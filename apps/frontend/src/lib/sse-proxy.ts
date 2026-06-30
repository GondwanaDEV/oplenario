// Proxy de SSE com STREAM REAL (não o rewrite do Next dev). Causa-raiz do live-push que não chegava ao
// browser: o `/api/:path*` era um rewrite afterFiles (precede rotas dinâmicas, então atropelava este handler)
// E o Next comprimia o SSE com gzip na saída → o `fetch` do browser (que pede `gzip`) só via bytes quando um
// bloco gzip enchia; com poucos eventos, nada chegava. O curl default não pede gzip, por isso "funcionava".
// Aqui: (1) o handler faz o fetch upstream SERVER-SIDE e devolve `up.body` por REFERÊNCIA — Next streama um
// ReadableStream sem bufferizar; (2) `Accept-Encoding: identity` no hop handler->backend; (3) `Cache-Control:
// no-transform` + `X-Accel-Buffering: no` desligam a compressão/buffering nos hops à frente. O next.config usa
// `fallback` (não afterFiles) p/ este handler vencer. É também production-correct: em prod o /api é reverse-proxy.
//
// SSRF: `backend` vem de env (BACKEND_URL, validada no next.config em produção), nunca do request; o path é
// fixo (/sessoes/<id>/plenario) e o `id` é validado antes de compor a URL. Só 3 headers atravessam.

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;
// O backend emite o `id:` do SSE como o seq monotônico (inteiro). Validar antes de repassar bloqueia
// Last-Event-ID opaco/gigante chegando ao Pedestal (review seg MINOR). Inválido => dropa (resume do início).
const LAST_EVENT_ID_VALIDO = /^[0-9]{1,64}$/;

export async function proxiarPlenario(
  req: Request,
  id: string,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<Response> {
  // `opts.backend` é injeção de dependência só de teste; em produção seria superfície de SSRF (review seg MINOR).
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }

  if (!ID_VALIDO.test(id)) {
    return new Response(JSON.stringify({ erro: "identificador de sessão inválido" }), {
      status: 400,
      headers: { "content-type": "application/json" },
    });
  }

  const backend = opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
  const f = opts?.fetchImpl ?? fetch;
  const url = `${backend}/sessoes/${encodeURIComponent(id)}/plenario`;

  const headers = new Headers({
    Accept: "text/event-stream",
    "Accept-Encoding": "identity",
  });
  const auth = req.headers.get("authorization");
  if (auth) headers.set("Authorization", auth);
  const lastEventId = req.headers.get("last-event-id");
  if (lastEventId && LAST_EVENT_ID_VALIDO.test(lastEventId)) headers.set("Last-Event-ID", lastEventId);

  let up: Response;
  try {
    up = await f(url, { headers, signal: req.signal, cache: "no-store" });
  } catch (e) {
    // cliente desconectou (navegação/aba fechada): encerramento ESPERADO de SSE, não erro de servidor.
    if (e instanceof Error && e.name === "AbortError") return new Response(null, { status: 499 });
    // backend inacessível: resposta estruturada que o cliente SSE reconhece (em vez de 500 HTML do Next).
    return new Response(JSON.stringify({ erro: "painel temporariamente indisponível" }), {
      status: 503,
      headers: { "content-type": "application/json" },
    });
  }

  if (!up.ok || !up.body) {
    await up.body?.cancel(); // libera o slot de conexão do pool undici imediatamente (review ts MINOR)
    return new Response(null, { status: up.status });
  }

  return new Response(up.body, {
    status: 200,
    headers: {
      "content-type": up.headers.get("content-type") ?? "text/event-stream",
      "cache-control": "no-cache, no-transform",
      "x-accel-buffering": "no",
    },
  });
}
