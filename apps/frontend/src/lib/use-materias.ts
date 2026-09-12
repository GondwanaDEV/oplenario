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
import type { MateriaOut, MateriasOut } from "./contrato-portal.gen";

type Estado = "carregando" | "pronto" | "erro";

export function useMaterias(ente: string) {
  const [itens, setItens] = useState<MateriaOut[] | null>(null);
  // `materiasTotal` (frente "truncamento-familia" sitio (a)/(b)): o par autoritativo que veio junto de
  // `itens` na MESMA resposta — nunca `itens.length` (capado em 200 pelo backend). `0` (nao `null`) até o
  // 1º fetch resolver: `escolherDestaque` só o lê quando `itens` já não é null (ver secao-em-tramitacao.tsx).
  const [materiasTotal, setMateriasTotal] = useState(0);
  const [estado, setEstado] = useState<Estado>("carregando");
  const [enteAnterior, setEnteAnterior] = useState(ente);

  // Reset cross-tenant (review A2.1, item 1): sem isto, trocar de `ente` (ex.
  // /portal/casa/fortaleza -> /portal/casa/aquiraz) mantinha os itens/estado "pronto" da câmara
  // anterior visíveis até o novo fetch do efeito abaixo resolver. O guard `vivo` do efeito sozinho não
  // cobre isto: ele só evita escritas fora de ordem de fetches concorrentes, não limpa o estado já
  // commitado da câmara antiga. Reset DURANTE O RENDER (não dentro do `useEffect`) é o padrão oficial
  // do React para "storing information from previous renders" — react.dev/reference/react/useState;
  // `eslint-plugin-react-hooks` v7 (`set-state-in-effect`) proíbe o `setState` síncrono no topo de um
  // efeito e recomenda exatamente esta forma condicional como alternativa.
  if (ente !== enteAnterior) {
    setEnteAnterior(ente);
    setItens(null);
    setMateriasTotal(0);
    setEstado("carregando");
  }

  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await buscarPublico<MateriasOut>(ente, "materias");
      if (!vivo) return;
      if (!r) {
        setEstado("erro");
        return;
      }
      setItens(r.materias);
      setMateriasTotal(r.materiasTotal);
      setEstado("pronto");
    })();
    return () => {
      vivo = false;
    };
  }, [ente]);

  return { itens, materiasTotal, estado };
}
