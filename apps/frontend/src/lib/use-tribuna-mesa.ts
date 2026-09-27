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
  FalaEncerradaOut,
  FalaReciboOut,
  InscricaoReciboOut,
  TribunaOut,
} from "./contrato-sessoes.gen";

export type OrigemInscricao =
  | "pre_sessao_app"
  | "pre_sessao_secretaria"
  | "intra_sessao_pedido"
  | "automatica_por_autoria";

/** Eventos MANUAIS de cronômetro que a Mesa registra (espelha logic/tipos-evento-cronometro-manual;
 * iniciada/encerrada são do ciclo da fala, não entram aqui). */
export type EventoCronometroManual =
  | "pausada"
  | "retomada"
  | "aparte_concedido"
  | "tempo_adicional_concedido";

export type EstadoDados = "carregando" | "pronto" | "erro";

export type ResultadoInscrever = { ok: true; recibo: InscricaoReciboOut } | { ok: false; erro: string };
export type ResultadoDesistir =
  | { ok: true }
  | { ok: false; erro: string; conflito: boolean };
export type ResultadoIniciarFala = { ok: true; recibo: FalaReciboOut } | { ok: false; erro: string };
export type ResultadoEventoCronometro = { ok: true } | { ok: false; erro: string };
export type ResultadoEncerrarFala =
  | { ok: true; fala: FalaEncerradaOut }
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

  // ---------- camada de EXECUÇÃO (§22.6 eixo F/G): chamar à tribuna, cronômetro, encerrar a fala ----------

  /** POST /falas — CHAMA um orador à tribuna (inicia a fala). `iniciou-em` é o instante de domínio (agora,
   * ISO). Liga a fala à inscrição que ela cumpre (`inscricao-id`). `tipo-fala` default "principal". */
  const iniciarFala = useCallback(
    async (
      oradorId: string,
      fase: string,
      opcoes?: { tipoFala?: string; inscricaoId?: string | null; tempoConcedidoSegundos?: number | null },
    ): Promise<ResultadoIniciarFala> => {
      if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
      if (!oradorId) return { ok: false, erro: "Sem orador para chamar à tribuna." };
      const corpo: Record<string, unknown> = {
        "orador-id": oradorId,
        "tipo-fala": opcoes?.tipoFala ?? "principal",
        fase,
        "iniciou-em": new Date().toISOString(),
      };
      if (opcoes?.inscricaoId) corpo["inscricao-id"] = opcoes.inscricaoId;
      // mig 0081: ausente = o servidor resolve o tempo regimental da Casa (ou sem limite)
      if (typeof opcoes?.tempoConcedidoSegundos === "number" && opcoes.tempoConcedidoSegundos > 0) {
        corpo["tempo-concedido-segundos"] = Math.round(opcoes.tempoConcedidoSegundos);
      }
      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/falas`, {
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
        return { ok: false, erro: corpoErro?.erro ?? `falha ao iniciar a fala (status ${r.status})` };
      }
      const recibo = camelizarChaves(await r.json()) as FalaReciboOut;
      await recarregar();
      return { ok: true, recibo };
    },
    [sessaoId, token, recarregar],
  );

  /** POST /falas/:id/cronometro — registra um evento MANUAL (pausada/retomada/aparte_concedido/
   * tempo_adicional_concedido). `segundos-adicionais` só para tempo_adicional (>0); os demais o proíbem
   * (o adapter valida a coerência, 400 fail-closed). `ocorrido-em` = agora. */
  const registrarEventoCronometro = useCallback(
    async (
      falaId: string,
      tipo: EventoCronometroManual,
      segundosAdicionais?: number,
    ): Promise<ResultadoEventoCronometro> => {
      if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
      const corpo: Record<string, unknown> = { tipo, "ocorrido-em": new Date().toISOString() };
      if (tipo === "tempo_adicional_concedido" && typeof segundosAdicionais === "number") {
        corpo["segundos-adicionais"] = segundosAdicionais;
      }
      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/falas/${encodeURIComponent(falaId)}/cronometro`, {
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
        return { ok: false, erro: corpoErro?.erro ?? `falha ao registrar o evento (status ${r.status})` };
      }
      await recarregar();
      return { ok: true };
    },
    [sessaoId, token, recarregar],
  );

  /** POST /falas/:id/encerrar — encerra a fala. `encerrou-em` = agora; o motor computa o tempo efetivo.
   * CAS por `lock-version` (do OradorAtualOut). 409 = a fala mudou de estado (já encerrada). */
  const encerrarFala = useCallback(
    async (falaId: string, lockVersion: number): Promise<ResultadoEncerrarFala> => {
      if (semCredencial(token)) return { ok: false, conflito: false, erro: "sem token de autenticacao" };
      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/falas/${encodeURIComponent(falaId)}/encerrar`, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ "encerrou-em": new Date().toISOString(), "lock-version": lockVersion }),
        });
      } catch {
        return { ok: false, conflito: false, erro: "Falha de rede — tente novamente." };
      }
      if (!r.ok) {
        if (r.status === 409) {
          await recarregar();
          return {
            ok: false,
            conflito: true,
            erro: "A fala mudou de estado enquanto a tela estava aberta (já foi encerrada?). A tela recarregou a tribuna.",
          };
        }
        const corpoErro = await r.json().catch(() => null);
        return { ok: false, conflito: false, erro: corpoErro?.erro ?? `falha ao encerrar a fala (status ${r.status})` };
      }
      const fala = camelizarChaves(await r.json()) as FalaEncerradaOut;
      await recarregar();
      return { ok: true, fala };
    },
    [sessaoId, token, recarregar],
  );

  const escritas = { inscrever, desistir, iniciarFala, registrarEventoCronometro, encerrarFala };

  if (semCredencial(token)) {
    return {
      tribuna: null, composicao: null, estado: "erro" as EstadoDados,
      erro: "Sem credencial de sessão (token).", recarregar, ...escritas,
    };
  }
  if (!idValido) {
    return {
      tribuna: null, composicao: null, estado: "erro" as EstadoDados,
      erro: "Identificador de sessão inválido.", recarregar, ...escritas,
    };
  }
  return { tribuna, composicao, estado, erro, recarregar, ...escritas };
}
