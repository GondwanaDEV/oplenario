// A frase de "quantos votaram" do placar, a MESMA no telão da Mesa (sessoes/[id]/plenario) e no cockpit do
// vereador ((vereador)/votar). Votação aberta: quantos ainda podem votar. ENCERRADA ninguém mais vai votar —
// o que cabe é dizer quantos não votaram (defeito de 05/10/2026: o cockpit dizia "faltam N" depois de fechar).

/** Sem base de membros (`faltam`/`baseMembros` nulos) não há frase: devolve null. */
export function QuantosVotaram({
  faltam,
  baseMembros,
  encerrada,
}: {
  faltam: number | null;
  baseMembros: number | null;
  encerrada: boolean;
}) {
  if (faltam === null || baseMembros === null) return null;
  return faltam > 0 ? (
    <>
      {encerrada ? "não votaram" : "faltam votar"} <b>{faltam}</b> de <b>{baseMembros}</b>
    </>
  ) : (
    <>
      todos os <b>{baseMembros}</b> votaram
    </>
  );
}
