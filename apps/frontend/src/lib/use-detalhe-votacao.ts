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

/** Identidade da busca em voo. Sessao e votacao sao UUIDs (sem `|`), mas o separador improvavel mantem a
 *  chave injetora-segura mesmo se um dia virarem texto livre. */
function chaveDe(sessaoId: string, votacaoId: string): string {
  return `${sessaoId}|${votacaoId}`;
}

/** O resultado de uma busca JUNTO da chave que a originou — ver o comentario no hook. */
interface Carga {
  chave: string;
  estado: Extract<Estado, "pronto" | "erro">;
  dados: DetalheVotacaoOut | null;
}

export function useDetalheVotacao(
  sessaoId: string | null,
  votacaoId: string | null,
  token: string | null,
): { dados: DetalheVotacaoOut | null; estado: Estado } {
  // A carga guarda a CHAVE que a produziu, e nao so' o resultado. E' isso que permite derivar "carregando"
  // sem nenhum setState sincrono no corpo do effect (react-hooks/set-state-in-effect): quando a votacao
  // muda, a chave atual deixa de casar com a da carga e o retorno ja' diz "carregando" no MESMO render —
  // sem o flash de dado velho sob o rotulo da votacao nova que o `setEstado("carregando")` anterior
  // deixava passar por um render. Todo setState daqui em diante acontece no callback assincrono.
  const [carga, setCarga] = useState<Carga | null>(null);

  const ocioso = !sessaoId || !votacaoId;
  const semToken = semCredencial(token);
  const chave = ocioso ? null : chaveDe(sessaoId, votacaoId);

  useEffect(() => {
    if (chave === null || semToken) return;
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarDetalheVotacao(sessaoId!, votacaoId!, token);
        if (!vivo) return;
        setCarga(
          resultado === null
            ? { chave, estado: "erro", dados: null }
            : { chave, estado: "pronto", dados: resultado },
        );
      } catch {
        if (vivo) setCarga({ chave, estado: "erro", dados: null });
      }
    })();
    return () => {
      vivo = false;
    };
  }, [chave, semToken, sessaoId, votacaoId, token]);

  // Nenhuma votacao aberta nao e' falha: e' repouso. Distinguir os dois e' o que impede o cockpit de
  // acusar erro quando simplesmente nao ha' o que votar.
  if (ocioso) return { dados: null, estado: "ocioso" };
  if (semToken) return { dados: null, estado: "erro" };
  // A carga de OUTRA votacao nao vale para esta: ate' a busca desta chegar, o estado honesto e' "carregando".
  if (carga === null || carga.chave !== chave) return { dados: null, estado: "carregando" };
  return { dados: carga.dados, estado: carga.estado };
}
