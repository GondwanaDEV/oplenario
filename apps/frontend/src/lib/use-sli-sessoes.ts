"use client";

// Hook de sessões do tenant (Onda C Slice C2, pauta-convocacao) — mesmo padrão de use-tramitacao-board.ts:
// fetch autenticado de UMA rota (GET /api/paineis/sli/sessoes, já gated ao papel "secretario" no backend),
// camelizarChaves do boundary, estados carregando/pronto/erro, cleanup por `vivo`. Reexporta SliSessaoOut
// de use-mesa.ts (mesmo tipo, evita 2ª definição divergente).

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { SliSessaoOut } from "./use-mesa";

export type { SliSessaoOut };

type Estado = "carregando" | "pronto" | "erro";

export function useSliSessoes(token: string | null) {
  const [sessoes, setSessoes] = useState<SliSessaoOut[] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/paineis/sli/sessoes", { token: token ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const corpo = camelizarChaves(await r.json()) as { sessoes: SliSessaoOut[] };
        if (!vivo) return;
        setSessoes(corpo.sessoes);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  if (semCredencial(token)) {
    return { sessoes: null, estado: "erro" as Estado };
  }
  return { sessoes, estado };
}
