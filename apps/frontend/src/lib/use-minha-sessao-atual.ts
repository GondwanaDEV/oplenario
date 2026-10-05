"use client";

// Hook de leitura — GET /api/meu/sessao-atual (Onda C3): qual sessão o cockpit do vereador deve abrir.
// Mirror simplificado de use-sli-sessoes.ts (mesmo racional de fetch+estado), mas escopo "vereador" (a
// rota do secretário /paineis/sli/sessoes não aceita este papel).

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type SessaoViva = { sessaoId: string; situacao: string; abertaEm: string | null };

// `sessaoId` = a que o cockpit abre por padrão (a aberta mais recente); `sessoesVivas` = todas as em curso, a
// padrão primeiro — com duas sessões ao mesmo tempo o vereador escolhe a outra (docs/16, retriagem linha 12).
export type MinhaSessaoAtualOut = { sessaoId: string | null; situacao: string | null; sessoesVivas?: SessaoViva[] };

type Estado = "carregando" | "pronto" | "erro";

// `escolhida`: a sessão pedida na URL (`/votar?sessao=…`). Só vale se estiver entre as vivas — um link velho
// para uma sessão já encerrada cai na padrão, nunca abre o cockpit de uma sessão que não está em curso.
export function useMinhaSessaoAtual(token: string | null, escolhida: string | null = null) {
  const [dados, setDados] = useState<MinhaSessaoAtualOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/meu/sessao-atual", { token: token ?? undefined, cache: "no-store" });
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

  if (semCredencial(token)) return { sessaoId: null, situacao: null, sessoesVivas: [] as SessaoViva[], estado: "erro" as Estado };
  const sessoesVivas = dados?.sessoesVivas ?? [];
  const pedida = escolhida ? sessoesVivas.find((s) => s.sessaoId === escolhida) : undefined;
  return {
    sessaoId: pedida?.sessaoId ?? dados?.sessaoId ?? null,
    situacao: pedida?.situacao ?? dados?.situacao ?? null,
    sessoesVivas,
    estado,
  };
}
