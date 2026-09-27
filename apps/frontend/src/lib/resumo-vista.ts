// Lógica pura do RESUMO CIDADÃO na ficha interna (Faixa A / A.8). A IA redige sozinha a cada versão nova do texto da
// proposição; a secretaria revisa e publica, e só o publicado vai ao portal. Aqui só se decide o TEXTO: em que pé está
// o rascunho, por que revisar com atenção, o que ficou para trás e o que falta para publicar.

import type { RascunhoResumoPonteiroOut, ResumoVersaoOut } from "./contrato-legislativo.gen";
import { formatarData } from "./formatar-data";

export const TETO_TEXTO_RESUMO = 4000;

export type SituacaoResumo = { titulo: string; detalhe: string; revisar: boolean };

const FALHAS: Record<string, string> = {
  infraestrutura: "a IA ficou fora do ar",
  sobrecarga: "a IA estava sobrecarregada",
  entrada: "o texto da proposição não pôde ser lido",
  modelo: "o modelo não conseguiu redigir",
};

export function situacaoDoResumo(r: RascunhoResumoPonteiroOut | null | undefined): SituacaoResumo {
  if (!r)
    return {
      titulo: "A IA ainda não redigiu o resumo",
      detalhe: "Ela redige sozinha quando a proposição é protocolada ou o texto muda. Enquanto isso, dá para escrever à mão.",
      revisar: false,
    };
  if (r.situacao === "falhou")
    return {
      titulo: "A IA não conseguiu redigir o resumo",
      detalhe: `Motivo: ${FALHAS[r.categoriaErro ?? ""] ?? "falha da IA"}. Escreva o resumo à mão; a IA tenta de novo quando o texto mudar.`,
      revisar: false,
    };
  const partes = [
    r.nCitacoes != null ? `${r.nCitacoesConferidas ?? 0} de ${r.nCitacoes} citações conferidas` : null,
    r.nParagrafosSemFonte ? `${r.nParagrafosSemFonte} parágrafo${r.nParagrafosSemFonte > 1 ? "s" : ""} sem fonte` : null,
  ].filter(Boolean);
  return {
    titulo: r.desatualizado ? "Rascunho da IA — de uma versão anterior do texto" : "Rascunho da IA pronto para revisar",
    detalhe: r.desatualizado
      ? "O texto da proposição mudou depois que a IA o leu; ela já está redigindo de novo. Dá para revisar este mesmo assim."
      : partes.length
        ? `${partes.join(" · ")}.`
        : "Revise antes de publicar.",
    revisar: true,
  };
}

const MOTIVOS: Record<string, string> = {
  conteudo_de_terceiro: "o resumo parte de um texto escrito pelo autor da matéria, que a IA lê como dado",
  sem_fonte: "há parágrafos sem fonte no texto da proposição",
  citacao_nao_conferida: "alguma citação não bate com o texto",
  truncado: "o rascunho foi cortado antes do fim",
};

export function avisoDoResumo(nivel: string, motivos: string[]): string | null {
  if (nivel !== "revisar_com_atencao") return null;
  const frases = motivos.map((m) => MOTIVOS[m]).filter(Boolean);
  return frases.length ? `Revise com atenção: ${frases.join("; ")}.` : "Revise com atenção antes de publicar.";
}

export function linhaDaVersao(v: Pick<ResumoVersaoOut, "versao" | "publicadoEm" | "origemRedacao">): string {
  const origem = v.origemRedacao === "gerada_automaticamente" ? "a partir do rascunho da IA" : "escrito pela Casa";
  return `Versão ${v.versao} · publicada em ${formatarData(v.publicadoEm)} · ${origem}`;
}

/** O que impede publicar, em linguagem da tela; null = pode publicar. */
export function faltaParaPublicar(texto: string): string | null {
  const t = texto.trim();
  if (!t) return "Escreva o resumo.";
  if (t.length > TETO_TEXTO_RESUMO) return `O resumo passou de ${TETO_TEXTO_RESUMO} caracteres: encurte.`;
  return null;
}

export function mensagemDeErroResumo(status: number, erro: string | undefined): string {
  if (status === 409) return erro ?? "Outra versão foi publicada ao mesmo tempo: recarregue a página.";
  if (status === 400) return "O resumo não foi aceito: confira o texto.";
  if (status === 403) return "Só a secretaria publica o resumo.";
  return "Não foi possível publicar agora. Tente de novo.";
}
