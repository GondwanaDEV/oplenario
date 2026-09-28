// View-model puro dos DADOS ABERTOS do portal (Onda E, `dados-abertos`; Decreto 8.777/2016 + LAI art. 8 §3) —
// porte de produto/design-system/o-plenario/telas/dados-abertos.html com o que existe:
//   • o formato é CSV (o chip "JSON"/"API" do design não entra como dataset: a API JSON do portal já existe, mas
//     não é um arquivo para baixar — a nota do rodapé a menciona pelo que é);
//   • "Despesas e empenhos" fica como em-breve honesto: o dado fiscal é do sistema contábil (o documento-mestre
//     veta produzi-lo aqui) e depende de um conector que ainda não existe;
//   • "Presença em sessões" não vira arquivo: o número público de presença segue a nota metodológica (docs/14) no
//     perfil de cada vereador, e a linha crua por sessão sem essa leitura mostraria ausência onde há licença.

import type { DadosAbertosOut, DatasetAbertoOut } from "./contrato-portal.gen";
import { formatarData } from "./formatar-data";

export type DatasetVista = {
  chave: string;
  titulo: string;
  descricao: string;
  formato: string; // "CSV"
  registros: string; // "1.234 registros" | "nenhum registro ainda"
  atualizado: string | null; // "atualizado em 01/09/2026"
  href: string; // o arquivo, pela rota do portal
  arquivo: string;
  colunas: { nome: string; descricao: string }[];
};

export const FORA = [
  {
    titulo: "Despesas e empenhos",
    motivo:
      "A execução orçamentária é produzida pelo sistema contábil da Câmara. Ela entra aqui quando esse sistema " +
      "estiver ligado ao portal — o O Plenário publica o dado, não o produz.",
  },
  {
    titulo: "Presença em sessões",
    motivo:
      "A presença de cada vereador está no perfil dele, contada segundo a nota metodológica pública (período de " +
      "exercício e licenças). O registro nominal de cada sessão está na ata.",
  },
];

const NUMERO = new Intl.NumberFormat("pt-BR");

function registros(n: number): string {
  if (n === 0) return "nenhum registro ainda";
  return `${NUMERO.format(n)} ${n === 1 ? "registro" : "registros"}`;
}

export function hrefDataset(ente: string, arquivo: string): string {
  return `/api/portal/casa/${encodeURIComponent(ente)}/dados-abertos/${encodeURIComponent(arquivo)}`;
}

function dataset(d: DatasetAbertoOut, ente: string): DatasetVista {
  return {
    chave: d.chave,
    titulo: d.titulo,
    descricao: d.descricao,
    formato: d.formato.toUpperCase(),
    registros: registros(d.linhas),
    atualizado: d.atualizadoEm ? `atualizado em ${formatarData(d.atualizadoEm)}` : null,
    href: hrefDataset(ente, d.arquivo),
    arquivo: d.arquivo,
    colunas: d.colunas,
  };
}

export function derivarDadosAbertos(d: DadosAbertosOut, ente: string): DatasetVista[] {
  return d.datasets.map((x) => dataset(x, ente));
}
