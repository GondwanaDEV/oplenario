// Lógica pura da tela ATA DA SESSÃO (Faixa A / A.6a da Track IA). A ata é artefato legal do core (§22.3.4): a
// publicada não muda — corrigir é RETIFICAR (nova versão, com motivo; a anterior fica guardada). Aqui só se decide
// o TEXTO da tela: de onde veio a redação, quem publicou e quando, e se o formulário pode ser enviado.

import type { AtaSessaoOut, AtaVersaoOut } from "./contrato-sessoes.gen";
import { formatarData, formatarHora } from "./formatar-data";

export const TETO_TEXTO_ATA = 200000;

/** O discriminador `origem_redacao` (§22.6) em linguagem de quem usa. */
export function origemDaRedacao(v: Pick<AtaVersaoOut, "origemRedacao">): string {
  return v.origemRedacao === "gerada_automaticamente"
    ? "Partiu de um rascunho da IA, revisado por uma pessoa"
    : "Redigida pela Casa";
}

/** "Versão 2 · publicada por Maria Souza em 26/09/2026 às 18:04". Sem nome resolvido, não inventa nem mostra id. */
export function linhaDaVersao(v: AtaVersaoOut): string {
  const por = v.publicadaPorNome ? ` por ${v.publicadaPorNome}` : "";
  return `Versão ${v.versao} · publicada${por} em ${formatarData(v.publicadaEm)} às ${formatarHora(v.publicadaEm)}`;
}

/** Por que não há o que fazer aqui (a sessão não gera ata, ou ainda não acabou). null = pode ter ata. */
export function semAta(a: Pick<AtaSessaoOut, "podeTerAta" | "atual">): string | null {
  if (a.podeTerAta || a.atual) return null;
  return "Esta sessão não tem ata: ela ainda não foi encerrada, ou é de um tipo que não gera ata regimental (solene ou especial).";
}

export type Formulario = { texto: string; motivo: string; retificando: boolean };

/** O que falta para poder publicar — null quando está pronto. */
export function faltaParaPublicar(f: Formulario): string | null {
  if (f.texto.trim() === "") return "Escreva ou cole o texto da ata.";
  if (f.texto.length > TETO_TEXTO_ATA) return `O texto passou do limite de ${TETO_TEXTO_ATA.toLocaleString("pt-BR")} caracteres.`;
  if (f.retificando && f.motivo.trim() === "") return "Diga o motivo da retificação — ele fica registrado junto da nova versão.";
  const pontos = f.texto.match(/\[\s*confirmar\s*:/gi)?.length ?? 0;
  if (pontos)
    return `Resolva ${pontos === 1 ? "o ponto" : `os ${pontos} pontos`} a confirmar ([confirmar: …]) antes de publicar.`;
  return null;
}

/** Traduz a recusa do servidor na frase da tela (nunca o corpo cru de um 500). */
export function mensagemDeErroAta(status: number, erro: string | undefined): string {
  if (status === 409 || status === 422) return erro ?? "A ata não pôde ser publicada agora.";
  if (status === 403) return "Você não tem permissão para publicar a ata desta sessão.";
  if (status === 400) return "O texto enviado não foi aceito. Confira e tente de novo.";
  return "Não foi possível publicar a ata agora. Nada foi alterado — tente de novo.";
}
