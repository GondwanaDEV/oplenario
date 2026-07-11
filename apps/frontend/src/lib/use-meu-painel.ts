"use client";

// Hook do painel do vereador (Onda C1) — GET /api/meu/painel. Sem parâmetro `id` (é sempre "o meu",
// resolvido do ator na borda — anti-forja por construção); mirror simplificado de use-documento-modelos.ts
// + `recarregar` (mesmo padrão de use-documento-detalhe.ts) — usado depois de "Dar ciência" pra revalidar
// contra o servidor (otimista no componente, mas a fonte de verdade final é o próximo GET real).

import { useCallback, useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { MeuPainelOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

async function buscarPainel(token: string): Promise<MeuPainelOut | null> {
  const r = await fetch("/api/meu/painel", {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as MeuPainelOut;
}

export function useMeuPainel(token: string | null) {
  const [dados, setDados] = useState<MeuPainelOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const vivoRef = useRef(true);

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
    try {
      const resultado = await buscarPainel(token);
      if (!vivoRef.current) return;
      if (resultado === null) {
        setEstado("erro");
        return;
      }
      setDados(resultado);
      setEstado("pronto");
    } catch {
      if (vivoRef.current) setEstado("erro");
    }
  }, [token]);

  if (!token) return { dados: null, estado: "erro" as Estado, recarregar };
  return { dados, estado, recarregar };
}
