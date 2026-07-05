"use client";

// Hook do Portal do Cidadão (Task 1.3, Fatia A2.1) — busca GET /api/portal/casa/{ente}/materias.
//
// DESVIO do plano (documentado no relatório da fatia): o plano original previa o fetch em page.tsx
// (Server Component). Um `fetch("/api/...")` relativo NÃO resolve em SSR — o rewrite same-origin de
// next.config.ts só reescreve requests que o BROWSER faz (o servidor Next não tem uma "origem" para
// resolver um path relativo). Mirror do padrão já provado de use-mesa.ts (A1): fetch client-side, 3
// estados (carregando/pronto/erro), degradação por seção — nunca derruba a página inteira.

import { useEffect, useState } from "react";
import { buscarPublico } from "./portal-api";
import type { MateriaOut } from "./contrato-portal.gen";

type Estado = "carregando" | "pronto" | "erro";

export function useMaterias(ente: string) {
  const [itens, setItens] = useState<MateriaOut[] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await buscarPublico<MateriaOut[]>(ente, "materias");
      if (!vivo) return;
      if (!r) {
        setEstado("erro");
        return;
      }
      setItens(r);
      setEstado("pronto");
    })();
    return () => {
      vivo = false;
    };
  }, [ente]);

  return { itens, estado };
}
