// Lógica pura da tela TRANSCRIÇÃO DA SESSÃO (Faixa A / A.3 da Track IA). A transcrição vive na IA; o core guarda
// o ponteiro (situação, métricas, modelos) e mostra o texto lido de lá. Aqui só se decide o TEXTO da tela:
// quem falou (o Caminho C pode não saber — e diz), quando, e o quanto confiar (Camada de Confiança, §16.8).

import type { TranscricaoPonteiroOut, TrechoTranscricaoOut } from "./contrato-sessoes.gen";
import { formatarData, formatarHora } from "./formatar-data";

/** Segundos desde o início da gravação -> "0:37", "12:05", "1:02:05". */
export function relogio(s: number): string {
  const t = Math.max(0, Math.floor(s));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const seg = String(t % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${seg}` : `${m}:${seg}`;
}

export type BlocoDeFala = { orador: string | null; inicio: number; fim: number; texto: string };

/** Frases seguidas da MESMA pessoa viram um bloco só — é como a ata lê. Sem orador conhecido, o bloco diz isso. */
export function blocosDeFala(trechos: TrechoTranscricaoOut[]): BlocoDeFala[] {
  const blocos: BlocoDeFala[] = [];
  for (const t of trechos) {
    const orador = t.oradorNome ?? null;
    const ultimo = blocos[blocos.length - 1];
    if (ultimo && ultimo.orador === orador) {
      ultimo.fim = t.fim;
      ultimo.texto = `${ultimo.texto} ${t.texto}`.trim();
    } else {
      blocos.push({ orador, inicio: t.inicio, fim: t.fim, texto: t.texto });
    }
  }
  return blocos;
}

/** Aviso de atenção (§16.8, incerteza): falas sem orador identificado ou cobertura abaixo de 80%. null = sem aviso. */
export function avisoDeAtencao(p: TranscricaoPonteiroOut, trechos: TrechoTranscricaoOut[]): string | null {
  const semOrador = trechos.filter((t) => !t.oradorNome).length;
  const cobertura = p.coberturaAtribuida ?? 0;
  if (semOrador === 0 && cobertura >= 0.8) return null;
  const pct = Math.round(cobertura * 100);
  const partes = [`${pct}% da fala tem orador identificado pela palavra concedida na Mesa`];
  if (semOrador > 0) partes.push(semOrador === 1 ? "1 trecho sem orador" : `${semOrador} trechos sem orador`);
  return `Revisar com atenção: ${partes.join("; ")}.`;
}

/** A linha de situação de uma transcrição. */
export function situacao(p: TranscricaoPonteiroOut): string {
  const quando = `${formatarData(p.ocorridoEm)}, ${formatarHora(p.ocorridoEm)}`;
  if (p.situacao === "falhou") {
    const causa = p.categoriaErro === "entrada" ? "o áudio não pôde ser lido" : "a IA não conseguiu transcrever";
    return `A transcrição falhou em ${quando}: ${causa}. Siga pela tela — a gravação está guardada.`;
  }
  const partes = [`Transcrita em ${quando}`];
  if (p.duracaoS != null) partes.push(relogio(p.duracaoS));
  if (p.nTrechos != null) partes.push(p.nTrechos === 1 ? "1 trecho" : `${p.nTrechos} trechos`);
  return partes.join(" · ");
}

/** A situação MAIS RECENTE de cada gravação (o backend já ordena do mais recente para o mais antigo). */
export function atuaisPorGravacao(itens: TranscricaoPonteiroOut[]): TranscricaoPonteiroOut[] {
  const vistos = new Set<string>();
  return itens.filter((i) => (vistos.has(i.segmentoId) ? false : (vistos.add(i.segmentoId), true)));
}
