// Tabuleiro de estágios (board de N colunas) — porta .pipeline-board de paineis-mesa.html. SEM itens de
// proposição individuais aqui (essa versão só tem contagem — a versão com itens reais vem de
// /paineis/tramitacao, componente PipelineLegislativo na Task B7).

export function TabuleiroEstagios({ estagios }: { estagios: { rotulo: string; n: number }[] }) {
  return (
    <div className="pipeline-board">
      {estagios.map((e) => (
        <section className="estagio" key={e.rotulo} aria-label={`${e.rotulo}: ${e.n}`}>
          <div className="estagio-cab">
            <div className="meta">
              <span className="nome">{e.rotulo}</span>
              <span className="n">{e.n}</span>
            </div>
          </div>
        </section>
      ))}
    </div>
  );
}
