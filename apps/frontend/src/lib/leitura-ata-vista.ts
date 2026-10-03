// Lógica pura do painel LEITURA DA ATA ANTERIOR (Faixa A / A.7): o nome da ata, como cada modo aparece, e o texto
// quebrado em trechos para a voz. Só se lê a versão VIGENTE publicada (o servidor garante; a tela mostra qual).

import type { LeituraAtaRegistradaOut, SessaoAnteriorOut } from "./contrato-sessoes.gen";
import { formatarData, formatarHora } from "./formatar-data";
import type { Trecho } from "./voz";

const TIPOS: Record<string, string> = {
  ordinaria: "Ordinária",
  extraordinaria: "Extraordinária",
  solene: "Solene",
  especial: "Especial",
  secreta: "Secreta",
  audiencia_publica: "Audiência pública",
};

export type ModoLeitura = LeituraAtaRegistradaOut["modo"];

export const MODOS: Record<ModoLeitura, { rotulo: string; registrar: string }> = {
  voz_sintetizada: { rotulo: "Lida em voz sintetizada", registrar: "Registrar: lida em voz sintetizada" },
  presencial: { rotulo: "Lida presencialmente", registrar: "Registrar: lida presencialmente" },
  dispensada: { rotulo: "Leitura dispensada", registrar: "Registrar: leitura dispensada" },
};

/** "Ata da 11ª Sessão Ordinária (12/09/2026)". */
export function tituloDaAta(s: SessaoAnteriorOut): string {
  const tipo = TIPOS[s.tipoSessao] ?? s.tipoSessao;
  const quando = s.abertaEm ? ` (${formatarData(s.abertaEm)})` : "";
  return `Ata da ${s.numeroSequencial}ª Sessão ${tipo}${quando}`;
}

/** "Registrada por João Mesa às 18:10." — sem nome resolvido, não inventa. */
export function linhaDoRegistro(l: LeituraAtaRegistradaOut): string {
  const por = l.registradaPorNome ? ` por ${l.registradaPorNome}` : "";
  return `Versão ${l.ataVersao} · registrada${por} às ${formatarHora(l.registradaEm)}.`;
}

const TETO_TRECHO = 220;

/** O texto da ata -> trechos para a voz: um por frase, marcados com o parágrafo (para destacar na tela). Frase longa
 * demais é cortada na vírgula mais próxima do teto (os navegadores cortam falas longas no meio). */
export function trechosParaVoz(texto: string): Trecho[] {
  const saida: Trecho[] = [];
  paragrafos(texto).forEach((p, i) => {
    for (const frase of p.match(/[^.!?;]+[.!?;]*/g) ?? []) {
      let resto = frase.trim();
      while (resto.length > TETO_TRECHO) {
        const corte = resto.lastIndexOf(",", TETO_TRECHO);
        const n = corte > 40 ? corte + 1 : TETO_TRECHO;
        saida.push({ paragrafo: i, texto: resto.slice(0, n).trim() });
        resto = resto.slice(n).trim();
      }
      if (resto) saida.push({ paragrafo: i, texto: resto });
    }
  });
  return saida;
}

export function paragrafos(texto: string): string[] {
  return texto
    .split(/\n[ \t]*\n/)
    .map((p) => p.replace(/\s+/g, " ").trim())
    .filter(Boolean);
}
