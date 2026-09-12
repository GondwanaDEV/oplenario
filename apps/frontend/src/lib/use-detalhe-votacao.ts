"use client";

// Hook de GET /api/sessoes/:id/votacoes/:votacaoId (fatia "demo-tres-consertos" #2) — o QUE está em
// votação: resolve o objeto polimórfico (objeto-tipo/objeto-id, já carregado pelo placar do
// plenario-reducer) pra um título de exibição. Mirror de use-pauta.ts (GET one-shot autenticado, sem
// push ao vivo — o objeto de uma votação não muda depois de aberta). Gate `vereador` no backend, mesmo
// papel de `/meu-voto`.
//
// Fail-closed em corpo malformado (mesmo racional de use-meu-identidade.ts/use-eu.ts): nunca assume o
// shape do JSON. `proposicao` ausente/null é um resultado LEGÍTIMO (emenda/parecer/requerimento, ainda
// não resolvidos por completo nesta fatia — ver docstring de controllers/detalhe-votacao no backend);
// só um `objetoTipo` que não é string vira erro de verdade.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type ProposicaoResumoDetalheVotacao = { tipo: string; ano: number; sequencial: number; ementa: string };
export type DetalheVotacaoOut = { objetoTipo: string; proposicao: ProposicaoResumoDetalheVotacao | null };

// "ocioso" (sem votacaoId — nenhuma votação aberta agora) é DISTINTO de "erro" (havia votação, a busca
// falhou): a página não pode confundir "nada em votação" com "não consegui identificar a matéria".
export type EstadoDetalheVotacao = "ocioso" | "carregando" | "pronto" | "erro";
type Estado = EstadoDetalheVotacao;

function comoProposicaoResumo(d: unknown): ProposicaoResumoDetalheVotacao | null {
  if (d === null || typeof d !== "object") return null;
  const { tipo, ano, sequencial, ementa } = d as Record<string, unknown>;
  if (typeof tipo !== "string" || typeof ano !== "number" || typeof sequencial !== "number" || typeof ementa !== "string") {
    return null;
  }
  return { tipo, ano, sequencial, ementa };
}

function comoDetalheVotacao(d: unknown): DetalheVotacaoOut | null {
  if (d === null || typeof d !== "object") return null;
  const { objetoTipo, proposicao } = d as Record<string, unknown>;
  if (typeof objetoTipo !== "string") return null;
  return { objetoTipo, proposicao: comoProposicaoResumo(proposicao) };
}

async function buscarDetalheVotacao(
  sessaoId: string,
  votacaoId: string,
  token: string | null,
): Promise<DetalheVotacaoOut | null> {
  const r = await apiFetch(`/api/sessoes/${sessaoId}/votacoes/${votacaoId}`, {
    token: token ?? undefined,
    cache: "no-store",
  });
  if (!r.ok) return null;
  return comoDetalheVotacao(camelizarChaves(await r.json()));
}

export function useDetalheVotacao(
  sessaoId: string | null,
  votacaoId: string | null,
  token: string | null,
): { dados: DetalheVotacaoOut | null; estado: Estado } {
  const [dados, setDados] = useState<DetalheVotacaoOut | null>(null);
  const [estado, setEstado] = useState<Estado>(sessaoId && votacaoId ? "carregando" : "ocioso");

  useEffect(() => {
    if (!sessaoId || !votacaoId) {
      setDados(null);
      setEstado("ocioso");
      return;
    }
    if (semCredencial(token)) {
      setEstado("erro");
      return;
    }
    let vivo = true;
    setEstado("carregando");
    (async () => {
      try {
        const resultado = await buscarDetalheVotacao(sessaoId, votacaoId, token);
        if (!vivo) return;
        if (resultado === null) {
          setEstado("erro");
          return;
        }
        setDados(resultado);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [sessaoId, votacaoId, token]);

  return { dados, estado };
}
