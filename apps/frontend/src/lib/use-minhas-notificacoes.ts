"use client";

// Hook da inbox (Onda E fatia 1) — GET /api/meu/notificacoes. Sem parâmetro de identidade (é sempre "as
// minhas", resolvidas do ator na borda — anti-forja por construção); mirror direto de use-meu-painel.ts,
// incluindo `recarregar` (revalidação contra o servidor depois de marcar como lida) e o `tokenAtualRef`
// que impede uma resposta em voo de um token ANTERIOR de sobrescrever dados do token NOVO.
//
// PESSIMISTA de propósito nesta fatia: a página faz `await marcar(id)` e só então `await recarregar()`.
// UI otimista (riscar o item na hora + rollback em erro) é melhoria futura, não o comportamento atual.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { MinhasNotificacoesOut } from "./contrato-paineis.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

async function buscar(token: string | null): Promise<MinhasNotificacoesOut | null> {
  const r = await apiFetch("/api/meu/notificacoes", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as MinhasNotificacoesOut;
}

export function useMinhasNotificacoes(token: string | null) {
  const [dados, setDados] = useState<MinhasNotificacoesOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const vivoRef = useRef(true);
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
    if (semCredencial(token)) return; // o caso sem token é derivado no retorno (sem setState no effect)
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscar(token);
        if (!vivo) return;
        if (resultado === null) {
          setEstado("erro");
          return;
        }
        setDados(resultado);
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
      const resultado = await buscar(token);
      if (!vivoRef.current) return;
      if (tokenAtualRef.current !== tokenDaChamada) return; // o token mudou com o fetch em voo
      if (resultado === null) {
        setEstado("erro");
        return;
      }
      setDados(resultado);
      setEstado("pronto");
    } catch {
      if (vivoRef.current && tokenAtualRef.current === tokenDaChamada) setEstado("erro");
    }
  }, [token]);

  if (semCredencial(token)) return { dados: null, estado: "erro" as Estado, recarregar };
  return { dados, estado, recarregar };
}
