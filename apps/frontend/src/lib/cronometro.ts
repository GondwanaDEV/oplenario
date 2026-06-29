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
