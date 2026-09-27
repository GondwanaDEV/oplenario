// Cronômetro client-side da tribuna: o servidor emite só os MARCOS (iniciou-em + pausada/retomada); o
// display por segundo é recomputado aqui (§22.6 eixo G). Pura e testada (cronometro.test.ts) — a armadilha
// de pausa/retomada é a mesma que o backend trata em sessoes/logic (tempo efetivo).

import type { MarcoCronometro } from "./plenario-reducer";

/** Segundos efetivamente decorridos desde `iniciouEm` até `agoraMs`, descontando intervalos de pausa. */
export function segundosDecorridos(iniciouEm: string, marcos: MarcoCronometro[], agoraMs: number): number {
  const inicio = Date.parse(iniciouEm);
  const pausas = marcos
    .filter((m) => m.tipo === "pausada" || m.tipo === "retomada")
    .sort((a, b) => Date.parse(a.ocorridoEm) - Date.parse(b.ocorridoEm));

  let pausadoMs = 0;
  let pausaAberta: number | null = null;
  for (const m of pausas) {
    const ts = Date.parse(m.ocorridoEm);
    if (m.tipo === "pausada" && pausaAberta === null) pausaAberta = ts;
    else if (m.tipo === "retomada" && pausaAberta !== null) {
      pausadoMs += ts - pausaAberta;
      pausaAberta = null;
    }
  }
  // se ainda está pausada, congela: conta só até o início da pausa em aberto
  const fim = pausaAberta !== null ? pausaAberta : agoraMs;
  return Math.max(0, Math.floor((fim - inicio - pausadoMs) / 1000));
}

/** Formata segundos como MM:SS (ou HH:MM:SS se passar de 1h). */
export function formatarTempo(s: number): string {
  const dd = (n: number) => String(n).padStart(2, "0");
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const seg = s % 60;
  return h > 0 ? `${dd(h)}:${dd(m)}:${dd(seg)}` : `${dd(m)}:${dd(seg)}`;
}

/** Como está o tempo da fala em relação ao limite. `sem-limite` = a fala não tem tempo concedido (a Casa não
 * configurou o regimental e a Mesa não informou): o cronômetro só conta, como antes da mig 0081. */
export type SituacaoTempo = "sem-limite" | "correndo" | "ultimo-minuto" | "esgotado";

export interface TempoDaFala {
  /** Limite efetivo (s) = concedido + todo `tempo_adicional_concedido`. `null` sem limite. */
  limite: number | null;
  /** Segundos que faltam (nunca negativo). `null` sem limite. */
  restante: number | null;
  /** Quanto já passou do limite (s). 0 enquanto não esgotou. */
  excedido: number;
  situacao: SituacaoTempo;
}

/** A partir de quantos segundos restantes a fala entra no "último minuto" (aviso visual, sem som). */
export const SEGUNDOS_ULTIMO_MINUTO = 60;

/** O tempo da fala contra o limite. O limite viaja no `fala.iniciada` (o servidor o fotografa ao iniciar) e
 * cresce com os marcos `tempo_adicional_concedido` — o "+1 min" da Mesa. Tudo client-side, a partir dos marcos
 * (§22.6 eixo G): o servidor não emite "esgotou". Limite não-positivo/não-finito = sem limite (nunca inventa
 * um "esgotado"). Pura. */
export function tempoDaFala(
  concedidoSegundos: number | null | undefined,
  marcos: MarcoCronometro[],
  decorrido: number,
): TempoDaFala {
  if (typeof concedidoSegundos !== "number" || !Number.isFinite(concedidoSegundos) || concedidoSegundos <= 0) {
    return { limite: null, restante: null, excedido: 0, situacao: "sem-limite" };
  }
  const adicionais = marcos
    .filter((m) => m.tipo === "tempo_adicional_concedido")
    .reduce((acc, m) => acc + (typeof m.segundosAdicionais === "number" && m.segundosAdicionais > 0 ? m.segundosAdicionais : 0), 0);
  const limite = concedidoSegundos + adicionais;
  const restante = Math.max(0, limite - decorrido);
  const excedido = Math.max(0, decorrido - limite);
  const situacao: SituacaoTempo =
    restante === 0 ? "esgotado" : restante <= SEGUNDOS_ULTIMO_MINUTO ? "ultimo-minuto" : "correndo";
  return { limite, restante, excedido, situacao };
}

/** O relógio que TV e Mesa mostram: com limite, o RESTANTE (contagem regressiva) e, esgotado, "+excedido";
 * sem limite, o decorrido. Uma função só para as duas telas nunca discordarem do número. Pura. */
export function relogioDaFala(tempo: TempoDaFala, decorrido: number): string {
  if (tempo.situacao === "sem-limite") return formatarTempo(decorrido);
  if (tempo.situacao === "esgotado") return `+${formatarTempo(tempo.excedido)}`;
  return formatarTempo(tempo.restante ?? 0);
}

/** O que a tela viu da fala no último tique: qual fala e se estava esgotada. */
export interface ObservacaoTempo {
  falaId: string;
  esgotado: boolean;
}

/** A campainha toca na TRANSIÇÃO para esgotado que a tela viu acontecer: a MESMA fala, antes não esgotada,
 * agora esgotada. Abrir (ou recarregar) a TV com a fala já estourada não toca — senão cada reload tocaria. Depois
 * do "+1 min", a fala volta a correr e, se esgotar de novo, toca de novo. Pura. */
export function deveTocarCampainha(anterior: ObservacaoTempo | null, atual: ObservacaoTempo | null): boolean {
  if (!anterior || !atual) return false;
  return anterior.falaId === atual.falaId && !anterior.esgotado && atual.esgotado;
}
