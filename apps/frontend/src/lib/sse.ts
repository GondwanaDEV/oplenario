// SSE sobre fetch (não EventSource): o EventSource nativo do browser NÃO suporta headers/cookie custom.
// fetch + ReadableStream suporta header + Last-Event-ID no resume. `extrairFrames` é o parser PURO (testado
// em sse.test.ts); `consumirSse` faz o IO, roteado pelo boundary único `apiFetch` (Onda D Slice 2): modo
// real (sem token) -> sem Authorization, cookie `sessao` rideia via credentials same-origin; modo dev (com
// token) -> Authorization: Bearer.

import { apiFetch } from "./api-fetch";

export interface FrameSse {
  event?: string;
  data: string;
  id?: string;
}

/** Lê um valor de campo SSE tolerando o espaço opcional após `:` (spec: um único espaço após os dois-pontos). */
function valorCampo(linha: string, prefixo: string): string {
  const v = linha.slice(prefixo.length);
  return v.startsWith(" ") ? v.slice(1) : v;
}

/**
 * Extrai os frames COMPLETOS de um buffer de texto, devolvendo o pedaço final incompleto como `resto`
 * (para concatenar com o próximo chunk). Frames são separados por linha em branco (\n\n). Normaliza \r\n.
 */
export function extrairFrames(buffer: string): { frames: FrameSse[]; resto: string } {
  const norm = buffer.replace(/\r\n/g, "\n");
  const partes = norm.split("\n\n");
  const resto = partes.pop() ?? ""; // o último pedaço pode estar incompleto (sem \n\n terminal)
  const frames: FrameSse[] = [];

  for (const bloco of partes) {
    if (bloco.trim() === "") continue; // bloco vazio (heartbeat) — nada a emitir
    const frame: FrameSse = { data: "" };
    const dataLinhas: string[] = [];
    for (const linha of bloco.split("\n")) {
      if (linha.startsWith(":")) continue; // comentário/keep-alive
      if (linha.startsWith("event:")) frame.event = valorCampo(linha, "event:");
      else if (linha.startsWith("data:")) dataLinhas.push(valorCampo(linha, "data:"));
      else if (linha.startsWith("id:")) frame.id = valorCampo(linha, "id:");
    }
    if (frame.event === undefined && dataLinhas.length === 0) continue; // só comentários
    frame.data = dataLinhas.join("\n");
    frames.push(frame);
  }
  return { frames, resto };
}

/**
 * Abre a conexão SSE autenticada e chama `aoFrame` para cada frame recebido. Resolve quando o stream fecha
 * (ou aborta via `signal`). `lastEventId` faz o resume desde o Last-Event-ID. Erros de rede propagam — o
 * chamador (hook) decide reconectar com backoff.
 */
export async function consumirSse(
  url: string,
  opts: { token?: string | null; signal?: AbortSignal; lastEventId?: string; aoFrame: (f: FrameSse) => void },
): Promise<void> {
  const resp = await apiFetch(url, {
    token: opts.token ?? undefined,
    headers: { Accept: "text/event-stream", ...(opts.lastEventId ? { "Last-Event-ID": opts.lastEventId } : {}) },
    signal: opts.signal,
    cache: "no-store",
  });
  if (!resp.ok || !resp.body) throw new Error(`SSE ${resp.status}`);

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const { frames, resto } = extrairFrames(buffer);
    buffer = resto;
    for (const f of frames) opts.aoFrame(f);
  }
}
