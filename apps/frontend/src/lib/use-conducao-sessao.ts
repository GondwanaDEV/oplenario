"use client";

// Hook de IO do "Comando da Mesa" (§22.6 eixo C) — lê o `SessaoOut` de `GET /sessoes/:id` (estado +
// lock-version) e expõe a ÚNICA escrita da tela: `transicionar(para, motivo?)` → `POST /sessoes/:id/transicao`.
// Toda a lógica de QUAIS transições são possíveis vive em `conducao-sessao-vista.ts` (módulo puro); este hook
// só faz IO e nunca reimplementa o grafo.
//
// CONCORRÊNCIA (o motivo de o lock-version existir): a Mesa carrega a tela, alguém mais (outra aba, a
// secretaria de outra máquina) transiciona a sessão, e o clique daqui chega com a versão velha. O backend
// recusa com 409 (`:conflito/transicao` — tanto lock desatualizado quanto transição ilegal). O hook NÃO adivinha a
// causa: repassa o 409 como "recarregue e confira o estado atual", e re-busca o `SessaoOut` para a tela já
// mostrar o estado real. O `para` é o alvo derivado pela vista (imutável); o `lock-version` sai SEMPRE do
// último `SessaoOut` carregado, nunca de um valor guardado à parte.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { SessaoOut } from "./contrato-sessoes.gen";
import type { SessaoEstado } from "./conducao-sessao-vista";

export type EstadoDados = "carregando" | "pronto" | "erro";

export type ResultadoTransicao =
  | { ok: true; sessao: SessaoOut }
  | { ok: false; erro: string; conflito: boolean };

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

const MSG_CONFLITO =
  "O estado da sessão mudou enquanto esta tela estava aberta — a versão que você tinha ficou desatualizada. " +
  "A tela já recarregou o estado atual; confira antes de tentar de novo.";

async function buscarSessao(sessaoId: string, token: string | null, signal?: AbortSignal): Promise<SessaoOut> {
  const r = await apiFetch(`/api/sessoes/${sessaoId}`, { token: token ?? undefined, signal, cache: "no-store" });
  if (!r.ok) throw new Error(`sessao ${r.status}`);
  return camelizarChaves(await r.json()) as SessaoOut;
}

export function useConducaoSessao(sessaoId: string, token: string | null) {
  const [sessao, setSessao] = useState<SessaoOut | null>(null);
  const [estado, setEstado] = useState<EstadoDados>("carregando");
  const [erro, setErro] = useState<string | null>(null);
  const idValido = ID_VALIDO.test(sessaoId);

  const sessaoRef = useRef<SessaoOut | null>(null);
  useEffect(() => {
    sessaoRef.current = sessao;
  }, [sessao]);

  const vivoRef = useRef(true);
  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  /** Recarga explícita (pós-transição, ou após um 409). Nunca zera um snapshot bom em falha de rede. */
  const recarregar = useCallback(async () => {
    if (semCredencial(token) || !idValido) return;
    try {
      const s = await buscarSessao(sessaoId, token);
      if (vivoRef.current) {
        setSessao(s);
        setEstado("pronto");
      }
    } catch {
      // mantém o último snapshot — recarregar explícito não apaga a tela
    }
  }, [sessaoId, token, idValido]);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return;
    const controller = new AbortController();
    let vivo = true;
    (async () => {
      try {
        const s = await buscarSessao(sessaoId, token, controller.signal);
        if (!vivo) return;
        setSessao(s);
        setEstado("pronto");
      } catch {
        if (!vivo || controller.signal.aborted) return;
        setEstado("erro");
        setErro("Não foi possível carregar a sessão.");
      }
    })();
    return () => {
      vivo = false;
      controller.abort();
    };
  }, [sessaoId, token, idValido]);

  /** POST /sessoes/:id/transicao. `para` vem da vista (alvo válido do grafo); `lock-version` sai do último
   * SessaoOut. 409 = conflito de concorrência OU transição ilegal — repassa a mesma mensagem acionável e
   * re-busca o estado. Sucesso → adota o SessaoOut devolvido (ou re-busca) e devolve-o ao chamador. */
  const transicionar = useCallback(
    async (para: SessaoEstado, motivo?: string): Promise<ResultadoTransicao> => {
      if (semCredencial(token)) return { ok: false, conflito: false, erro: "sem token de autenticacao" };
      const atual = sessaoRef.current;
      if (!atual) return { ok: false, conflito: false, erro: "sessão ainda não foi carregada" };

      const corpo: Record<string, unknown> = { para, "lock-version": atual.lockVersion };
      const motivoAparado = motivo?.trim();
      if (motivoAparado) corpo.motivo = motivoAparado;

      let r: Response;
      try {
        r = await apiFetch(`/api/sessoes/${sessaoId}/transicao`, {
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
          return { ok: false, conflito: true, erro: MSG_CONFLITO };
        }
        const corpoErro = await r.json().catch(() => null);
        return { ok: false, conflito: false, erro: corpoErro?.erro ?? `falha ao transicionar (status ${r.status})` };
      }

      // A rota devolve TransicaoSessaoOut ({sessao-id, de, para}) — não o SessaoOut inteiro. Re-buscamos o
      // SessaoOut para atualizar estado + lock-version na tela num único caminho de verdade (o do servidor).
      await recarregar();
      const nova = sessaoRef.current ?? { ...atual, estado: para, lockVersion: atual.lockVersion + 1 };
      return { ok: true, sessao: nova };
    },
    [sessaoId, token, recarregar],
  );

  if (semCredencial(token)) {
    return {
      sessao: null, estado: "erro" as EstadoDados, erro: "Sem credencial de sessão (token).",
      recarregar, transicionar,
    };
  }
  if (!idValido) {
    return {
      sessao: null, estado: "erro" as EstadoDados, erro: "Identificador de sessão inválido.",
      recarregar, transicionar,
    };
  }
  return { sessao, estado, erro, recarregar, transicionar };
}
