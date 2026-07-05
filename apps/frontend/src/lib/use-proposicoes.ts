"use client";

// Hook da lista de proposições (Onda B Slice 1): busca GET /api/legislativo/proposicoes com os filtros
// atuais (querystring), cameliza o payload no boundary (mesmo padrão de use-mesa.ts) e refaz a busca a
// cada mudança de filtro (busca/tipo/estado/ano/página/ordenação).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ListaProposicoesOut } from "./contrato-legislativo.gen";

export type FiltrosProposicoes = {
  busca?: string;
  tipo?: string;
  estado?: string;
  ano?: number;
  pagina: number;
  tamanho: number;
  ordenarPor: string;
  ordenarDir: "asc" | "desc";
};

type Estado = "carregando" | "pronto" | "erro";

function montarQuerystring(filtros: FiltrosProposicoes): string {
  const params = new URLSearchParams();
  if (filtros.busca) params.set("busca", filtros.busca);
  if (filtros.tipo) params.set("tipo", filtros.tipo);
  if (filtros.estado) params.set("estado", filtros.estado);
  if (filtros.ano !== undefined) params.set("ano", String(filtros.ano));
  params.set("pagina", String(filtros.pagina));
  params.set("tamanho", String(filtros.tamanho));
  params.set("ordenar-por", filtros.ordenarPor);
  params.set("ordenar-dir", filtros.ordenarDir);
  return params.toString();
}

export function useProposicoes(token: string | null, filtros: FiltrosProposicoes) {
  const [dados, setDados] = useState<ListaProposicoesOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const chave = JSON.stringify(filtros);
  const [chaveAnterior, setChaveAnterior] = useState(chave);

  // Reset ao trocar de filtro (mesmo padrão de use-materias.ts): sem isto, os `dados`/estado "pronto" do
  // filtro anterior ficariam visíveis até o novo fetch do efeito abaixo resolver. Reset DURANTE O RENDER
  // (não dentro do `useEffect`) é o padrão que `eslint-plugin-react-hooks` v7 (`set-state-in-effect`)
  // exige em vez de um `setState` síncrono no topo do efeito.
  if (chave !== chaveAnterior) {
    setChaveAnterior(chave);
    setEstado("carregando");
  }

  useEffect(() => {
    if (!token) return; // caso de erro sem token é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      try {
        const r = await fetch(`/api/legislativo/proposicoes?${montarQuerystring(filtros)}`, {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        setDados(camelizarChaves(await r.json()) as ListaProposicoesOut);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `chave` já resume `filtros` por valor
  }, [token, chave]);

  if (!token) {
    return { dados: null, estado: "erro" as Estado };
  }
  return { dados, estado };
}
