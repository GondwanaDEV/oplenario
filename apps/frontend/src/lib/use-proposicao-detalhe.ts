"use client";

// Hook de detalhe de uma proposição (Onda B Slice 2) — GET /api/legislativo/proposicoes/:id. `id === null`
// é o fluxo de CRIAR (não há nada pra buscar); devolve "pronto"/dados-nulo sem chamar fetch, mesmo
// idioma de useProposicoes (guard `vivo` contra unmount).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ProposicaoDetalheOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

export function useProposicaoDetalhe(token: string | null, id: string | null) {
  const [dados, setDados] = useState<ProposicaoDetalheOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");

  useEffect(() => {
    if (!id) return;
    if (!token) return;
    let vivo = true;
    setEstado("carregando");
    (async () => {
      try {
        const r = await fetch(`/api/legislativo/proposicoes/${id}`, {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as ProposicaoDetalheOut);
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
