// View-model puro da página de assinatura em 2 toques (Onda C4, feature 7.3) — porte de
// produto/design-system/o-plenario/telas/assinatura-2-toques.html. `textoEstado` já vem DERIVADO do
// backend (adapters/out/parecer.clj: rascunho > vigente > vazio) — aqui só traduz pra "dá ou não dá pra
// mostrar a ilha-papel de revisão".
//
// FIX (review CRÍTICO pós-Task 11): `votoRelator` é OPCIONAL no editor de comissão (Onda B Slice 5,
// formulario-parecer.tsx — "Salvar rascunho" não exige voto, só "Emitir parecer" exige) — um parecer pode
// legitimamente chegar aqui com `textoEstado` != "vazio" e `votoRelator` ainda `null`. A página de
// assinatura NUNCA pode fabricar um voto (ver page.tsx `confirmar()` — antes deste fix, `?? "favoravel"`
// substituía silenciosamente um voto ausente, fazendo o vereador assinar uma conclusão que nunca escolheu:
// ato irreversível). Por isso `deriveEstadoAssinatura` agora tem 3 saídas — "sem-voto" é um estado
// DISTINTO de "sem-texto" (não reusa o mesmo, porque a mensagem honesta é diferente: já há texto pra ler,
// só falta a conclusão) — e o CTA "Revisar e assinar" só aparece em "pronto-pra-revisar", que agora
// GARANTE `votoRelator` truthy.

import type { ParecerEditorOut } from "./contrato-legislativo.gen";

export type EstadoAssinatura = "sem-texto" | "sem-voto" | "pronto-pra-revisar";

export function deriveEstadoAssinatura(dados: ParecerEditorOut | null): EstadoAssinatura {
  if (!dados) return "sem-texto";
  if (dados.textoEstado === "vazio") return "sem-texto";
  if (!dados.votoRelator) return "sem-voto";
  return "pronto-pra-revisar";
}
