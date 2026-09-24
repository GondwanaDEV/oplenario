"use client";

// Hook de IO da CENTRAL DA CASA (docs/23 Fatia 3). Junta os sinais medidos na 3a — cada um BEST-EFFORT e
// independente: uma fonte que falha vira `null` (a tela diz o que não carregou) e nunca derruba as outras.
//   · GET /sessoes                      — a lista (useSessoes)
//   · GET /paineis/pendencias           — prazos legais de e-SIC/LGPD/ouvidoria (+ total autoritativo)
//   · GET /compliance/painel            — obrigações do TCE em aberto (+ total autoritativo)
//   · GET /moderacao/comentarios        — comentários do portal a moderar
//   · GET /paineis/tramitacao           — totais por estado (a coluna "Pronta p/ pauta")
//   · por sessão (só as que a tela mostra — `sessoesParaDetalhar`): /pauta, /folhas, /justificativas
// Em cada sinal, `undefined` = ainda carregando e `null` = falhou (o contrato de `central-vista.ts`).

import { useEffect, useMemo, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { useSessoes } from "./use-sessoes";
import { useMeuIdentidade } from "./use-meu-identidade";
import {
  derivarCentral,
  sessoesParaDetalhar,
  type ComentarioIn,
  type DetalheSessao,
  type ObrigacaoIn,
  type PrazoLegalIn,
} from "./central-vista";

async function lerOuNull<T>(url: string, token: string | null, signal: AbortSignal): Promise<T | null> {
  try {
    const r = await apiFetch(url, { token: token ?? undefined, signal, cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as T;
  } catch {
    return null;
  }
}

type Sinal<T> = T | null | undefined;

export function useCentral(token: string | null) {
  const [agoraIso] = useState(() => new Date().toISOString());
  const { sessoes, estado: estadoSessoes } = useSessoes(token);
  const { dados: identidade } = useMeuIdentidade(token);
  const [pendencias, setPendencias] = useState<Sinal<{ itens: PrazoLegalIn[]; total: number }>>(undefined);
  const [compliance, setCompliance] = useState<Sinal<{ itens: ObrigacaoIn[]; total: number }>>(undefined);
  const [moderacao, setModeracao] = useState<Sinal<ComentarioIn[]>>(undefined);
  const [tramitacao, setTramitacao] = useState<Sinal<{ estado: string; total: number }[]>>(undefined);
  const [detalhes, setDetalhes] = useState<Record<string, DetalheSessao>>({});

  useEffect(() => {
    if (semCredencial(token)) return;
    const controller = new AbortController();
    const { signal } = controller;
    const vivo = () => !signal.aborted;
    lerOuNull<{ pendencias: PrazoLegalIn[]; pendenciasTotal: number }>("/api/paineis/pendencias", token, signal).then((r) => {
      if (vivo()) setPendencias(r && { itens: r.pendencias, total: r.pendenciasTotal });
    });
    lerOuNull<{ emAberto: ObrigacaoIn[]; emAbertoTotal: number }>("/api/compliance/painel", token, signal).then((r) => {
      if (vivo()) setCompliance(r && { itens: r.emAberto, total: r.emAbertoTotal });
    });
    lerOuNull<ComentarioIn[]>("/api/moderacao/comentarios", token, signal).then((r) => {
      if (vivo()) setModeracao(Array.isArray(r) ? r : null);
    });
    lerOuNull<{ totaisPorEstado: { estado: string; total: number }[] }>("/api/paineis/tramitacao", token, signal).then((r) => {
      if (vivo()) setTramitacao(r ? r.totaisPorEstado : null);
    });
    return () => controller.abort();
  }, [token]);

  const alvos = useMemo(() => (sessoes ? sessoesParaDetalhar(sessoes, agoraIso) : []), [sessoes, agoraIso]);
  const chaveAlvos = JSON.stringify(alvos);

  useEffect(() => {
    if (semCredencial(token)) return;
    const controller = new AbortController();
    const { signal } = controller;
    const lista = JSON.parse(chaveAlvos) as typeof alvos;
    const gravar = (id: string, campo: keyof DetalheSessao, valor: number | null) => {
      if (!signal.aborted) setDetalhes((d) => ({ ...d, [id]: { ...d[id], [campo]: valor } }));
    };
    for (const a of lista) {
      const base = `/api/sessoes/${encodeURIComponent(a.sessaoId)}`;
      if (a.pauta) {
        lerOuNull<{ itens: unknown[] }>(`${base}/pauta`, token, signal).then((r) =>
          gravar(a.sessaoId, "itensPauta", r && Array.isArray(r.itens) ? r.itens.length : null));
      }
      if (a.folhas) {
        lerOuNull<{ folhas: unknown[] }>(`${base}/folhas`, token, signal).then((r) =>
          gravar(a.sessaoId, "folhas", r && Array.isArray(r.folhas) ? r.folhas.length : null));
      }
      if (a.justificativas) {
        lerOuNull<{ justificativas: { estado: string }[] }>(`${base}/justificativas`, token, signal).then((r) =>
          gravar(a.sessaoId, "justificativasPendentes",
            r && Array.isArray(r.justificativas) ? r.justificativas.filter((j) => j.estado === "pendente").length : null));
      }
    }
    return () => controller.abort();
  }, [token, chaveAlvos]);

  const semToken = semCredencial(token);
  return derivarCentral({
    agoraIso,
    nome: identidade?.nome ?? null,
    sessoes,
    estadoSessoes,
    detalhes,
    pendencias: semToken ? null : pendencias,
    compliance: semToken ? null : compliance,
    moderacao: semToken ? null : moderacao,
    tramitacao: semToken ? null : tramitacao,
  });
}
