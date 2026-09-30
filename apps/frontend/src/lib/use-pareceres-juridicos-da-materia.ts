"use client";

// O que o parecer jurídico tem a dizer sobre UMA matéria, para a ficha interna: os pareceres já ASSINADOS (o mais novo
// primeiro) e os pedidos ainda em aberto. GET /api/legislativo/proposicoes/:id/pareceres-juridicos — leem secretaria,
// vereadores e o jurídico; para os demais papéis o servidor devolve 403 e a aba diz isso, em vez de "erro".

import { useCarregarJuridico } from "./use-juridico";
import type { PareceresJuridicosDaMateriaOut } from "./contrato-juridico.gen";

const valido = (d: unknown) =>
  Array.isArray((d as PareceresJuridicosDaMateriaOut)?.pareceres) && Array.isArray((d as PareceresJuridicosDaMateriaOut)?.pedidosAbertos);

export function usePareceresJuridicosDaMateria(token: string | null, proposicaoId: string | null) {
  return useCarregarJuridico<PareceresJuridicosDaMateriaOut>(
    token,
    proposicaoId ? `/api/legislativo/proposicoes/${encodeURIComponent(proposicaoId)}/pareceres-juridicos` : null,
    "listar-materia",
    valido,
  );
}
