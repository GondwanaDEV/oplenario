"use client";

// Hook de detalhe de uma proposição (Onda B Slice 2) — GET /api/legislativo/proposicoes/:id. `id === null`
// é o fluxo de CRIAR (não há nada pra buscar); devolve "pronto"/dados-nulo sem chamar fetch, mesmo
// idioma de useProposicoes (guard `vivo` contra unmount).

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ProposicaoDetalheOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function useProposicaoDetalhe(token: string | null, id: string | null) {
  const [dados, setDados] = useState<ProposicaoDetalheOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);

  // Reset ao trocar de `id` (mesmo padrão de use-proposicoes.ts): sem isto, os `dados`/estado "pronto" do
  // id anterior ficariam visíveis até o novo fetch do efeito abaixo resolver. Reset DURANTE O RENDER (não
  // dentro do `useEffect`) é o padrão que `eslint-plugin-react-hooks` v7 (`set-state-in-effect`) exige em
  // vez de um `setState` síncrono no topo do efeito.
  if (id !== idAnterior) {
    setIdAnterior(id);
    setEstado(id ? "carregando" : "pronto");
  }

  useEffect(() => {
    if (!id) return;
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch(`/api/legislativo/proposicoes/${id}`, {
          token: token ?? undefined,
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
  if (semCredencial(token)) return { dados: null, estado: "erro" as Estado };
  return { dados, estado };
}
