"use client";

// Hook de IO dos ATOS DA MESA no cockpit (docs/23 Fatia 2). Lê os atos já registrados
// (GET /sessoes/:id/atos-mesa) e a composição da sessão (GET /sessoes/:id/composicao — nomes e o Presidente da
// Mesa para pré-selecionar "quem presidiu"), e escreve os dois atos: DECISÃO sobre questão de ordem
// (POST /decisoes-mesa) e INCIDENTE processual (POST /incidentes). As duas escritas são append-only: corrigir =
// registrar de novo. Depois de cada escrita, recarrega a lista. Derivação em `atos-mesa-vista.ts`.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { AtosMesaOut, ComposicaoSessaoOut, TribunaOut } from "./contrato-sessoes.gen";
import type { ResultadoIncidente, TipoIncidente } from "./atos-mesa-vista";

export interface NovaDecisao {
  questao: string;
  decisao: string;
  presidenteId: string;
  fundamentacao?: string;
  falaId?: string | null;
}

export interface NovoIncidente {
  tipo: TipoIncidente;
  resultado: ResultadoIncidente;
  descricao: string;
  requerenteId?: string | null;
  /** Matéria atingida (proposição da pauta), quando houver. */
  proposicaoId?: string | null;
  deliberacao?: string;
}

export type ResultadoAto = { ok: true } | { ok: false; erro: string };
export type EstadoAtos = "carregando" | "pronto" | "erro";

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

async function lerJson<T>(caminho: string, token: string | null, signal?: AbortSignal): Promise<T> {
  const r = await apiFetch(caminho, { token: token ?? undefined, signal, cache: "no-store" });
  if (!r.ok) throw new Error(`${caminho} ${r.status}`);
  return camelizarChaves(await r.json()) as T;
}

export function useAtosMesa(sessaoId: string, token: string | null) {
  const [atos, setAtos] = useState<AtosMesaOut | null>(null);
  const [composicao, setComposicao] = useState<ComposicaoSessaoOut | null>(null);
  const [estado, setEstado] = useState<EstadoAtos>("carregando");
  const [enviando, setEnviando] = useState(false);
  const enviandoRef = useRef(false);
  const vivoRef = useRef(true);
  const idValido = ID_VALIDO.test(sessaoId);
  const base = `/api/sessoes/${sessaoId}`;

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return;
    const controller = new AbortController();
    let vivo = true;
    (async () => {
      try {
        const a = await lerJson<AtosMesaOut>(`${base}/atos-mesa`, token, controller.signal);
        if (vivo) {
          setAtos(a);
          setEstado("pronto");
        }
      } catch {
        if (vivo && !controller.signal.aborted) setEstado("erro");
      }
      try {
        const c = await lerJson<ComposicaoSessaoOut>(`${base}/composicao`, token, controller.signal);
        if (vivo) setComposicao(c);
      } catch {
        /* composição é secundária: os nomes degradam para o rótulo neutro */
      }
    })();
    return () => {
      vivo = false;
      controller.abort();
    };
  }, [base, token, idValido]);

  const recarregar = useCallback(async () => {
    if (semCredencial(token) || !idValido) return;
    try {
      const a = await lerJson<AtosMesaOut>(`${base}/atos-mesa`, token);
      if (vivoRef.current) {
        setAtos(a);
        setEstado("pronto");
      }
    } catch {
      // recarga explícita não derruba a lista que já está na tela
    }
  }, [base, token, idValido]);

  /** A fala em curso agora (para oferecer "vincular à fala"), lida na hora de abrir o formulário. */
  const buscarFalaAtual = useCallback(async (): Promise<{ falaId: string; oradorId: string } | null> => {
    if (semCredencial(token) || !idValido) return null;
    try {
      const t = await lerJson<TribunaOut>(`${base}/tribuna`, token);
      return t.oradorAtual ? { falaId: t.oradorAtual.falaId, oradorId: t.oradorAtual.oradorId } : null;
    } catch {
      return null;
    }
  }, [base, token, idValido]);

  const escrever = useCallback(
    async (caminho: string, corpo: Record<string, unknown>, acao: string): Promise<ResultadoAto> => {
      if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
      if (enviandoRef.current) return { ok: false, erro: "Aguarde o registro anterior terminar." };
      enviandoRef.current = true;
      setEnviando(true);
      try {
        const r = await apiFetch(caminho, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(corpo),
        });
        if (!r.ok) {
          const e = await r.json().catch(() => null);
          return { ok: false, erro: e?.erro ?? `falha ao registrar ${acao} (status ${r.status})` };
        }
        await recarregar();
        return { ok: true };
      } catch {
        return { ok: false, erro: "Falha de rede — tente novamente." };
      } finally {
        enviandoRef.current = false;
        if (vivoRef.current) setEnviando(false);
      }
    },
    [token, recarregar],
  );

  const registrarDecisao = useCallback(
    (d: NovaDecisao) => {
      const fund = d.fundamentacao?.trim();
      return escrever(
        `${base}/decisoes-mesa`,
        {
          questao: d.questao.trim(),
          decisao: d.decisao.trim(),
          "presidente-id": d.presidenteId,
          "decidido-em": new Date().toISOString(),
          ...(fund ? { fundamentacao: fund } : {}),
          ...(d.falaId ? { "fala-id": d.falaId } : {}),
        },
        "a decisão",
      );
    },
    [base, escrever],
  );

  const registrarIncidente = useCallback(
    (i: NovoIncidente) => {
      const delib = i.deliberacao?.trim();
      return escrever(
        `${base}/incidentes`,
        {
          tipo: i.tipo,
          resultado: i.resultado,
          descricao: i.descricao.trim(),
          "ocorrido-em": new Date().toISOString(),
          ...(i.requerenteId ? { "requerente-id": i.requerenteId } : {}),
          ...(i.proposicaoId ? { "objeto-tipo": "proposicao", "objeto-id": i.proposicaoId } : {}),
          ...(delib ? { deliberacao: delib } : {}),
        },
        "o incidente",
      );
    },
    [base, escrever],
  );

  return {
    atos,
    membros: composicao?.membros ?? [],
    estado: semCredencial(token) || !idValido ? ("erro" as EstadoAtos) : estado,
    enviando,
    recarregar,
    buscarFalaAtual,
    registrarDecisao,
    registrarIncidente,
  };
}
