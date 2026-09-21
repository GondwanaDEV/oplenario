"use client";

// Hook de IO da tribuna conduzida pela Mesa (§22.6 eixo F) — camada de INTENÇÃO. Lê a tribuna
// (GET /sessoes/:id/tribuna) e a composição da Casa (GET /sessoes/:id/composicao, para o seletor de orador
// + resolução de nome) e escreve as duas ações da Mesa: INSCREVER (POST /sessoes/:id/inscricoes) e
// DESISTÊNCIA (POST /sessoes/:id/inscricoes/:insc-id/desistir, CAS por lock-version). Tipos GERADOS
// (contrato-sessoes.gen.ts) — sem hand-model. Derivação de fila/nomes em `tribuna-mesa-vista.ts`.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type {
  ComposicaoSessaoOut,
  InscricaoReciboOut,
  TribunaOut,
} from "./contrato-sessoes.gen";

export type OrigemInscricao =
  | "pre_sessao_app"
  | "pre_sessao_secretaria"
  | "intra_sessao_pedido"
  | "automatica_por_autoria";

export type EstadoDados = "carregando" | "pronto" | "erro";

export type ResultadoInscrever = { ok: true; recibo: InscricaoReciboOut } | { ok: false; erro: string };
export type ResultadoDesistir =
  | { ok: true }
  | { ok: false; erro: string; conflito: boolean };

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

const MSG_CONFLITO_DESISTIR =
  "Esta inscrição mudou de estado enquanto a tela estava aberta (outra pessoa a alterou). A tela recarregou " +
  "a fila; confira antes de tentar de novo.";

async function buscarTribuna(sessaoId: string, token: string | null, signal?: AbortSignal): Promise<TribunaOut> {
  const r = await apiFetch(`/api/sessoes/${sessaoId}/tribuna`, { token: token ?? undefined, signal, cache: "no-store" });
  if (!r.ok) throw new Error(`tribuna ${r.status}`);
  return camelizarChaves(await r.json()) as TribunaOut;
}

async function buscarComposicao(
  sessaoId: string,
  token: string | null,
  signal?: AbortSignal,
): Promise<ComposicaoSessaoOut> {
  const r = await apiFetch(`/api/sessoes/${sessaoId}/composicao`, { token: token ?? undefined, signal, cache: "no-store" });
  if (!r.ok) throw new Error(`composicao ${r.status}`);
  return camelizarChaves(await r.json()) as ComposicaoSessaoOut;
}

export function useTribunaMesa(sessaoId: string, token: string | null) {
  const [tribuna, setTribuna] = useState<TribunaOut | null>(null);
  const [composicao, setComposicao] = useState<ComposicaoSessaoOut | null>(null);
  const [estado, setEstado] = useState<EstadoDados>("carregando");
  const [erro, setErro] = useState<string | null>(null);
  const idValido = ID_VALIDO.test(sessaoId);

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
      const t = await buscarTribuna(sessaoId, token);
      if (vivoRef.current) {
        setTribuna(t);
        setEstado("pronto");
      }
    } catch {
      // recarregar explícito não zera a tela
    }
    try {
      const c = await buscarComposicao(sessaoId, token);
      if (vivoRef.current) setComposicao(c);
    } catch {
      /* composição é secundária: seletor de orador degrada */
    }
  }, [sessaoId, token, idValido]);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return;
    const controller = new AbortController();
    let vivo = true;
    (async () => {
      try {
        const t = await buscarTribuna(sessaoId, token, controller.signal);
        if (!vivo) return;
        setTribuna(t);
        setEstado("pronto");
      } catch {
        if (!vivo || controller.signal.aborted) return;
        setEstado("erro");
        setErro("Não foi possível carregar a tribuna.");
      }
      try {
        const c = await buscarComposicao(sessaoId, token, controller.signal);
        if (vivo) setComposicao(c);
      } catch {
        /* seletor de orador degrada para vazio */
      }
    })();
    return () => {
      vivo = false;
      controller.abort();
    };
  }, [sessaoId, token, idValido]);

  /** POST /inscricoes. `origem` default `intra_sessao_pedido` (pedido durante a sessão — o caminho da Mesa
   * ao vivo). `origem-inscricao` é descritivo (não força precedência), validado contra o enum. */
  const inscrever = useCallback(
    async (
      vereadorId: string,
      fase: string,
      origem: OrigemInscricao = "intra_sessao_pedido",
    ): Promise<ResultadoInscrever> => {
      if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
      if (!vereadorId) return { ok: false, erro: "Escolha o orador a inscrever." };
      if (!fase) return { ok: false, erro: "Escolha a fase da inscrição." };
      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/inscricoes`, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ "vereador-id": vereadorId, "origem-inscricao": origem, fase }),
        });
      } catch {
        return { ok: false, erro: "Falha de rede — tente novamente." };
      }
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        return { ok: false, erro: corpoErro?.erro ?? `falha ao inscrever (status ${r.status})` };
      }
      const recibo = camelizarChaves(await r.json()) as InscricaoReciboOut;
      await recarregar();
      return { ok: true, recibo };
    },
    [sessaoId, token, recarregar],
  );

  /** POST /inscricoes/:insc-id/desistir com CAS por lock-version (vindo do InscritoTribunaOut da fila). */
  const desistir = useCallback(
    async (inscricaoId: string, lockVersion: number): Promise<ResultadoDesistir> => {
      if (semCredencial(token)) return { ok: false, conflito: false, erro: "sem token de autenticacao" };
      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/inscricoes/${encodeURIComponent(inscricaoId)}/desistir`, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ "lock-version": lockVersion }),
        });
      } catch {
        return { ok: false, conflito: false, erro: "Falha de rede — tente novamente." };
      }
      if (!r.ok) {
        if (r.status === 409) {
          await recarregar();
          return { ok: false, conflito: true, erro: MSG_CONFLITO_DESISTIR };
        }
        const corpoErro = await r.json().catch(() => null);
        return { ok: false, conflito: false, erro: corpoErro?.erro ?? `falha ao registrar desistência (status ${r.status})` };
      }
      await recarregar();
      return { ok: true };
    },
    [sessaoId, token, recarregar],
  );

  if (semCredencial(token)) {
    return {
      tribuna: null, composicao: null, estado: "erro" as EstadoDados,
      erro: "Sem credencial de sessão (token).", recarregar, inscrever, desistir,
    };
  }
  if (!idValido) {
    return {
      tribuna: null, composicao: null, estado: "erro" as EstadoDados,
      erro: "Identificador de sessão inválido.", recarregar, inscrever, desistir,
    };
  }
  return { tribuna, composicao, estado, erro, recarregar, inscrever, desistir };
}
