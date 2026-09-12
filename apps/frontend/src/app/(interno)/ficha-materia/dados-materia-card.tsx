// DadosMateriaCard — card "Dados da matéria" do rail (Onda B Slice 3). Porte de ficha-materia.html:240-250
// (subconjunto real: só os campos que `derivarDadosMateria` de fato deriva do dado — "Regime"/"Turnos" da
// tela-fonte são estáticos de demo, sem contraparte no nosso modelo, e ficam FORA — honestidade > fidelidade
// pixel-a-pixel ao mockup, Global Constraint "sem dado falso").

import type { DadosMateriaVista } from "@/lib/ficha-materia-vista";
import { formatarData } from "@/lib/formatar-data";

export function DadosMateriaCard({ dados }: { dados: DadosMateriaVista }) {
  return (
    <div className="card">
      <h3>Dados da matéria</h3>
      <dl className="dl">
        <dt>Situação</dt>
        <dd>{dados.situacao}</dd>
        <dt>Apensados</dt>
        <dd>
          {dados.apensadosTotal > 0 ? dados.apensadosTotal : "nenhum"}
          {/* fatia "truncamento-familia": o servidor manda BOOLEANO (`apensadasTruncado`), nunca um
              total à parte — "+" é honesto (há mais que o número mostrado) sem fingir saber quantas. */}
          {dados.apensadosTotal > 0 && dados.apensadasTruncado && (
            <span title="Há mais apensadas do que as exibidas aqui">+</span>
          )}
        </dd>
        <dt>Apresentada</dt>
        <dd className="mono">
          {/* fatia "truncamento-familia": sob corte, a data mais antiga sobrevivente NÃO é a apresentação
              real (o corte mantém as mais recentes) — "anterior a" é o fato honesto que temos: a data real
              é mais antiga que esta. */}
          {dados.apresentadaEmIncerta ? (
            <span title="A tramitação está cortada no registro mais antigo mantido; a data real de apresentação é anterior a esta">
              anterior a {formatarData(dados.apresentadaEm)}
            </span>
          ) : (
            formatarData(dados.apresentadaEm)
          )}
        </dd>
        <dt>Última ação</dt>
        <dd className="mono">{formatarData(dados.ultimaAcaoEm)}</dd>
      </dl>
    </div>
  );
}
