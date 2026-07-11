"use client";

// Hook de ficha da matéria (Onda B Slice 3) — GET /api/legislativo/proposicoes/:id/ficha. Mirror EXATO de
// use-proposicao-detalhe.ts (mesmo idioma 3-estados + reset em render-time ao trocar `id`, exigido por
// eslint-plugin-react-hooks v7 `set-state-in-effect` em vez de setState síncrono dentro do useEffect).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { FichaMateriaOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

export function useFichaMateria(token: string | null, id: string | null) {
  const [dados, setDados] = useState<FichaMateriaOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);

  // Reset DURANTE O RENDER (não dentro do useEffect) — mesmo padrão de use-proposicao-detalhe.ts: sem
  // isto, os dados/estado "pronto" do id anterior ficariam visíveis até o fetch do novo id resolver.
  if (id !== idAnterior) {
    setIdAnterior(id);
    setEstado(id ? "carregando" : "pronto");
  }

  useEffect(() => {
    if (!id) return;
    if (!token) return;
    let vivo = true;
    (async () => {
      try {
        const r = await fetch(`/api/legislativo/proposicoes/${encodeURIComponent(id)}/ficha`, {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as FichaMateriaOut);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, id]);

  if (!id) return { dados: null, estado: "pronto" as Estado };
  if (!token) return { dados: null, estado: "erro" as Estado };
  return { dados, estado };
}
