"use client";

// Hook do painel do vereador (Onda C1) — GET /api/meu/painel. Sem parâmetro `id` (é sempre "o meu",
// resolvido do ator na borda — anti-forja por construção); mirror simplificado de use-documento-modelos.ts
// + `recarregar` (mesmo padrão de use-documento-detalhe.ts) — usado depois de "Dar ciência" pra revalidar
// contra o servidor. PESSIMISTA de propósito nesta fatia (review MEDIUM react — corrigido de um comentário
// anterior que prometia "otimista" sem implementar: `darCiencia`, no page.tsx, faz `await acusar(...)` e só
// então `await recarregar()` — dois round-trips antes de qualquer atualização visual; nenhuma mutação local
// acontece antes da resposta do servidor). Uma UI otimista (marcar o card como "ciente" na hora + rollback
// em erro) é melhoria futura, não o comportamento atual.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { MeuPainelOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

async function buscarPainel(token: string): Promise<MeuPainelOut | null> {
  const r = await apiFetch("/api/meu/painel", { token, cache: "no-store" });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as MeuPainelOut;
}

export function useMeuPainel(token: string | null) {
  const [dados, setDados] = useState<MeuPainelOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const vivoRef = useRef(true);
  // Review react LOW: sempre o `token` do render mais recente (mesmo racional de `idAtualRef` em
  // use-documento-detalhe.ts) — sem isso, um `recarregar()` em voo de um token ANTERIOR poderia sobrescrever
  // dados já buscados com o token NOVO, se o token mudar no meio do caminho (troca de sessão/dev-token).
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
    if (!token) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarPainel(token);
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
    if (!token) return;
    const tokenDaChamada = token;
    try {
      const resultado = await buscarPainel(token);
      if (!vivoRef.current) return;
      if (tokenAtualRef.current !== tokenDaChamada) return; // token mudou enquanto o fetch estava em voo
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

  if (!token) return { dados: null, estado: "erro" as Estado, recarregar };
  return { dados, estado, recarregar };
}
