// Barra de distribuição segmentada — porta .barra-dist de paineis-mesa.html. Composição honesta da carga
// (skill dataviz): larguras proporcionais REAIS, nunca estilizadas p/ parecerem mais uniformes.

export function largurasPercentuais<T extends { n: number }>(segmentos: T[]): (T & { percentual: number })[] {
  const total = segmentos.reduce((acc, s) => acc + s.n, 0);
  return segmentos.map((s) => ({ ...s, percentual: total > 0 ? (100 * s.n) / total : 0 }));
}

export function BarraSegmentada({
  segmentos,
  rotuloGeral,
}: {
  segmentos: { rotulo: string; n: number; cor: string }[];
  rotuloGeral: string;
}) {
  const comPercentual = largurasPercentuais(segmentos);
  const descricao = segmentos.map((s) => `${s.rotulo} ${s.n}`).join(", ");
  return (
    <div className="distribuicao">
      <p className="rotulo-d">
        <span>{rotuloGeral}</span>
      </p>
      <div className="barra-dist" role="img" aria-label={`${rotuloGeral}: ${descricao}.`}>
        {comPercentual.map((s) => (
          <span key={s.rotulo} style={{ width: `${s.percentual}%`, background: s.cor }}>
            {s.percentual > 8 ? s.n : ""}
          </span>
        ))}
      </div>
      <div className="dist-legenda">
        {segmentos.map((s) => (
          <span key={s.rotulo}>
            <i style={{ background: s.cor }} aria-hidden="true" />
            {s.rotulo} · {s.n}
          </span>
        ))}
      </div>
    </div>
  );
}
