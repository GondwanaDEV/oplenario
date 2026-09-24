"use client";

// Hook de IO da votação conduzida pela Mesa (§22.6 eixo C) — as duas escritas que só existiam via API:
// ABRIR (POST /sessoes/:id/votacoes) e ENCERRAR (POST /sessoes/:id/votacoes/:vid/encerramento). Lê a
// votação em curso (GET /sessoes/:id/votacao-aberta; 404 = nenhuma) e a pauta (GET /sessoes/:id/pauta,
// camelizada aqui) para o seletor de objeto. A derivação de painel/opções é de `votacao-mesa-vista.ts`.
//
// TIPOS DE FIO hand-modeled: o codegen (gerar_sessoes/gerar_legislativo) ainda NÃO cobre as saídas de
// votação (AberturaOut/EncerramentoOut/VotacaoAbertaOut vivem em legislativo/wire/out/votacao.clj, fora de
// qualquer manifesto). Mesma disciplina de `use-meu-voto.ts`/`use-detalhe-votacao.ts` — modelar o pouco que
// a tela usa, localmente, camelizando na entrada. Se um dia entrarem no codegen, troca-se por import.
//
// LOCK-VERSION DO ENCERRAMENTO: `VotacaoAbertaOut` NÃO carrega lock-version — por desenho, `AberturaOut` é a
// ÚNICA fonte do token de CAS (docstring de wire/out/votacao.clj). Uma votação ABERTA nunca é mutada entre
// abrir e encerrar (não há suspender/retomar votação), então o seu lock-version fica em 0 até o
// encerramento. Guardamos o lock-version do AberturaOut quando ABRIMOS nesta sessão; ao RECUPERAR uma
// votação já aberta (page reload → só temos votacao-aberta, sem lock), caímos em 0 — correto para uma
// votação em curso. O 409 `:conflito/votacao-terminal` cobre o caso de já ter sido encerrada.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type ObjetoTipoVotacao = "proposicao" | "emenda" | "parecer" | "requerimento" | "redacao_final";
export type ModalidadeVotacao = "nominal" | "simbolica" | "secreta";
export type QuorumTipo =
  | "maioria_simples"
  | "maioria_absoluta"
  | "maioria_qualificada_2_3"
  | "maioria_qualificada_3_5";
export type EstadoVotacao = "aberta" | "encerrada" | "anulada";
export type ResultadoVotacao = "aprovada" | "rejeitada";

export interface ProposicaoResumoVotacao {
  tipo: string;
  ano: number;
  sequencial: number;
  ementa: string;
}

/** Recibo de POST .../votacoes (201). O `lockVersion` é o token de CAS do encerramento. */
export interface AberturaVotacaoOut {
  id: string;
  estado: EstadoVotacao;
  lockVersion: number;
}

/** Recibo de POST .../encerramento (200). Totais/base ausentes na modalidade simbólica. */
export interface EncerramentoVotacaoOut {
  id: string;
  estado: EstadoVotacao;
  resultado?: ResultadoVotacao | null;
  totalSim?: number | null;
  totalNao?: number | null;
  totalAbstencao?: number | null;
  baseMembros?: number | null;
}

/** O comum às 3 modalidades de `VotacaoAbertaOut` — o que a Mesa precisa para encerrar (o `votos` nominal é
 * ignorado aqui; o placar vivo é do telão). */
export interface VotacaoAbertaResumo {
  votacaoId: string;
  modalidade: ModalidadeVotacao;
  objetoTipo: ObjetoTipoVotacao;
  objetoId: string;
  proposicao: ProposicaoResumoVotacao | null;
}

/** Item de pauta camelizado (o suficiente para o seletor de objeto). */
export interface ItemPautaVotacao {
  id: string;
  tipoItem: string;
  proposicaoId?: string | null;
  fase: string;
  ordem: number;
  proposicao?: ProposicaoResumoVotacao | null;
}

export type EstadoDados = "carregando" | "pronto" | "erro";

export interface AbrirArgs {
  objetoTipo: ObjetoTipoVotacao;
  objetoId: string;
  modalidade: ModalidadeVotacao;
  quorumTipo: QuorumTipo;
  pautaItemId?: string | null;
}

export type ResultadoAbrir = { ok: true; abertura: AberturaVotacaoOut } | { ok: false; erro: string };
export type ResultadoEncerrar =
  | { ok: true; encerramento: EncerramentoVotacaoOut }
  | { ok: false; erro: string; conflito: boolean };

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

const MSG_CONFLITO_ENCERRAR =
  "Esta votação já foi encerrada (ou mudou de estado) enquanto a tela estava aberta. A tela recarregou o " +
  "estado atual; confira antes de tentar de novo.";

async function buscarVotacaoAberta(
  sessaoId: string,
  token: string | null,
  signal?: AbortSignal,
): Promise<VotacaoAbertaResumo | null> {
  const r = await apiFetch(`/api/sessoes/${sessaoId}/votacao-aberta`, {
    token: token ?? undefined,
    signal,
    cache: "no-store",
  });
  if (r.status === 404) return null; // 404 = nenhuma votação aberta agora (estado legítimo, não erro)
  if (!r.ok) throw new Error(`votacao-aberta ${r.status}`);
  return camelizarChaves(await r.json()) as VotacaoAbertaResumo;
}

async function buscarPautaItens(
  sessaoId: string,
  token: string | null,
  signal?: AbortSignal,
): Promise<ItemPautaVotacao[]> {
  const r = await apiFetch(`/api/sessoes/${sessaoId}/pauta`, {
    token: token ?? undefined,
    signal,
    cache: "no-store",
  });
  if (!r.ok) throw new Error(`pauta ${r.status}`);
  const p = camelizarChaves(await r.json()) as { itens?: ItemPautaVotacao[] };
  return p.itens ?? [];
}

export function useVotacaoMesa(sessaoId: string, token: string | null) {
  const [votacaoAberta, setVotacaoAberta] = useState<VotacaoAbertaResumo | null>(null);
  const [itens, setItens] = useState<ItemPautaVotacao[]>([]);
  const [estado, setEstado] = useState<EstadoDados>("carregando");
  const [erro, setErro] = useState<string | null>(null);
  const idValido = ID_VALIDO.test(sessaoId);

  // lock-version por votação-id, alimentado pelo AberturaOut quando ABRIMOS. Miss → 0 (votação aberta em
  // curso nunca foi mutada, ver docstring do arquivo).
  const lockPorVotacaoRef = useRef<Map<string, number>>(new Map());
  const vivoRef = useRef(true);
  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  const recarregar = useCallback(async () => {
    if (semCredencial(token) || !idValido) return;
    try {
      const [va, its] = await Promise.all([
        buscarVotacaoAberta(sessaoId, token).catch(() => null),
        buscarPautaItens(sessaoId, token).catch(() => [] as ItemPautaVotacao[]),
      ]);
      if (!vivoRef.current) return;
      setVotacaoAberta(va);
      setItens(its);
      setEstado("pronto");
    } catch {
      // recarregar explícito nunca zera a tela
    }
  }, [sessaoId, token, idValido]);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return;
    const controller = new AbortController();
    let vivo = true;
    (async () => {
      try {
        const va = await buscarVotacaoAberta(sessaoId, token, controller.signal);
        if (!vivo) return;
        setVotacaoAberta(va);
        setEstado("pronto");
      } catch {
        if (!vivo || controller.signal.aborted) return;
        setEstado("erro");
        setErro("Não foi possível carregar a votação.");
      }
      // pauta é secundária: erro aqui não derruba o painel (só some o seletor de objeto)
      try {
        const its = await buscarPautaItens(sessaoId, token, controller.signal);
        if (vivo) setItens(its);
      } catch {
        /* seletor de objeto degrada para vazio */
      }
    })();
    return () => {
      vivo = false;
      controller.abort();
    };
  }, [sessaoId, token, idValido]);

  const abrir = useCallback(
    async (args: AbrirArgs): Promise<ResultadoAbrir> => {
      if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
      if (!args.objetoId) return { ok: false, erro: "Escolha o objeto da votação." };
      const corpo: Record<string, unknown> = {
        "objeto-tipo": args.objetoTipo,
        "objeto-id": args.objetoId,
        modalidade: args.modalidade,
        "quorum-tipo": args.quorumTipo,
      };
      if (args.pautaItemId) corpo["pauta-item-id"] = args.pautaItemId;

      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/votacoes`, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(corpo),
        });
      } catch {
        return { ok: false, erro: "Falha de rede — tente novamente." };
      }
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        return { ok: false, erro: corpoErro?.erro ?? `falha ao abrir votação (status ${r.status})` };
      }
      const abertura = camelizarChaves(await r.json()) as AberturaVotacaoOut;
      lockPorVotacaoRef.current.set(abertura.id, abertura.lockVersion);
      await recarregar();
      return { ok: true, abertura };
    },
    [sessaoId, token, recarregar],
  );

  const encerrar = useCallback(
    async (resultado?: ResultadoVotacao): Promise<ResultadoEncerrar> => {
      if (semCredencial(token)) return { ok: false, conflito: false, erro: "sem token de autenticacao" };
      const va = votacaoAberta;
      if (!va) return { ok: false, conflito: false, erro: "Nenhuma votação aberta para encerrar." };
      const lockVersion = lockPorVotacaoRef.current.get(va.votacaoId) ?? 0;
      const corpo: Record<string, unknown> = { "lock-version": lockVersion };
      if (resultado) corpo.resultado = resultado;

      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/votacoes/${encodeURIComponent(va.votacaoId)}/encerramento`, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(corpo),
        });
      } catch {
        return { ok: false, conflito: false, erro: "Falha de rede — tente novamente." };
      }
      if (!r.ok) {
        if (r.status === 409) {
          await recarregar();
          return { ok: false, conflito: true, erro: MSG_CONFLITO_ENCERRAR };
        }
        const corpoErro = await r.json().catch(() => null);
        return { ok: false, conflito: false, erro: corpoErro?.erro ?? `falha ao encerrar (status ${r.status})` };
      }
      const encerramento = camelizarChaves(await r.json()) as EncerramentoVotacaoOut;
      await recarregar();
      return { ok: true, encerramento };
    },
    [sessaoId, token, votacaoAberta, recarregar],
  );

  if (semCredencial(token)) {
    return {
      votacaoAberta: null, itens: [] as ItemPautaVotacao[], estado: "erro" as EstadoDados,
      erro: "Sem credencial de sessão (token).", recarregar, abrir, encerrar,
    };
  }
  if (!idValido) {
    return {
      votacaoAberta: null, itens: [] as ItemPautaVotacao[], estado: "erro" as EstadoDados,
      erro: "Identificador de sessão inválido.", recarregar, abrir, encerrar,
    };
  }
  return { votacaoAberta, itens, estado, erro, recarregar, abrir, encerrar };
}
