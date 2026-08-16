"use client";

// Hook de IO da FOLHA DA SESSÃO (§22.6 eixo C, Etapa 5 fatia 6) — as 4 rotas do backend (Etapa 5 fatia 5):
// `GET /sessoes/:id/folhas` (lista) · `POST /sessoes/:id/folha` (congela) ·
// `GET /sessoes/:id/folhas/:versao` (HTML canônico) · `GET /sessoes/:id/folhas/:versao/pdf` (download).
// Sem SSE: ao contrário da chamada, a folha não é operada ao vivo — é um artefato gerado sob demanda (D5
// do brief). `recarregar` é a única forma de atualizar a lista; nenhum canal empurra frame aqui.
//
// Toda ordenação/rotulagem/tradução de erro é de `folha-vista.ts` (módulo puro, testado em isolamento) —
// este hook só faz IO e nunca reimplementa nada de lá.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { mensagemDeErroFolha, retryAfterSegundos } from "./folha-vista";
import type { FolhaMetadadosOut, FolhasDaSessaoOut } from "./contrato-sessoes.gen";
import { semCredencial } from "./modo";

export type EstadoFolha = "carregando" | "pronto" | "erro";

export type ResultadoGerar = { ok: true; folha: FolhaMetadadosOut } | { ok: false; erro: string };
export type ResultadoHtml = { ok: true; html: string } | { ok: false; erro: string };
export type ResultadoDownload = { ok: true } | { ok: false; erro: string };

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

/** Lê `{erro}` do corpo de uma resposta que falhou — best-effort: um corpo que não é JSON (ex.: 502 de
 * proxy) não pode fazer a tradução de erro lançar por cima do erro original. */
async function corpoErroDe(r: Response): Promise<string | undefined> {
  try {
    const j = (await r.json()) as { erro?: string };
    return j?.erro;
  } catch {
    return undefined;
  }
}

async function erroTraduzido(r: Response): Promise<string> {
  const corpo = await corpoErroDe(r);
  const retryAfter = retryAfterSegundos(r.headers.get("Retry-After"));
  return mensagemDeErroFolha(corpo, r.status, retryAfter);
}

/** Nome determinístico do download — espelha `adapters/out/folha.clj:->pdf-download`
 * (`folha-<sessao-id>-v<versao>.pdf`), para o arquivo salvo pelo navegador ter o MESMO nome que o servidor
 * já decidiu, nunca um nome inventado no cliente. */
export function nomeArquivoPdf(sessaoId: string, versao: number): string {
  return `folha-${sessaoId}-v${versao}.pdf`;
}

export function useFolha(sessaoId: string, token: string | null) {
  const [versoes, setVersoes] = useState<FolhaMetadadosOut[] | null>(null);
  const [estado, setEstado] = useState<EstadoFolha>("carregando");
  const [erro, setErro] = useState<string | null>(null);
  const idValido = ID_VALIDO.test(sessaoId);

  const vivoRef = useRef(true);
  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  const buscarVersoes = useCallback(
    async (signal?: AbortSignal): Promise<FolhaMetadadosOut[]> => {
      const r = await apiFetch(`/api/sessoes/${sessaoId}/folhas`, { token: token ?? undefined, signal, cache: "no-store" });
      if (!r.ok) throw new Error(await erroTraduzido(r));
      const j = camelizarChaves(await r.json()) as FolhasDaSessaoOut;
      return j.folhas;
    },
    [sessaoId, token],
  );

  const recarregar = useCallback(async () => {
    try {
      const v = await buscarVersoes();
      if (vivoRef.current) {
        setVersoes(v);
        setEstado("pronto");
        setErro(null);
      }
    } catch (e) {
      if (!vivoRef.current) return;
      setEstado("erro");
      setErro(e instanceof Error ? e.message : "Não foi possível carregar as versões da folha.");
    }
  }, [buscarVersoes]);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return;
    const controller = new AbortController();
    (async () => {
      try {
        const v = await buscarVersoes(controller.signal);
        if (vivoRef.current) {
          setVersoes(v);
          setEstado("pronto");
        }
      } catch (e) {
        if (controller.signal.aborted || !vivoRef.current) return;
        setEstado("erro");
        setErro(e instanceof Error ? e.message : "Não foi possível carregar as versões da folha.");
      }
    })();
    return () => controller.abort();
  }, [sessaoId, token, idValido, buscarVersoes]);

  /** POST /sessoes/:id/folha — congela uma nova versão (ou devolve a existente, no dedup de 30s de D9; a
   * tela não distingue os dois casos, o servidor decide). Sem corpo. Recarrega a lista em sucesso — a folha
   * recém-congelada precisa aparecer imediatamente. */
  const gerar = useCallback(async (): Promise<ResultadoGerar> => {
    if (semCredencial(token)) return { ok: false, erro: "Sem credencial de sessão." };
    const r = await apiFetch(`/api/sessoes/${sessaoId}/folha`, { token: token ?? undefined, method: "POST" });
    if (!r.ok) return { ok: false, erro: await erroTraduzido(r) };
    const folha = camelizarChaves(await r.json()) as FolhaMetadadosOut;
    await recarregar();
    return { ok: true, folha };
  }, [sessaoId, token, recarregar]);

  /** GET /sessoes/:id/folhas/:versao — os BYTES do HTML canônico congelado, como TEXTO (nunca `.json()`: a
   * resposta é `text/html`, e passar isso por `JSON.parse` lançaria sobre um documento válido). */
  const buscarHtml = useCallback(
    async (versao: number): Promise<ResultadoHtml> => {
      if (semCredencial(token)) return { ok: false, erro: "Sem credencial de sessão." };
      const r = await apiFetch(`/api/sessoes/${sessaoId}/folhas/${versao}`, { token: token ?? undefined, cache: "no-store" });
      if (!r.ok) return { ok: false, erro: await erroTraduzido(r) };
      return { ok: true, html: await r.text() };
    },
    [sessaoId, token],
  );

  /** GET /sessoes/:id/folhas/:versao/pdf — busca os bytes via `apiFetch` (autentica igual nos dois
   * ambientes — cookie em produção, header em dev) e dispara o download client-side via
   * `URL.createObjectURL` + `<a download>` sintético. Escolha deliberada em vez de um `<a href="/api/…">`
   * cru: um link direto NÃO carrega o header `Authorization` do modo dev (o token só viaja dentro de
   * `apiFetch`), então funcionaria em produção (cookie de sessão acompanha navegação) mas quebraria
   * exatamente no ambiente onde se desenvolve — mesma armadilha que D10 resolveu para o visualizador HTML. */
  const baixarPdf = useCallback(
    async (versao: number): Promise<ResultadoDownload> => {
      if (semCredencial(token)) return { ok: false, erro: "Sem credencial de sessão." };
      const r = await apiFetch(`/api/sessoes/${sessaoId}/folhas/${versao}/pdf`, { token: token ?? undefined });
      if (!r.ok) return { ok: false, erro: await erroTraduzido(r) };
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      try {
        const a = document.createElement("a");
        a.href = url;
        a.download = nomeArquivoPdf(sessaoId, versao);
        document.body.appendChild(a);
        a.click();
        a.remove();
      } finally {
        URL.revokeObjectURL(url);
      }
      return { ok: true };
    },
    [sessaoId, token],
  );

  if (semCredencial(token)) {
    return {
      versoes: null, estado: "erro" as EstadoFolha, erro: "Sem credencial de sessão (token).",
      recarregar, gerar, buscarHtml, baixarPdf,
    };
  }
  if (!idValido) {
    return {
      versoes: null, estado: "erro" as EstadoFolha, erro: "Identificador de sessão inválido.",
      recarregar, gerar, buscarHtml, baixarPdf,
    };
  }
  return { versoes, estado, erro, recarregar, gerar, buscarHtml, baixarPdf };
}
