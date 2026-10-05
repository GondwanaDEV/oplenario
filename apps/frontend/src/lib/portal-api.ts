// Cliente de leitura pública do Portal do Cidadão (Task 0.3, Fatia A2.0). Espelha `buscarOuNull` de
// use-mesa.ts, mas para a superfície SEM auth (nenhuma rota autenticada nesta fatia): sem header
// Authorization, `!ok` ou throw sempre viram `null` — "degradação por seção" (Global Constraints do
// plano): uma seção com fetch falho/vazio degrada sozinha, nunca derruba a página inteira.
//
// `buscarPublico` recebe SEGMENTOS de path (não um `caminho` já concatenado) — review de segurança
// A2.0: um `caminho` único concatenado direto na URL deixava passar `../` vindo de qualquer segmento
// não confiável (ex.: um `ente` da URL). Cada segmento é codificado individualmente antes do join.

import { camelizarChaves } from "./boundary";

function codificarSegmento(segmento: string): string {
  const codificado = encodeURIComponent(segmento);
  // encodeURIComponent não escapa "."/".." (não são reservados) — sem isso, um segmento igual a ".."
  // continuaria significando "sobe um nível" quando o navegador resolve a URL relativa, escapando do
  // prefixo /api/portal/casa/. Só os segmentos puramente de pontos precisam desse reforço.
  return /^\.+$/.test(codificado) ? codificado.replace(/\./g, "%2E") : codificado;
}

export async function buscarPublico<T>(...segmentos: string[]): Promise<T | null> {
  try {
    const caminho = segmentos.map(codificarSegmento).join("/");
    const r = await fetch(`/api/portal/casa/${caminho}`, { cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as T;
  } catch {
    return null;
  }
}

// `buscarPublicoDetalhado` — o mesmo fetch, para a página que precisa dizer ao cidadão QUAL dos dois aconteceu:
// "isto não existe" (404) ou "não consegui agora" (5xx, rede, 400). `consulta` vira query string (só o que veio
// preenchido); nunca lança.
export type BuscaPublica<T> = { estado: "ok"; dado: T } | { estado: "nao-encontrado" } | { estado: "erro" };

export async function buscarPublicoDetalhado<T>(
  segmentos: string[],
  consulta: Record<string, string> = {},
): Promise<BuscaPublica<T>> {
  try {
    const caminho = segmentos.map(codificarSegmento).join("/");
    const q = new URLSearchParams(Object.entries(consulta).filter(([, v]) => v !== "")).toString();
    const r = await fetch(`/api/portal/casa/${caminho}${q ? `?${q}` : ""}`, { cache: "no-store" });
    if (r.status === 404) return { estado: "nao-encontrado" };
    if (!r.ok) return { estado: "erro" };
    return { estado: "ok", dado: camelizarChaves(await r.json()) as T };
  } catch {
    return { estado: "erro" };
  }
}

// `buscarNomeCasa` roda em SERVER COMPONENT (page.tsx), NÃO em client — por isso não pode usar
// `buscarPublico` (fetch relativo `/api/...`, que só resolve no browser via o rewrite same-origin de
// next.config.ts). Aqui o fetch é direto ao backend com a MESMA env var (`BACKEND_URL`) que o rewrite usa
// como alvo — servidor-a-servidor, sem depender de origem de browser. Resolve o nome real da Câmara ANTES
// do primeiro paint (sem flash de UUID); falha/timeout -> null, e o caller (page.tsx) degrada pro slug cru
// da URL — nunca pior que o comportamento anterior a este fix.
const backend = process.env.BACKEND_URL ?? "http://localhost:8888";

// `resolverCasa` é o primitivo: distingue "esta Casa NÃO existe" de "não consegui resolver agora".
// Motivo (achado do teste exploratório contra a homolog, método docs/20): `buscarNomeCasa` colapsava os
// dois casos em `null`, e a CAPA (`/portal/casa/[ente]`) degradava pro slug cru nos dois — renderizando o
// Portal do Cidadão INTEIRO para um ente inexistente ou malformado, com o id cru no cabeçalho e no
// `© 2026 <slug>` do rodapé. Um link errado exibia um portal de transparência crível para uma Casa que
// não existe. 404 (uuid bem-formado, sem Casa) e 400 (id malformado) são ambos veredito definitivo de
// "não existe"; qualquer outra falha (5xx, timeout, rede) é transitória e MANTÉM a degradação documentada.
// `acessoRestritoDesde` (ADR-0018): a Casa suspensa — o portal segue no ar e mostra a faixa, só com a data (o motivo
// não é público e nem vem do backend). Ausente quando a Casa está ativa.
// `encerrada` (ADR-0018 fatia 2): a Câmara deixou de usar O Plenário e os dados dela foram apagados — o backend
// responde 410 em toda rota dela, com o nome (público, do registro), a data e, se informado, para onde foi o acervo.
export type ResolucaoCasa =
  | { estado: "ok"; nomeOficial: string; nomeCurto?: string; acessoRestritoDesde?: string }
  | { estado: "encerrada"; nome: string | null; encerradaEm: string | null; destinoAcervoUrl: string | null }
  | { estado: "inexistente" }
  | { estado: "indisponivel" };

/** O corpo do 410 da Câmara encerrada -> a resolução. Só https sai como link (o backend já só aceita https). */
export function casaEncerrada(corpo: unknown): Extract<ResolucaoCasa, { estado: "encerrada" }> {
  const c = (corpo ?? {}) as Record<string, unknown>;
  const texto = (v: unknown) => (typeof v === "string" && v.trim() ? v : null);
  const destino = texto(c["destino-acervo-url"]);
  return {
    estado: "encerrada",
    nome: texto(c.nome),
    encerradaEm: texto(c["encerrada-em"]),
    destinoAcervoUrl: destino && /^https:\/\//.test(destino) ? destino : null,
  };
}

export async function resolverCasa(ente: string): Promise<ResolucaoCasa> {
  try {
    const r = await fetch(`${backend}/portal/casa/${codificarSegmento(ente)}`, { cache: "no-store" });
    if (r.status === 410) return casaEncerrada(await r.json().catch(() => ({})));
    if (r.status === 404 || r.status === 400) return { estado: "inexistente" };
    if (!r.ok) return { estado: "indisponivel" };
    const c = camelizarChaves(await r.json()) as { nomeOficial: string; nomeCurto?: string; acessoRestritoDesde?: string | null };
    return {
      estado: "ok",
      nomeOficial: c.nomeOficial,
      nomeCurto: c.nomeCurto,
      ...(c.acessoRestritoDesde ? { acessoRestritoDesde: c.acessoRestritoDesde } : {}),
    };
  } catch {
    return { estado: "indisponivel" };
  }
}

// Contrato preservado VERBATIM (null p/ qualquer não-ok) — as telas internas (matéria, vereador) degradam
// pro slug de propósito: elas já têm o seu próprio "não encontrado" para o objeto que exibem, e o nome da
// Casa ali é moldura, não o assunto. Só a CAPA precisa do veredito, e usa `resolverCasa`.
// `acessoRestritoDesde` (ADR-0018) segue junto quando a Casa está suspensa: as páginas de formulário mostram a faixa.
export async function buscarNomeCasa(
  ente: string,
): Promise<{ nomeOficial: string; nomeCurto?: string; acessoRestritoDesde?: string } | null> {
  const r = await resolverCasa(ente);
  if (r.estado !== "ok") return null;
  return {
    nomeOficial: r.nomeOficial,
    nomeCurto: r.nomeCurto,
    ...(r.acessoRestritoDesde ? { acessoRestritoDesde: r.acessoRestritoDesde } : {}),
  };
}
