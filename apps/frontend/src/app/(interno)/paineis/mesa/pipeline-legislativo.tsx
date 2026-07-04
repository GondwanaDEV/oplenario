"use client";

// "Onde está cada proposição" — porta .pipeline-board de paineis-mesa.html. Com itens reais quando
// tramitacaoItens veio (comItens=true); degrada pra só-contagem (TabuleiroEstagios) quando a chamada de
// detalhe falhou — nunca deriva pra estado de erro de página inteira.

import { BarraSegmentada } from "@/lib/charts/barra-segmentada";
import { TabuleiroEstagios } from "@/lib/charts/tabuleiro-estagios";
import type { MesaVista } from "@/lib/mesa-vista";

const CORES_ESTAGIO: Record<string, string> = {
  protocolada: "#0C5340",
  em_comissao: "#1E5FA8",
  primeiro_turno: "#D9542B",
  segundo_turno: "#16785C",
  sancao: "#E8B23A",
};

export function PipelineLegislativo({ vista }: { vista: MesaVista["pipeline"] }) {
  if (vista.estado === "indisponivel") {
    return (
      <section className="bloco" aria-labelledby="pipeline-titulo">
        <div className="bloco-cabeca"><h2 id="pipeline-titulo">Onde está cada proposição</h2></div>
        <div className="bloco-corpo"><p>Indisponível no momento.</p></div>
      </section>
    );
  }
  const segmentos = vista.porEstado.map((e) => ({ rotulo: e.estado, n: e.n, cor: CORES_ESTAGIO[e.estado] ?? "#888" }));
  return (
    <section className="bloco" aria-labelledby="pipeline-titulo">
      <div className="bloco-cabeca">
        <h2 id="pipeline-titulo">Onde está cada proposição</h2>
      </div>
      <div className="bloco-corpo">
        {vista.comItens ? (
          <div className="pipeline-board">
            {vista.porEstado.map((e) => (
              <section className="estagio" key={e.estado} aria-label={`${e.estado}: ${e.n}`}>
                <div className="estagio-cab">
                  <div className="meta">
                    <span className="nome">{e.estado}</span>
                    <span className="n">{e.n}</span>
                  </div>
                </div>
                <ul className="estagio-lista">
                  {vista.itens
                    .filter((it) => it.estado === e.estado)
                    .slice(0, 3)
                    .map((it) => (
                      <li key={it.proposicaoId}>
                        <span className="ref">{it.tipo.toUpperCase()} {it.sequencial}/{it.ano}</span>
                        <span className="tit">{it.ementa}</span>
                      </li>
                    ))}
                </ul>
              </section>
            ))}
          </div>
        ) : (
          <TabuleiroEstagios estagios={vista.porEstado.map((e) => ({ rotulo: e.estado, n: e.n }))} />
        )}
        <BarraSegmentada segmentos={segmentos} rotuloGeral={`Carga por estágio · ${vista.porEstado.reduce((a, e) => a + e.n, 0)} proposições ativas`} />
      </div>
    </section>
  );
}
