// Lógica pura do COPILOTO DO REQUERIMENTO (Faixa B / B.7): o que a tela diz depois que o assistente preenche o
// formulário a partir do pedido em palavras. É rascunho: o vereador revisa cada campo e assina pelo fluxo de sempre.

import type { CopilotoRequerimentoOut } from "./contrato-legislativo.gen";

export type ResultadoCopiloto =
  | { tipo: "preenchido"; resposta: CopilotoRequerimentoOut }
  | { tipo: "nada"; mensagem: string };

/** O que a resposta do core vira na tela: preenchido (com o que a IA pôs no formulário) ou uma mensagem. */
export function lerResultadoCopiloto(r: CopilotoRequerimentoOut): ResultadoCopiloto {
  if (r.preenchimento) return { tipo: "preenchido", resposta: r };
  if (r.indisponivel) return { tipo: "nada", mensagem: `${r.indisponivel} Preencha o formulário abaixo.` };
  return {
    tipo: "nada",
    mensagem: "O assistente não conseguiu montar esse pedido com os modelos da Casa. Preencha o formulário abaixo.",
  };
}

export function mensagemDeErroCopiloto(status: number): string {
  if (status === 503) return "O assistente está indisponível agora. Preencha o formulário — nada do requerimento depende dele.";
  if (status === 400) return "Descreva o pedido com pelo menos 5 caracteres.";
  if (status === 404) return "Confira se o seu cadastro de vereador está vinculado a este login.";
  return "Não foi possível falar com o assistente agora. Preencha o formulário.";
}

/** Aviso sobre a justificativa redigida: sem citação conferida, a base legal não foi confirmada nas normas da Casa. */
export function avisoDaJustificativa(j: CopilotoRequerimentoOut["justificativa"]): string | null {
  if (!j) return null;
  const conferidas = j.citacoes.filter((c) => c.status === "conferida").length;
  if (conferidas === 0) {
    return "O assistente não achou nas normas da Casa um dispositivo que sustente a justificativa. Confira antes de assinar.";
  }
  if (j.paragrafosSemFonte.length > 0 || j.incerteza !== "normal") return "Parte da justificativa não tem fonte. Revise com atenção.";
  return null;
}
