"use client";

// Hook de detalhe da sessão-alvo (Onda C Slice C2, pauta-convocacao) — dado um sessaoId, busca em paralelo
// GET /api/sessoes/:id (SessaoOut) e GET /api/sessoes/:id/pauta (PautaOut). Mesmo padrão de
// use-mesa.ts/use-tramitacao-board.ts: fetch autenticado, camelizarChaves do boundary, estados
// carregando/pronto/erro, cleanup por `vivo`. `sessaoId` nulo (nenhuma sessão agendada, ou ainda
// carregando a lista de sessões) -> estado "pronto" com sessao/pauta null: não é erro, é a ausência
// honesta de sessão-alvo, tratada pela página.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export interface SessaoOut {
  id: string;
  sessaoLegislativaId: string;
  tipoSessao: string;
  numeroSequencial: number;
  estado: string;
  modalidade: string;
  delibera: boolean;
  transmitePublica: boolean;
  geraAtaRegimental: boolean;
  permiteVotoSecreto: boolean;
  permiteModalidadeRemota: boolean;
  agendadaPara: string | null;
  abertaEm: string | null;
  encerradaEm: string | null;
  motivoNaoRealizada: string | null;
}

export interface PautaItemOut {
  id: string;
  fase: string;
  tipoItem: string;
  proposicaoId?: string;
  /** Resumo da matéria (enriquecimento do Modo TV) — ausente quando legislativo não respondeu. */
  proposicao?: { tipo: string; ano: number; sequencial: number; ementa: string } | null;
  textoDescricao?: string;
  ordem: number;
  /** Token de CAS exigido por PATCH/DELETE do item (docs/23 Fatia 1). */
  lockVersion: number;
}

export interface PautaOut {
  sessaoId: string;
  itens: PautaItemOut[];
}

type Estado = "carregando" | "pronto" | "erro";

export function useSessaoPauta(token: string | null, sessaoId: string | null) {
  const [sessao, setSessao] = useState<SessaoOut | null>(null);
  const [pauta, setPauta] = useState<PautaOut | null>(null);
  const [estado, setEstado] = useState<Estado>(sessaoId ? "carregando" : "pronto");
  const [sessaoIdAnterior, setSessaoIdAnterior] = useState(sessaoId);
  // `versao` sobe a cada `recarregar()` (depois de uma escrita na pauta) e re-dispara o efeito de busca.
  const [versao, setVersao] = useState(0);
  const recarregar = useCallback(() => setVersao((v) => v + 1), []);

  // Reset ao trocar de sessaoId (mesmo padrão de use-proposicoes.ts): reset DURANTE O RENDER, não dentro do
  // useEffect — eslint-plugin-react-hooks v7 (set-state-in-effect) exige isso em vez de um setState
  // síncrono no topo do efeito.
  if (sessaoId !== sessaoIdAnterior) {
    setSessaoIdAnterior(sessaoId);
    setVersao(0);
    setEstado(sessaoId ? "carregando" : "pronto");
    if (!sessaoId) {
      setSessao(null);
      setPauta(null);
    }
  }

  useEffect(() => {
    if (semCredencial(token) || !sessaoId) return;
    let vivo = true;
    (async () => {
      try {
        const [rSessao, rPauta] = await Promise.all([
          apiFetch(`/api/sessoes/${encodeURIComponent(sessaoId)}`, { token: token ?? undefined, cache: "no-store" }),
          apiFetch(`/api/sessoes/${encodeURIComponent(sessaoId)}/pauta`, {
            token: token ?? undefined,
            cache: "no-store",
          }),
        ]);
        if (!vivo) return;
        if (!rSessao.ok || !rPauta.ok) {
          // Recarga depois de escrita: mantém a pauta já na tela (a própria escrita já reportou o resultado);
          // só a PRIMEIRA carga vira a tela de erro.
          if (versao === 0) setEstado("erro");
          return;
        }
        const [corpoSessao, corpoPauta] = await Promise.all([rSessao.json(), rPauta.json()]);
        if (!vivo) return;
        setSessao(camelizarChaves(corpoSessao) as SessaoOut);
        setPauta(camelizarChaves(corpoPauta) as PautaOut);
        setEstado("pronto");
      } catch {
        if (vivo && versao === 0) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, sessaoId, versao]);

  if (semCredencial(token)) {
    return { sessao: null, pauta: null, estado: "erro" as Estado, recarregar };
  }
  return { sessao, pauta, estado, recarregar };
}
