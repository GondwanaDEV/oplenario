"use client";

// A dica da tela para a Clara (prancha assistente-da-casa.html, "Nesta tela: PL 042/2026"): a página diz do que trata,
// e a Clara oferece "Perguntar sobre esta matéria", que só começa a pergunta no campo — a pessoa termina e envia. A
// tela NUNCA é fonte: a Clara continua consultando o sistema pelas ferramentas, com o acesso da pessoa.

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";

export type DicaDaClara = {
  /** O que está na tela, curto: "PL 42/2026". */
  rotulo: string;
  /** O começo da pergunta que o botão põe no campo: "Sobre o PL 42/2026, ". */
  inicio: string;
  /** O texto do botão: "Perguntar sobre esta matéria". */
  acao: string;
};

const DicaAtual = createContext<DicaDaClara | null>(null);
const DefinirDica = createContext<((d: DicaDaClara | null) => void) | null>(null);

export function ProvedorDaDica({ children }: { children: ReactNode }) {
  const [dica, definir] = useState<DicaDaClara | null>(null);
  return (
    <DefinirDica.Provider value={definir}>
      <DicaAtual.Provider value={dica}>{children}</DicaAtual.Provider>
    </DefinirDica.Provider>
  );
}

export function useDicaAtual(): DicaDaClara | null {
  return useContext(DicaAtual);
}

/** A página declara do que trata enquanto está montada (nil = nada). Fora da moldura da Clara, não faz nada. */
export function useDicaDaClara(dica: DicaDaClara | null) {
  const definir = useContext(DefinirDica);
  const rotulo = dica?.rotulo;
  const inicio = dica?.inicio;
  const acao = dica?.acao;
  useEffect(() => {
    if (!definir || !rotulo || !inicio || !acao) return;
    definir({ rotulo, inicio, acao });
    return () => definir(null);
  }, [definir, rotulo, inicio, acao]);
}

/** A dica de uma matéria: "Nesta tela: PL 42/2026" → "Sobre o PL 42/2026, ". */
export function dicaDaMateria(tipo: string, sequencial: number | null | undefined, ano: number | null | undefined): DicaDaClara | null {
  if (!sequencial || !ano) return null;
  const numero = formatarNumeroProposicao(tipo, sequencial, ano);
  return { rotulo: numero, inicio: `Sobre o ${numero}, `, acao: "Perguntar sobre esta matéria" };
}
