"use client";

// Hook de mutação — POST /api/legislativo/proposicoes/:id/recebimento (fatia 2b): quem recebe a carga assina
// o recebimento da movimentação que VIU na tela. O corpo é só `{movimentacao-id}` (wire/in
// ReceberMovimentacao é :closed): quem recebe vem do token, a hora e o estado vêm do servidor.
//
// Mesmo idioma de use-tramitar.ts (guard `enviandoRef` contra duplo clique, `vivoRef`). Devolve o resultado
// em vez de lançar: a tela precisa do `motivo` do 409 — `movimentacao-divergente` pede RECARREGAR (a matéria
// andou depois que a tela carregou; não se assina o que não se viu), `sem-recebimento-pendente` também
// (alguém já recebeu).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { semCredencial } from "./modo";

export type ResultadoReceber =
  | { ok: true }
  | { ok: false; erro: string; recarregar: boolean };

export function useReceber(token: string | null) {
  const [enviando, setEnviando] = useState(false);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function receber(proposicaoId: string, movimentacaoId: string): Promise<ResultadoReceber> {
    if (semCredencial(token)) return { ok: false, erro: "Sessão sem credencial — entre de novo.", recarregar: false };
    if (enviandoRef.current) return { ok: false, erro: "O recebimento já está sendo registrado.", recarregar: false };
    enviandoRef.current = true;
    setEnviando(true);
    try {
      const r = await apiFetch(`/api/legislativo/proposicoes/${encodeURIComponent(proposicaoId)}/recebimento`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ "movimentacao-id": movimentacaoId }),
      });
      if (r.ok) return { ok: true };
      const corpo = await r.json().catch(() => null);
      if (r.status === 403) {
        return {
          ok: false,
          erro: "Você não está entre quem o rito desta Casa autoriza a receber esta carga.",
          recarregar: false,
        };
      }
      if (r.status === 409) {
        const motivo = corpo?.motivo;
        const erro =
          motivo === "movimentacao-divergente"
            ? "A matéria se movimentou depois que esta tela carregou. Confira a situação atualizada antes de assinar."
            : motivo === "sem-recebimento-pendente"
              ? "Esta carga já foi recebida — a situação foi atualizada."
              : (corpo?.erro ?? "Não foi possível registrar o recebimento.");
        return { ok: false, erro, recarregar: true };
      }
      return { ok: false, erro: `Não foi possível registrar o recebimento (status ${r.status}).`, recarregar: false };
    } catch {
      return { ok: false, erro: "Não foi possível registrar o recebimento: falha de rede. Tente de novo.", recarregar: false };
    } finally {
      enviandoRef.current = false;
      if (vivoRef.current) setEnviando(false);
    }
  }

  return { receber, enviando };
}
