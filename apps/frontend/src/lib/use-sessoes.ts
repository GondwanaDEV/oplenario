"use client";

// Hook do defeito #16 (MATA): GET /api/sessoes — a listagem geral das sessões do ente. Antes desta rota
// existir, a home do vereador SEMPRE chamava `derivarHome` com `sessoes=[]` e não tinha como distinguir
// "ainda não sei" de "não há" — daí a home afirmar "SEM SESSÃO AGORA" / "Nenhuma sessão agendada" no mesmo
// segundo em que /paineis/mesa mostrava, corretamente, uma sessão aberta e uma agendada. Este hook devolve
// o terceiro estado que faltava: `estado` ("carregando" | "pronto" | "erro") ao lado dos dados, para o
// chamador poder escolher o texto honesto em vez de tratar `[]`/`null` como sinônimo de "nenhuma".
//
// Mirror do padrão de use-folha.ts/use-meu-painel.ts (fetch + estado + recarregar). SEM `id` no path (é a
// listagem geral, não uma sessão específica) — authz fina por LINHA já roda no controller (o que o ator
// não pode ver simplesmente não vem; nada a refiltrar aqui). Teto de 500 linhas do backend falha fechado
// (422), que aqui vira `estado: "erro"` como qualquer outro `!r.ok` — nunca lista vazia.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { SessaoOut, SessoesOut } from "./contrato-sessoes.gen";
import { semCredencial } from "./modo";

export type EstadoSessoes = "carregando" | "pronto" | "erro";

async function buscarSessoes(token: string | null): Promise<SessaoOut[] | null> {
  const r = await apiFetch("/api/sessoes", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  const j = camelizarChaves(await r.json()) as SessoesOut;
  return j.sessoes;
}

export function useSessoes(token: string | null) {
  const [sessoes, setSessoes] = useState<SessaoOut[] | null>(null);
  const [estado, setEstado] = useState<EstadoSessoes>("carregando");
  const vivoRef = useRef(true);
  // Mesmo racional de use-meu-painel.ts: um `recarregar()` em voo de um token ANTERIOR não pode
  // sobrescrever dados já buscados com o token NOVO se o token mudar no meio do caminho.
  const tokenAtualRef = useRef(token);
  useEffect(() => {
    tokenAtualRef.current = token;
  }, [token]);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (semCredencial(token)) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarSessoes(token);
        if (!vivo) return;
        if (resultado === null) {
          setEstado("erro");
          return;
        }
        setSessoes(resultado);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  const recarregar = useCallback(async () => {
    if (semCredencial(token)) return;
    const tokenDaChamada = token;
    try {
      const resultado = await buscarSessoes(token);
      if (!vivoRef.current) return;
      if (tokenAtualRef.current !== tokenDaChamada) return; // token mudou enquanto o fetch estava em voo
      if (resultado === null) {
        setEstado("erro");
        return;
      }
      setSessoes(resultado);
      setEstado("pronto");
    } catch {
      if (vivoRef.current && tokenAtualRef.current === tokenDaChamada) setEstado("erro");
    }
  }, [token]);

  if (semCredencial(token)) return { sessoes: null, estado: "erro" as EstadoSessoes, recarregar };
  return { sessoes, estado, recarregar };
}
