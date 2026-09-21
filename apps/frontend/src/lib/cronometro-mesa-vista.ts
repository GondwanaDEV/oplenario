// View-model PURO da EXECUÇÃO da tribuna pela Mesa (§22.6 eixo F/G) — sem React, testável isolado. A camada
// de execução: chamar o inscrito à tribuna (iniciar fala), controlar o cronômetro (pausar/retomar/+tempo/
// aparte) e encerrar a fala. O tempo decorrido e a formatação são de `cronometro.ts` (já usados pelo telão);
// aqui só o que falta para a Mesa OPERAR: opções de tipo de fala, estado de pausa, tempo adicional concedido.

import { nomeTipoFala } from "./rotulos-sessao";
import type { MarcoCronometroOut } from "./contrato-sessoes.gen";

/** Tipos de fala que a Mesa pode iniciar (espelha logic/tipos-fala). "principal" é o padrão da tribuna. */
export const TIPOS_FALA: { valor: string; rotulo: string }[] = [
  "principal",
  "aparte",
  "pela_ordem",
  "questao_de_ordem",
  "explicacao_pessoal",
  "comunicado",
].map((valor) => ({ valor, rotulo: nomeTipoFala(valor) }));

/** true quando a fala está PAUSADA agora: o último marco de pausa/retomada é uma `pausada`. Puro. */
export function estaPausado(marcos: MarcoCronometroOut[]): boolean {
  const ult = [...marcos]
    .filter((m) => m.tipo === "pausada" || m.tipo === "retomada")
    .sort((a, b) => Date.parse(a.ocorridoEm) - Date.parse(b.ocorridoEm))
    .at(-1);
  return ult?.tipo === "pausada";
}

/** Soma dos segundos de `tempo_adicional_concedido` — o tempo extra que a Mesa concedeu à fala. */
export function segundosAdicionaisConcedidos(marcos: MarcoCronometroOut[]): number {
  return marcos
    .filter((m) => m.tipo === "tempo_adicional_concedido")
    .reduce((acc, m) => acc + (m.segundosAdicionais ?? 0), 0);
}

/** Quantos apartes foram concedidos nesta fala (informativo para a Mesa). */
export function apartesConcedidos(marcos: MarcoCronometroOut[]): number {
  return marcos.filter((m) => m.tipo === "aparte_concedido").length;
}
