// A CAMPAINHA da tribuna — o sinal que a Mesa dá quando o tempo do orador se esgota (o costume do Senado e da
// Câmara dos Deputados). Sintetizada com Web Audio, sem arquivo de áudio: três toques de sino de mesa, cada um
// com parciais inarmônicas (o que faz soar "sino" e não "bip") e decaimento exponencial. Sem IO de rede, sem
// estado: quem decide QUANDO tocar é `deveTocarCampainha` (cronometro.ts); quem libera o áudio no navegador
// é `use-campainha.ts`.

/** Fundamental do sino (Hz) e as razões das parciais de um sino de mesa (inarmônicas, como num sino real). */
const FUNDAMENTAL_HZ = 1320;
const PARCIAIS: { razao: number; ganho: number; decaimentoS: number }[] = [
  { razao: 1, ganho: 0.5, decaimentoS: 1.1 },
  { razao: 2.76, ganho: 0.25, decaimentoS: 0.6 },
  { razao: 5.4, ganho: 0.12, decaimentoS: 0.3 },
  { razao: 8.93, ganho: 0.05, decaimentoS: 0.15 },
];

/** Os instantes (s, relativos ao início) dos três toques: "dim — dim — dim". */
export const TOQUES_S = [0, 0.38, 0.76];

/** Duração total do sinal (s): último toque + o decaimento mais longo. */
export const DURACAO_CAMPAINHA_S = TOQUES_S[TOQUES_S.length - 1] + Math.max(...PARCIAIS.map((p) => p.decaimentoS)) + 0.1;

function tocarToque(ctx: AudioContext, destino: AudioNode, inicio: number): void {
  for (const p of PARCIAIS) {
    const osc = ctx.createOscillator();
    const env = ctx.createGain();
    osc.type = "sine";
    osc.frequency.setValueAtTime(FUNDAMENTAL_HZ * p.razao, inicio);
    // ataque de 5 ms (sem estalo) e decaimento exponencial até o silêncio
    env.gain.setValueAtTime(0.0001, inicio);
    env.gain.exponentialRampToValueAtTime(p.ganho, inicio + 0.005);
    env.gain.exponentialRampToValueAtTime(0.0001, inicio + p.decaimentoS);
    osc.connect(env).connect(destino);
    osc.start(inicio);
    osc.stop(inicio + p.decaimentoS + 0.05);
  }
}

/** Toca a campainha no contexto dado. Só agenda os nós: se o contexto estiver suspenso (o navegador ainda não
 * liberou áudio), o som fica mudo — nunca lança. */
export function tocarCampainha(ctx: AudioContext, volume = 0.6): void {
  const mestre = ctx.createGain();
  mestre.gain.value = volume;
  mestre.connect(ctx.destination);
  const t0 = ctx.currentTime + 0.02;
  for (const t of TOQUES_S) tocarToque(ctx, mestre, t0 + t);
}
