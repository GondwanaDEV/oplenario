// Lógica pura da BUSCA intra-câmara (Faixa A / A.5 da Track IA). O backend (GET /busca) já devolve só o que a Casa
// pode ver, hidratado das tabelas do core; aqui só se decide o TEXTO de cada resultado, para onde ele leva e que
// palavras destacar. Nunca o id cru na tela.

import { formatarData } from "./formatar-data";
import { formatarNumeroProposicao } from "./proposicoes-vista";
import { nomeTipoSessao } from "./rotulos-sessao";
import { relogio } from "./transcricao-vista";

export type TipoResultado = "proposicao" | "transcricao";

export type ProposicaoAchadaOut = {
  id: string;
  tipo: string;
  ano: number;
  sequencial: number;
  ementa: string;
  autorTexto?: string | null;
};

export type ResultadoBuscaOut =
  | { tipo: "proposicao"; score?: number; trecho?: string; proposicao: ProposicaoAchadaOut }
  | {
      tipo: "transcricao";
      score: number;
      trecho: string;
      sessao: { id: string; tipo: string; numero: number; data?: string | null };
      transcricaoId: string;
      inicio?: number;
      fim?: number;
      orador?: string;
    };

export type BuscaOut = { modo: "ia" | "sem-ia"; aviso?: string; resultados: ResultadoBuscaOut[] };

export type VistaResultado = {
  chave: string;
  tipo: TipoResultado;
  rotuloTipo: string;
  titulo: string;
  detalhe: string;
  trecho: string | null;
  href: string;
};

export const FILTROS: Array<{ rotulo: string; tipos: TipoResultado[] }> = [
  { rotulo: "Tudo", tipos: ["proposicao", "transcricao"] },
  { rotulo: "Proposições", tipos: ["proposicao"] },
  { rotulo: "Falas em plenário", tipos: ["transcricao"] },
];

export function vistaResultado(r: ResultadoBuscaOut, i: number): VistaResultado {
  if (r.tipo === "proposicao") {
    const p = r.proposicao;
    return {
      chave: `p-${p.id}`,
      tipo: "proposicao",
      rotuloTipo: "Proposição",
      titulo: formatarNumeroProposicao(p.tipo, p.sequencial, p.ano),
      detalhe: p.autorTexto ? `Autoria: ${p.autorTexto}` : "",
      // o que se lê da proposição é a EMENTA vigente (do core), não o trecho que a IA indexou
      trecho: p.ementa,
      href: `/ficha-materia/${encodeURIComponent(p.id)}`,
    };
  }
  const s = r.sessao;
  const titulo = `Sessão ${nomeTipoSessao(s.tipo)} nº ${s.numero}${s.data ? ` · ${formatarData(s.data)}` : ""}`;
  const quem = r.orador ?? "Orador não identificado";
  return {
    chave: `t-${r.transcricaoId}-${i}`,
    tipo: "transcricao",
    rotuloTipo: "Fala em plenário",
    titulo,
    detalhe: typeof r.inicio === "number" ? `${quem} · aos ${relogio(r.inicio)} da gravação` : quem,
    trecho: r.trecho,
    href: `/sessoes/${encodeURIComponent(s.id)}/transcricao`,
  };
}

function normalizar(p: string): string {
  return p.normalize("NFD").replace(/\p{M}/gu, "").toLocaleLowerCase("pt-BR");
}

/** As palavras da consulta que valem destaque: 3+ letras, sem acento, pelo radical (as 5 primeiras letras — "escola"
 *  destaca "escolar"). Mesma ideia do lado lexical do índice. */
export function termosDaConsulta(consulta: string): string[] {
  const termos = (consulta.match(/[\p{L}\p{N}]+/gu) ?? [])
    .map(normalizar)
    .filter((t) => t.length >= 3)
    .map((t) => t.slice(0, 5));
  return [...new Set(termos)];
}

export type Pedaco = { texto: string; destaque: boolean };

/** Parte o texto em pedaços, marcando as palavras que casam com a consulta. Juntar os pedaços devolve o texto. */
export function destacar(texto: string, termos: string[]): Pedaco[] {
  if (termos.length === 0) return [{ texto, destaque: false }];
  const saida: Pedaco[] = [];
  let ultimo = 0;
  for (const m of texto.matchAll(/[\p{L}\p{N}]+/gu)) {
    const palavra = normalizar(m[0]);
    if (palavra.length >= 3 && termos.some((t) => palavra.startsWith(t))) {
      const ini = m.index ?? 0;
      if (ini > ultimo) saida.push({ texto: texto.slice(ultimo, ini), destaque: false });
      saida.push({ texto: m[0], destaque: true });
      ultimo = ini + m[0].length;
    }
  }
  if (ultimo < texto.length) saida.push({ texto: texto.slice(ultimo), destaque: false });
  return saida;
}

export function resumoDaBusca(n: number): string {
  if (n === 0) return "Nada encontrado.";
  return n === 1 ? "1 resultado" : `${n} resultados`;
}
