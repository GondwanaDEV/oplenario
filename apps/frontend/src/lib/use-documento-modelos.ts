"use client";

// Hook da lista de modelos ATIVOS (Onda B Slice 6) — GET /api/legislativo/documento-modelos. Sem filtros
// (mirror simplificado de use-proposicoes.ts): o seletor da aba "Gerar documento" sempre mostra todos os
// modelos ativos do tenant, sem paginação/busca.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ListaModelosOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function useDocumentoModelos(token: string | null) {
  const [dados, setDados] = useState<ListaModelosOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/legislativo/documento-modelos", {
          token: token ?? undefined,
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as ListaModelosOut);
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
    return { dados: null, estado: "erro" as Estado };
  }
  return { dados, estado };
}
