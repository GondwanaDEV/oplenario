"use client";

// "Onde está cada proposição" — porta .pipeline-board de paineis-mesa.html. Com itens reais quando
// tramitacaoItens veio (comItens=true); degrada pra só-contagem (TabuleiroEstagios) quando a chamada de
// detalhe falhou — nunca deriva pra estado de erro de página inteira.

import { BarraSegmentada } from "@/lib/charts/barra-segmentada";
import { TabuleiroEstagios } from "@/lib/charts/tabuleiro-estagios";
import { derivarRef } from "@/lib/materia-vista";
import type { MesaVista } from "@/lib/mesa-vista";
import { derivarTramitacao } from "@/lib/tramitacao-vista";

// O painel mostrava a CHAVE do estado ("em_comissoes", que o CSS ainda punha em maiuscula ->
// "EM_COMISSOES") e o tipo cru ("PROJETO_LEI"). Os tradutores ja existiam e ja sao o que o Portal
// do Cidadao mostra ao publico — `derivarTramitacao().rotuloSituacao` e `derivarRef()`. Reusa-los
// aqui mantem UMA fonte de rotulo por conceito, em vez de um segundo mapa a divergir com o tempo.
const rotularEstagio = (estado: string) => derivarTramitacao(estado).rotuloSituacao;

const CORES_ESTAGIO: Record<string, string> = {
  protocolada: "#2C5638",
  em_comissao: "#3F6E92",
  primeiro_turno: "#C0693F",
  segundo_turno: "#4E8259",
  sancao: "#CFA65C",
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
  const segmentos = vista.porEstado.map((e) => ({ rotulo: rotularEstagio(e.estado), n: e.n, cor: CORES_ESTAGIO[e.estado] ?? "#888" }));
  return (
    <section className="bloco" aria-labelledby="pipeline-titulo">
      <div className="bloco-cabeca">
        <h2 id="pipeline-titulo">Onde está cada proposição</h2>
      </div>
      <div className="bloco-corpo">
        {vista.comItens ? (
          <div className="pipeline-board">
            {vista.porEstado.map((e) => (
              <section className="estagio" key={e.estado} aria-label={`${rotularEstagio(e.estado)}: ${e.n}`}>
                <div className="estagio-cab">
                  <div className="meta">
                    <span className="nome">{rotularEstagio(e.estado)}</span>
                    <span className="n">{e.n}</span>
                  </div>
                </div>
                <ul className="estagio-lista">
                  {vista.itens
                    .filter((it) => it.estado === e.estado)
                    .slice(0, 3)
                    .map((it) => (
                      <li key={it.proposicaoId}>
                        <span className="ref">{derivarRef(it)}</span>
                        <span className="tit">{it.ementa}</span>
                      </li>
                    ))}
                </ul>
              </section>
            ))}
          </div>
        ) : (
          <TabuleiroEstagios estagios={vista.porEstado.map((e) => ({ rotulo: rotularEstagio(e.estado), n: e.n }))} />
        )}
        <BarraSegmentada segmentos={segmentos} rotuloGeral={`Carga por estágio · ${vista.porEstado.reduce((a, e) => a + e.n, 0)} proposições ativas`} />
      </div>
    </section>
  );
}
