"use client";

// Hook de leitura — GET /api/meu/sessao-atual (Onda C3): qual sessão o cockpit do vereador deve abrir.
// Mirror simplificado de use-sli-sessoes.ts (mesmo racional de fetch+estado), mas escopo "vereador" (a
// rota do secretário /paineis/sli/sessoes não aceita este papel).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";

export type MinhaSessaoAtualOut = { sessaoId: string | null; situacao: string | null };

type Estado = "carregando" | "pronto" | "erro";

export function useMinhaSessaoAtual(token: string | null) {
  const [dados, setDados] = useState<MinhaSessaoAtualOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (!token) return;
    let vivo = true;
    (async () => {
      try {
        const r = await fetch("/api/meu/sessao-atual", {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const resultado = camelizarChaves(await r.json()) as MinhaSessaoAtualOut;
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

  if (!token) return { sessaoId: null, situacao: null, estado: "erro" as Estado };
  return { sessaoId: dados?.sessaoId ?? null, situacao: dados?.situacao ?? null, estado };
}
