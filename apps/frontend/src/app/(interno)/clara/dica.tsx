"use client";

// A dica da tela para a Clara (prancha assistente-da-casa.html, "Nesta tela: PL 042/2026"): a página diz do que trata,
// e a Clara oferece "Perguntar sobre esta matéria", que só começa a pergunta no campo — a pessoa termina e envia. A
// tela NUNCA é fonte: a Clara continua consultando o sistema pelas ferramentas, com o acesso da pessoa.

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { formatarTipoSessao, formatarTituloSessao } from "@/lib/pauta-convocacao-vista";
import { formatarData } from "@/lib/formatar-data";

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
  return dicaDaMateriaPeloNumero(formatarNumeroProposicao(tipo, sequencial, ano));
}

/** A dica de uma matéria cujo número já vem pronto do servidor (o `ref` do pedido jurídico: "PL 42/2026"). Sem número
 *  (a consulta avulsa do jurídico não tem matéria), não há dica. */
export function dicaDaMateriaPeloNumero(numero: string | null | undefined): DicaDaClara | null {
  const n = numero?.trim();
  if (!n) return null;
  return { rotulo: n, inicio: `Sobre o ${n}, `, acao: "Perguntar sobre esta matéria" };
}

/** O que a tela da sessão sabe dela: o tipo, o número e a data marcada. */
export type SessaoDaDica = {
  tipoSessao: string;
  numeroSequencial?: number | null;
  agendadaPara?: string | null;
};

/** O nome da sessão como as telas o escrevem: "15ª Sessão Ordinária" (a pauta), "Audiência pública nº 3" (a Mesa da
 *  audiência). Sem número, a data: "Sessão ordinária de 30/09/2026". Sem número nem data, nada — não se inventa nome. */
export function rotuloDaSessao(sessao: SessaoDaDica | null | undefined): string | null {
  if (!sessao) return null;
  const audiencia = sessao.tipoSessao === "audiencia_publica";
  const numero = sessao.numeroSequencial;
  if (numero) {
    return audiencia
      ? `Audiência pública nº ${numero}`
      : formatarTituloSessao({ numeroSequencial: numero, tipoSessao: sessao.tipoSessao });
  }
  if (sessao.agendadaPara) {
    const dia = formatarData(sessao.agendadaPara);
    return audiencia ? `Audiência pública de ${dia}` : `Sessão ${formatarTipoSessao(sessao.tipoSessao).toLowerCase()} de ${dia}`;
  }
  return null;
}

/** A dica de uma sessão: "Nesta tela: 15ª Sessão Ordinária" → "Sobre a 15ª Sessão Ordinária, ". */
export function dicaDaSessao(sessao: SessaoDaDica | null | undefined): DicaDaClara | null {
  const rotulo = rotuloDaSessao(sessao);
  if (!rotulo) return null;
  return { rotulo, inicio: `Sobre a ${rotulo}, `, acao: "Perguntar sobre esta sessão" };
}

/** A dica da pauta de uma sessão: "Nesta tela: Pauta da 15ª Sessão Ordinária" → "Sobre a pauta da 15ª Sessão
 *  Ordinária, ". */
export function dicaDaPauta(sessao: SessaoDaDica | null | undefined): DicaDaClara | null {
  const rotulo = rotuloDaSessao(sessao);
  if (!rotulo) return null;
  return { rotulo: `Pauta da ${rotulo}`, inicio: `Sobre a pauta da ${rotulo}, `, acao: "Perguntar sobre esta pauta" };
}

/** A dica da ata de uma sessão (a Clara lê atas pela ferramenta `ata_da_sessao`): "Nesta tela: Ata da 15ª Sessão
 *  Ordinária" → "Sobre a ata da 15ª Sessão Ordinária, ". */
export function dicaDaAta(sessao: SessaoDaDica | null | undefined): DicaDaClara | null {
  const rotulo = rotuloDaSessao(sessao);
  if (!rotulo) return null;
  return { rotulo: `Ata da ${rotulo}`, inicio: `Sobre a ata da ${rotulo}, `, acao: "Perguntar sobre esta ata" };
}
