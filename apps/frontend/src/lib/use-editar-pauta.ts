"use client";

// Hook de ESCRITA da pauta (docs/23, Fatia 1 — "Montar a pauta"). Só a camada de intenção: incluir
// (POST /sessoes/:id/pauta/itens), mover (PATCH .../itens/:item-id, CAS por lock-version) e retirar
// (DELETE .../itens/:item-id, soft-remove com classificação, CAS). A LEITURA continua com quem já lê a pauta
// (useSessaoPauta na montagem, usePauta no cockpit) — quem chama recarrega depois de cada resultado.
//
// Mover = trocar de lugar com o vizinho. O backend só grava `ordem` de UM item por PATCH (sem deslocar os
// outros; `ordem` não é única), então a troca são DOIS PATCHes: o item vai para a ordem do vizinho e o vizinho
// para a do item. Se o segundo falhar, a pauta fica com os dois na mesma ordem — nada se perde, e a tela
// recarrega e diz isso. Empate já existente (mesma ordem) vira um PATCH só, deslocando o item em 1.

import { useCallback, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { semCredencial } from "./modo";

/** Espelha `logic/fases-pauta` do backend. */
export type FasePauta =
  | "expediente"
  | "grande_expediente"
  | "ordem_do_dia"
  | "explicacoes_pessoais"
  | "tribuna_livre_cidadao";

/** Os tipos de item que carregam texto (todos menos `proposicao`) — `logic/tipos-item-pauta`. */
export type TipoItemTexto = "leitura" | "comunicado" | "homenagem";

export type NovoItemPauta =
  | { fase: FasePauta; tipoItem: "proposicao"; proposicaoId: string }
  | { fase: FasePauta; tipoItem: TipoItemTexto; textoDescricao: string };

/** `logic/tipos-remocao-pauta`. */
export type TipoRetirada = "exclusao" | "retirada_pedido_autor";

/** O mínimo de um item para as escritas com CAS. */
export interface ItemParaEscrita {
  id: string;
  ordem: number;
  lockVersion: number;
}

export type ResultadoPauta = { ok: true } | { ok: false; erro: string; conflito: boolean };

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

export const MSG_CONFLITO_PAUTA =
  "A pauta mudou enquanto esta tela estava aberta (outra pessoa a alterou, ou a sessão fechou). " +
  "A pauta foi recarregada; confira antes de tentar de novo.";

export const MSG_TROCA_PARCIAL =
  "Só metade da troca de ordem foi gravada: os dois itens ficaram na mesma posição. A pauta foi recarregada; " +
  "mova de novo para desempatar.";

async function falha(r: Response, acao: string): Promise<ResultadoPauta> {
  if (r.status === 409) return { ok: false, conflito: true, erro: MSG_CONFLITO_PAUTA };
  const corpo = await r.json().catch(() => null);
  return { ok: false, conflito: false, erro: corpo?.erro ?? `falha ao ${acao} (status ${r.status})` };
}

export function useEditarPauta(sessaoId: string | null, token: string | null) {
  const [enviando, setEnviando] = useState(false);
  const enviandoRef = useRef(false);

  /** Serializa as escritas: um clique duplo nunca dispara duas mutações concorrentes contra o mesmo CAS. */
  const executar = useCallback(
    async (fn: (base: string) => Promise<ResultadoPauta>): Promise<ResultadoPauta> => {
      if (semCredencial(token)) return { ok: false, conflito: false, erro: "sem token de autenticacao" };
      if (!sessaoId || !ID_VALIDO.test(sessaoId)) return { ok: false, conflito: false, erro: "sessão inválida" };
      if (enviandoRef.current) return { ok: false, conflito: false, erro: "Aguarde a alteração anterior terminar." };
      enviandoRef.current = true;
      setEnviando(true);
      try {
        return await fn(`/api/sessoes/${sessaoId}/pauta/itens`);
      } catch {
        return { ok: false, conflito: false, erro: "Falha de rede — tente novamente." };
      } finally {
        enviandoRef.current = false;
        setEnviando(false);
      }
    },
    [sessaoId, token],
  );

  const patchOrdem = useCallback(
    (base: string, item: ItemParaEscrita, novaOrdem: number) =>
      apiFetch(`${base}/${encodeURIComponent(item.id)}`, {
        token: token ?? undefined,
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ "nova-ordem": novaOrdem, "lock-version": item.lockVersion }),
      }),
    [token],
  );

  const incluir = useCallback(
    (novo: NovoItemPauta) =>
      executar(async (base) => {
        const corpo =
          novo.tipoItem === "proposicao"
            ? { fase: novo.fase, "tipo-item": novo.tipoItem, "proposicao-id": novo.proposicaoId }
            : { fase: novo.fase, "tipo-item": novo.tipoItem, "texto-descricao": novo.textoDescricao.trim() };
        if (novo.tipoItem !== "proposicao" && !novo.textoDescricao.trim()) {
          return { ok: false, conflito: false, erro: "Descreva o item antes de incluir." };
        }
        const r = await apiFetch(base, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(corpo),
        });
        return r.ok ? { ok: true } : falha(r, "incluir o item");
      }),
    [executar, token],
  );

  /** Troca `item` de lugar com `vizinho` (o item imediatamente acima ou abaixo NA MESMA FASE). */
  const mover = useCallback(
    (item: ItemParaEscrita, vizinho: ItemParaEscrita, direcao: "acima" | "abaixo") =>
      executar(async (base) => {
        if (item.ordem === vizinho.ordem) {
          const alvo = direcao === "acima" ? Math.max(0, vizinho.ordem - 1) : vizinho.ordem + 1;
          const r = await patchOrdem(base, item, alvo);
          return r.ok ? { ok: true } : falha(r, "mover o item");
        }
        const r1 = await patchOrdem(base, item, vizinho.ordem);
        if (!r1.ok) return falha(r1, "mover o item");
        const r2 = await patchOrdem(base, vizinho, item.ordem);
        if (!r2.ok) return { ok: false, conflito: true, erro: MSG_TROCA_PARCIAL };
        return { ok: true };
      }),
    [executar, patchOrdem],
  );

  const retirar = useCallback(
    (item: ItemParaEscrita, tipo: TipoRetirada, justificativa?: string) =>
      executar(async (base) => {
        const just = justificativa?.trim();
        const r = await apiFetch(`${base}/${encodeURIComponent(item.id)}`, {
          token: token ?? undefined,
          method: "DELETE",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ tipo, "lock-version": item.lockVersion, ...(just ? { justificativa: just } : {}) }),
        });
        return r.ok ? { ok: true } : falha(r, "retirar o item");
      }),
    [executar, token],
  );

  return { incluir, mover, retirar, enviando };
}
