// Lógica pura do COPILOTO DO RELATOR (ADR-0019, Eixo 5 / fatia 2): o que a tela diz quando a IA devolve o rascunho da
// análise de constitucionalidade e juridicidade do parecer de comissão. É RASCUNHO: vai para o campo Análise do editor
// só quando a pessoa pede, e o relator revisa e salva pelo fluxo de sempre. Texto de IA nunca é chamado de parecer —
// quem assina o parecer da comissão é o relator (ADR-0019, contexto 6).

import type { AnaliseCopilotoOut, CopilotoAnaliseOut } from "./contrato-legislativo.gen";

export const ROTULO_BOTAO_ANALISE = "Rascunhar análise de constitucionalidade e juridicidade";

export const SELO_RASCUNHO_IA = "Rascunho da IA — revise antes de salvar. Não é parecer: quem assina é o relator.";

export const EXPLICACAO_COPILOTO =
  "A IA lê a matéria e a Lei Orgânica e o Regimento da Casa, e devolve um rascunho da análise com as citações. Nada é salvo até você salvar.";

export type ResultadoAnalise =
  | { tipo: "rascunho"; analise: AnaliseCopilotoOut; normas: CopilotoAnaliseOut["normas"] }
  | { tipo: "nada"; mensagem: string };

/** O que a resposta do core vira na tela: um rascunho para revisar ou uma mensagem (a tela segue sem a IA). */
export function lerResultadoAnalise(r: CopilotoAnaliseOut): ResultadoAnalise {
  if (r.analise) return { tipo: "rascunho", analise: r.analise, normas: r.normas };
  if (r.indisponivel) return { tipo: "nada", mensagem: `${r.indisponivel} Redija a análise no campo abaixo.` };
  return {
    tipo: "nada",
    mensagem: "O assistente não conseguiu rascunhar a análise desta matéria. Redija no campo abaixo.",
  };
}

export function mensagemDeErroAnalise(status: number): string {
  if (status === 503)
    return "O assistente está indisponível agora. Redija a análise no campo abaixo — nada do parecer depende dele.";
  if (status === 404) return "Este parecer não está com você como relator, ou não existe nesta Casa.";
  if (status === 403) return "Seu acesso não permite pedir o rascunho da análise deste parecer.";
  if (status === 401) return "Sua sessão expirou. Entre de novo.";
  return "Não foi possível falar com o assistente agora. Redija a análise no campo abaixo.";
}

/** O aviso sobre a base normativa do rascunho: sem normas publicadas na Casa, ou sem dispositivo citado. */
export function avisoDasNormas(normas: CopilotoAnaliseOut["normas"]): string | null {
  if (normas === "sem-normas")
    return "A Casa ainda não publicou a Lei Orgânica nem o Regimento na plataforma: o rascunho foi feito só com a matéria. Confira cada ponto nas normas antes de usar.";
  if (normas === "sem-dispositivo")
    return "O assistente não achou nas normas da Casa um dispositivo que trate desta matéria. Confira a base legal antes de usar.";
  return null;
}

/** O aviso de revisão: parte do texto sem fonte ou citação que não conferiu. */
export function avisoDaAnalise(a: AnaliseCopilotoOut): string | null {
  const naoConferidas = a.citacoes.filter((c) => c.status !== "conferida").length;
  if (naoConferidas > 0) return "Há citação que não conferiu com o texto lido. Confira antes de usar.";
  if (a.paragrafosSemFonte.length > 0) return "Parte do rascunho não tem fonte. Revise com atenção.";
  return null;
}

export type ModoDeUso = "substituir" | "acrescentar";

/** O texto do campo Análise depois de usar o rascunho: no campo vazio (ou ao substituir) entra o rascunho; ao acrescentar,
 *  vai depois do que já está, separado por uma linha em branco. */
export function juntarAnalise(atual: string, rascunho: string, modo: ModoDeUso): string {
  if (modo === "substituir" || atual.trim() === "") return rascunho;
  return `${atual.replace(/\s+$/, "")}\n\n${rascunho}`;
}

/** "De onde veio": a matéria ou o dispositivo da Casa (com a data até quando foi conferido), e o trecho. */
export function rotuloDaCitacao(c: { fonteId: string; rotulo: string | null }): string {
  if (c.rotulo) return c.rotulo;
  if (c.fonteId.startsWith("materia:")) return "A matéria";
  return c.fonteId;
}
