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
        <dd>{dados.apensadosTotal > 0 ? dados.apensadosTotal : "nenhum"}</dd>
        <dt>Apresentada</dt>
        <dd className="mono">{formatarData(dados.apresentadaEm)}</dd>
        <dt>Última ação</dt>
        <dd className="mono">{formatarData(dados.ultimaAcaoEm)}</dd>
      </dl>
    </div>
  );
}
