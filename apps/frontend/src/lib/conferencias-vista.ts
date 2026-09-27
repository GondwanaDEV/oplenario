// Lógica pura da CONFERÊNCIA DAS PROPOSIÇÕES (Faixa B / B.8, ADR-0013). A cada proposição protocolada numa Casa que
// ligou o agente institucional, a IA lê o texto e os dispositivos da LOM/RI e deixa uma nota técnica em RASCUNHO na
// fila da secretaria. Aqui só se decide o TEXTO: a fila, em que pé está cada nota, por que revisar com atenção, o
// estado do agente (ligado pelo administrador da Casa) e os erros. A IA nunca decide nada.

import { formatarData, formatarHora } from "./formatar-data";
import { formatarNumeroProposicao } from "./proposicoes-vista";
import type { NotaTecnicaOut, NotaTecnicaResumoOut } from "./contrato-legislativo.gen";

export type EstadoNota = NotaTecnicaResumoOut["estado"];
export type FiltroFila = EstadoNota | "todas";

/** GET /api/identidade/agentes-institucionais (identidade não tem contrato gerado: forma escrita à mão). */
export type AgenteInstitucional = {
  agente: string;
  titulo: string;
  descricao: string;
  classes: string[];
  ligado: boolean;
  ligadoEm: string | null;
};

export const ABAS: { filtro: FiltroFila; rotulo: string }[] = [
  { filtro: "pendente", rotulo: "A conferir" },
  { filtro: "aproveitada", rotulo: "Aproveitadas" },
  { filtro: "descartada", rotulo: "Descartadas" },
];

export function numeroDaNota(n: Pick<NotaTecnicaResumoOut, "tipo" | "sequencial" | "ano">): string {
  return formatarNumeroProposicao(n.tipo, n.sequencial, n.ano);
}

function quando(iso: string): string {
  return `${formatarData(iso)}, ${formatarHora(iso)}`;
}

/** A linha de estado de uma nota, na fila e no detalhe. */
export function linhaDaNota(n: Pick<NotaTecnicaResumoOut, "estado" | "criadaEm" | "decididaEm">): string {
  if (n.estado === "aproveitada" && n.decididaEm) return `Aproveitada em ${quando(n.decididaEm)}`;
  if (n.estado === "descartada" && n.decididaEm) return `Descartada em ${quando(n.decididaEm)}`;
  return `Chegou em ${quando(n.criadaEm)}`;
}

export function vazioDaFila(filtro: FiltroFila, ligado: boolean | null): string {
  if (filtro === "aproveitada") return "Nenhuma nota aproveitada ainda.";
  if (filtro === "descartada") return "Nenhuma nota descartada.";
  if (ligado === false)
    return "Nenhuma nota a conferir. A conferência automática está desligada: as proposições protocoladas não passam pela IA.";
  return "Nenhuma nota a conferir. Quando uma proposição for protocolada, a nota da IA aparece aqui.";
}

const MOTIVOS: Record<string, string> = {
  sem_fonte: "há parágrafos que nenhum dispositivo lido sustenta",
  citacao_nao_conferida: "alguma citação não bate com o texto da norma",
  conteudo_de_terceiro: "o texto conferido veio de fora da Casa",
  truncado: "a nota foi cortada antes do fim",
};

/** Por que ler com atenção, na linguagem de quem confere. null = nada a destacar. */
export function avisoDaNota(nivel: string, motivos: string[]): string | null {
  if (nivel !== "revisar_com_atencao") return null;
  const frases = motivos.map((m) => MOTIVOS[m]).filter(Boolean);
  return frases.length ? `Leia com atenção: ${frases.join("; ")}.` : "Leia com atenção antes de aproveitar.";
}

/** O que a secretaria confirma antes de aproveitar: texto não vazio e dentro do teto. null = pode. */
export const TETO_TEXTO_NOTA = 20000;

export function faltaParaAproveitar(texto: string): string | null {
  const t = texto.trim();
  if (!t) return "Escreva o texto da nota (ou volte e descarte).";
  if (t.length > TETO_TEXTO_NOTA) return `A nota tem até ${TETO_TEXTO_NOTA} caracteres.`;
  return null;
}

export function linhaDoAgente(a: AgenteInstitucional): string {
  return a.ligado && a.ligadoEm ? `Ligada desde ${formatarData(a.ligadoEm)}` : "Desligada";
}

/** O texto que foi aproveitado (o editado, ou o da IA sem as marcas de citação). */
export function textoAproveitado(n: Pick<NotaTecnicaOut, "textoFinal" | "textoLimpo">): string {
  return n.textoFinal ?? n.textoLimpo;
}

export function mensagemDeErroConferencias(status: number, erro?: string): string {
  if (status === 409) return erro ?? "Esta nota já foi decidida.";
  if (status === 403) return "A conferência das proposições é da secretaria; ligar e desligar é do administrador da Casa.";
  if (status === 404) return "Esta nota não existe nesta Casa.";
  if (status === 400) return erro ?? "Pedido inválido.";
  return "Não foi possível falar com o servidor agora. Tente de novo.";
}
