// IdentidadeLexmlCard — card "Identidade canônica (LexML)" do rail (Onda B Slice 3). Porte de
// ficha-materia.html:251-254. `urnLex` vem direto de ProposicaoDetalheOut (já real, sem espera de fatia
// futura — §22.4 eixo H).

export function IdentidadeLexmlCard({ urnLex }: { urnLex: string }) {
  return (
    <div className="card">
      <h3>Identidade canônica (LexML)</h3>
      <p className="urn">{urnLex}</p>
    </div>
  );
}
