"use client";

// Hook do Portal do Cidadão (Task 2.2, Fatia A2.2) — busca GET /api/portal/casa/{ente}/encarregado.
//
// DESVIO do plano (mesmo racional já documentado em use-materias.ts, A2.1): o plano previa o fetch em
// page.tsx (Server Component), mas um `fetch("/api/...")` relativo não resolve em SSR — o rewrite
// same-origin de next.config.ts só reescreve requests do BROWSER. Mirror do padrão já provado de
// use-materias.ts: fetch client-side, 3 estados (carregando/pronto/erro), degradação por seção (o
// balcão LGPD continua de pé sem o bloco do Encarregado se este fetch falhar isoladamente).

import { useEffect, useState } from "react";
import { buscarPublico } from "./portal-api";
import type { EncarregadoOut } from "./contrato-portal.gen";

type Estado = "carregando" | "pronto" | "erro";

export function useEncarregado(ente: string) {
  const [encarregado, setEncarregado] = useState<EncarregadoOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const [enteAnterior, setEnteAnterior] = useState(ente);

  // mesmo reset cross-tenant de use-materias.ts (review A2.1, item 1) — durante o render, não dentro
  // do efeito.
  if (ente !== enteAnterior) {
    setEnteAnterior(ente);
    setEncarregado(null);
    setEstado("carregando");
  }

  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await buscarPublico<EncarregadoOut>(ente, "encarregado");
      if (!vivo) return;
      if (!r) {
        setEstado("erro");
        return;
      }
      setEncarregado(r);
      setEstado("pronto");
    })();
    return () => {
      vivo = false;
    };
  }, [ente]);

  return { encarregado, estado };
}
