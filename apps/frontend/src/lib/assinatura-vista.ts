// View-model puro da página de assinatura em 2 toques (Onda C4, feature 7.3) — porte de
// produto/design-system/o-plenario/telas/assinatura-2-toques.html. `textoEstado` já vem DERIVADO do
// backend (adapters/out/parecer.clj: rascunho > vigente > vazio) — aqui só traduz pra "dá ou não dá pra
// mostrar a ilha-papel de revisão".
//
// FIX (review CRÍTICO pós-Task 11): `votoRelator` é OPCIONAL no editor de comissão (Onda B Slice 5,
// formulario-parecer.tsx — "Salvar rascunho" não exige voto, só "Emitir parecer" exige) — um parecer pode
// legitimamente chegar aqui com `textoEstado` != "vazio" e `votoRelator` ainda `null`. A página de
// assinatura NUNCA pode FABRICAR um voto (antes de um fix anterior, `?? "favoravel"` substituía
// silenciosamente um voto ausente, fazendo o vereador assinar uma conclusão que nunca escolheu).
//
// FIX (achado docs/20 — a jornada de assinatura era circular): o único jeito de setar o voto era a
// secretaria "Emitir", que JÁ terminaliza o parecer — então um parecer chegava aqui ou sem voto (e a tela
// só dizia "volte ao editor", sem porta de saída) ou já terminal (não assinável). Agora o estado
// "escolher-voto" (texto pronto, voto ainda null) NÃO é um beco: a página oferece ao relator ESCOLHER o
// voto e assinar num ato só (o endpoint POST /meu/pareceres/:id/emissao já aceita `voto-relator` no corpo).
// Isso respeita o "nunca fabricar": a escolha é EXPLÍCITA do relator, não um default silencioso. Quando o
// voto JÁ vem setado (`pronto-pra-revisar`), a tela o mostra como leitura, como antes.

import type { ParecerEditorOut } from "./contrato-legislativo.gen";

export type EstadoAssinatura = "sem-texto" | "escolher-voto" | "pronto-pra-revisar";

export function deriveEstadoAssinatura(dados: ParecerEditorOut | null): EstadoAssinatura {
  if (!dados) return "sem-texto";
  if (dados.textoEstado === "vazio") return "sem-texto";
  if (!dados.votoRelator) return "escolher-voto";
  return "pronto-pra-revisar";
}
