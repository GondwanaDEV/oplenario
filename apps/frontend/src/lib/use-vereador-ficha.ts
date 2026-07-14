"use client";

// Hook da ficha de um vereador (Cadastro de Vereadores, Task 8) — GET /api/cadastros/vereadores/:id.
// Mirror de use-ficha-materia.ts / use-proposicao-detalhe.ts (fetch-on-id, mesmo idioma 3-estados + reset
// em render-time ao trocar `id`, exigido por eslint-plugin-react-hooks v7 `set-state-in-effect` em vez de
// setState síncrono dentro do useEffect). `id === null` -> nada pra buscar (nenhum vereador selecionado no
// master-detail), devolve "pronto"/dados-nulo sem chamar fetch.
//
// Diferença deliberada dos dois mirrors: eles resetam só o `estado` no bloco de render (o `dados` do id
// anterior só é substituído quando o novo `return { dados, estado }` já teria os dados atualizados — não há
// teste cobrindo o instante intermediário). Task 8 exige explicitamente "reseta prior dados" na troca de
// id, então este hook também zera `dados` nesse mesmo bloco — degrau a mais sobre o padrão existente, não
// um padrão novo.
//
// Degrada independentemente de useVereadores: um 404/erro aqui nunca deriva pra erro da lista (hooks
// separados, chamadas separadas).

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { VereadorFichaOut } from "./contrato-cadastros.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function useVereadorFicha(token: string | null, id: string | null) {
  const [dados, setDados] = useState<VereadorFichaOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);

  // Reset DURANTE O RENDER (não dentro do useEffect): sem isto, os dados/estado "pronto" do id anterior
  // ficariam visíveis até o fetch do novo id resolver.
  if (id !== idAnterior) {
    setIdAnterior(id);
    setDados(null);
    setEstado(id ? "carregando" : "pronto");
  }

  useEffect(() => {
    if (!id) return;
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch(`/api/cadastros/vereadores/${encodeURIComponent(id)}`, {
          token: token ?? undefined,
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as VereadorFichaOut);
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
