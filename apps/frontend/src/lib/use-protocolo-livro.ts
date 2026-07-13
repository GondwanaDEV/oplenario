"use client";

// Hook do Livro do Protocolo Geral (Onda B Slice 6, feature 3.23) — GET /api/legislativo/protocolo-geral.
// Sem filtros: o backend já resolve o ano corrente (kernel/tempo, mesmo padrão de protocolar-documento).
// Mirror exato de use-documento-modelos.ts.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { LivroProtocoloOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function useProtocoloLivro(token: string | null) {
  const [dados, setDados] = useState<LivroProtocoloOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/legislativo/protocolo-geral", {
          token: token ?? undefined,
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as LivroProtocoloOut);
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
