"use client";

// A LEITURA de uma nota técnica da IA em rascunho: o texto por parágrafo, com cada citação numerada (a marca mostra se o
// sistema conferiu o trecho) e a lista dos dispositivos citados. Compartilhada pela conferência da secretaria
// (/conferencias/:id) e pela nota na fila do jurídico (/juridico/notas/:id, ADR-0019 Eixo 5) — a mesma leitura nas duas.

import { rotuloDaCitacao, paragrafosDoRascunho } from "@/lib/rascunho-ata-vista";
import type { NotaTecnicaOut } from "@/lib/contrato-legislativo.gen";

export function LeituraDaNota({ n }: { n: NotaTecnicaOut }) {
  const paragrafos = paragrafosDoRascunho(n.texto, n.citacoes, n.paragrafosSemFonte);
  return (
    <>
      <article className="conf-papel conf-rascunho" aria-label="Nota técnica em rascunho">
        {paragrafos.map((p, i) => (
          <p key={i} className={p.semFonte ? "conf-sem-fonte" : undefined}>
            {p.semFonte && <span className="conf-selo">sem fonte — confira</span>}
            {p.partes.map((x, j) =>
              x.tipo === "citacao" ? (
                <sup key={j} className={x.citacao?.status === "conferida" ? "conf-cita" : "conf-cita conf-cita-falha"}
                  title={rotuloDaCitacao(x.citacao, "o texto da norma")}>
                  {x.n}
                </sup>
              ) : (
                <span key={j}>{x.texto}</span>
              ),
            )}
          </p>
        ))}
      </article>

      {n.citacoes.length > 0 && (
        <section className="conf-fontes" aria-label="Dispositivos citados">
          <h2>Dispositivos citados</h2>
          <ol>
            {n.citacoes.map((c, i) => (
              <li key={i} className={c.status === "conferida" ? undefined : "conf-cita-falha"}>
                <b>{rotuloDaCitacao(c, "o texto da norma")}</b>
                {c.trecho && <span> — “{c.trecho}”</span>}
              </li>
            ))}
          </ol>
        </section>
      )}
    </>
  );
}
