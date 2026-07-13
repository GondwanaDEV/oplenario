"use client";

// Hook de leitura composta do pós-aprovação (Onda B Slice 7) — GET /api/legislativo/proposicoes/:id/
// pos-aprovacao. Mirror EXATO de use-ficha-materia.ts (mesmo idioma 3-estados + reset em render-time ao
// trocar `id`). `proposicaoId` aqui não muda de fato (fixo pela rota /pos-aprovacao/:id), mas o hook segue
// o mesmo contrato genérico dos outros hooks-por-id do módulo.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { PosAprovacaoOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function usePosAprovacao(token: string | null, proposicaoId: string | null) {
  const [dados, setDados] = useState<PosAprovacaoOut | null>(null);
  const [estado, setEstado] = useState<Estado>(proposicaoId ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(proposicaoId);

  // Reset DURANTE O RENDER (não dentro do useEffect) — mesmo padrão de use-ficha-materia.ts: sem isto, os
  // dados/estado "pronto" do id anterior ficariam visíveis até o fetch do novo id resolver.
  if (proposicaoId !== idAnterior) {
    setIdAnterior(proposicaoId);
    setEstado(proposicaoId ? "carregando" : "pronto");
  }

  useEffect(() => {
    if (!proposicaoId) return;
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch(`/api/legislativo/proposicoes/${encodeURIComponent(proposicaoId)}/pos-aprovacao`, {
          token: token ?? undefined,
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as PosAprovacaoOut);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, proposicaoId]);

  if (!proposicaoId) return { dados: null, estado: "pronto" as Estado };
  if (semCredencial(token)) return { dados: null, estado: "erro" as Estado };
  return { dados, estado };
}
