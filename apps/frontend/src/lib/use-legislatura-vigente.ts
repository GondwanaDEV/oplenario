"use client";

// Hook da legislatura vigente (Cadastro de Vereadores, Onda D Slice 4) — GET /api/cadastros/legislatura-vigente.
// Mirror de use-vereadores.ts: fetch autenticado (Bearer token), camelizarChaves do boundary.ts, estados
// carregando/pronto/erro, cleanup por `vivo` (abort-safe), caso sem token derivado no retorno (nunca
// setState síncrono dentro do effect). Diferença: 404 é um estado VAZIO válido (ainda não há legislatura
// cadastrada), não um erro — `dados` fica `null` e `estado` vai a "pronto" mesmo assim.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { LegislaturaVigenteOut } from "./contrato-cadastros.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function useLegislaturaVigente(token: string | null) {
  const [dados, setDados] = useState<LegislaturaVigenteOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/cadastros/legislatura-vigente", { token: token ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (r.status === 404) {
          setDados(null);
          setEstado("pronto");
          return;
        }
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const corpo = camelizarChaves(await r.json()) as LegislaturaVigenteOut;
        if (!vivo) return;
        setDados(corpo);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  // caso de erro sem token é derivado aqui (mantém o effect livre de setState síncrono)
  if (semCredencial(token)) {
    return { dados: null as LegislaturaVigenteOut | null, estado: "erro" as Estado };
  }
  return { dados, estado };
}
