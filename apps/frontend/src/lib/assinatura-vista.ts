// View-model puro da página de assinatura em 2 toques (Onda C4, feature 7.3) — porte de
// produto/design-system/o-plenario/telas/assinatura-2-toques.html. `textoEstado` já vem DERIVADO do
// backend (adapters/out/parecer.clj: rascunho > vigente > vazio) — aqui só traduz pra "dá ou não dá pra
// mostrar a ilha-papel de revisão".

import type { ParecerEditorOut } from "./contrato-legislativo.gen";

export type EstadoAssinatura = "sem-texto" | "pronto-pra-revisar";

export function deriveEstadoAssinatura(dados: ParecerEditorOut | null): EstadoAssinatura {
  if (!dados) return "sem-texto";
  return dados.textoEstado === "vazio" ? "sem-texto" : "pronto-pra-revisar";
}
