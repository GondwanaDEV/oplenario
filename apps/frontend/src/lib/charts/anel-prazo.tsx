// Anel de prazo (countdown circular) — porta o SVG hand-rolled de paineis-mesa.html (.prazo-anel/.anel-c),
// mesma disciplina do Hemiciclo já existente em sessoes/[id]/plenario (zero lib de chart, skill dataviz:
// sem donut decorativo — este anel É informativo, mede prazo real).

export function arcoDashoffset(diasRestantes: number, diasTotal: number, raio: number): number {
  const circunferencia = 2 * Math.PI * raio;
  if (diasTotal <= 0) return 0;
  const fracaoRestante = Math.max(0, Math.min(1, diasRestantes / diasTotal));
  return circunferencia * fracaoRestante;
}

export function AnelPrazo({
  diasRestantes,
  diasTotal,
  rotulo,
  tamanho = 64,
}: {
  diasRestantes: number;
  diasTotal: number;
  rotulo: string;
  tamanho?: number;
}) {
  const raio = tamanho / 2 - 4;
  const circunferencia = 2 * Math.PI * raio;
  const offset = arcoDashoffset(diasRestantes, diasTotal, raio);
  const centro = tamanho / 2;
  return (
    <div className="anel-c" role="img" aria-label={`${rotulo}: faltam ${diasRestantes} dias.`}>
      <svg viewBox={`0 0 ${tamanho} ${tamanho}`} style={{ transform: "rotate(-90deg)", display: "block" }}>
        <circle className="trilho" cx={centro} cy={centro} r={raio} fill="none" strokeWidth={6} />
        <circle
          className="arco"
          cx={centro}
          cy={centro}
          r={raio}
          fill="none"
          strokeWidth={6}
          strokeLinecap="round"
          strokeDasharray={circunferencia}
          strokeDashoffset={offset}
        />
      </svg>
      <div className="centro">
        <span className="d">{diasRestantes}</span>
        <span className="u">dias</span>
      </div>
    </div>
  );
}
