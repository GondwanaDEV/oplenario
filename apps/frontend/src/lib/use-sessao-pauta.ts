"use client";

// Hook de detalhe da sessão-alvo (Onda C Slice C2, pauta-convocacao) — dado um sessaoId, busca em paralelo
// GET /api/sessoes/:id (SessaoOut) e GET /api/sessoes/:id/pauta (PautaOut). Mesmo padrão de
// use-mesa.ts/use-tramitacao-board.ts: fetch autenticado, camelizarChaves do boundary, estados
// carregando/pronto/erro, cleanup por `vivo`. `sessaoId` nulo (nenhuma sessão agendada, ou ainda
// carregando a lista de sessões) -> estado "pronto" com sessao/pauta null: não é erro, é a ausência
// honesta de sessão-alvo, tratada pela página.

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";

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
  textoDescricao?: string;
  ordem: number;
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

  // Reset ao trocar de sessaoId (mesmo padrão de use-proposicoes.ts): reset DURANTE O RENDER, não dentro do
  // useEffect — eslint-plugin-react-hooks v7 (set-state-in-effect) exige isso em vez de um setState
  // síncrono no topo do efeito.
  if (sessaoId !== sessaoIdAnterior) {
    setSessaoIdAnterior(sessaoId);
    setEstado(sessaoId ? "carregando" : "pronto");
    if (!sessaoId) {
      setSessao(null);
      setPauta(null);
    }
  }

  useEffect(() => {
    if (!token || !sessaoId) return;
    let vivo = true;
    (async () => {
      try {
        const [rSessao, rPauta] = await Promise.all([
          fetch(`/api/sessoes/${encodeURIComponent(sessaoId)}`, {
            headers: { Authorization: `Bearer ${token}` },
            cache: "no-store",
          }),
          fetch(`/api/sessoes/${encodeURIComponent(sessaoId)}/pauta`, {
            headers: { Authorization: `Bearer ${token}` },
            cache: "no-store",
          }),
        ]);
        if (!vivo) return;
        if (!rSessao.ok || !rPauta.ok) {
          setEstado("erro");
          return;
        }
        const [corpoSessao, corpoPauta] = await Promise.all([rSessao.json(), rPauta.json()]);
        if (!vivo) return;
        setSessao(camelizarChaves(corpoSessao) as SessaoOut);
        setPauta(camelizarChaves(corpoPauta) as PautaOut);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, sessaoId]);

  if (!token) {
    return { sessao: null, pauta: null, estado: "erro" as Estado };
  }
  return { sessao, pauta, estado };
}
