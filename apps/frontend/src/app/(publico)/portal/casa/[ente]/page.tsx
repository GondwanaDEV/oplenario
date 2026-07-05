// Rota pública do Portal do Cidadão — GET /portal/casa/{ente}. Server Component: recebe `ente` (o slug
// da câmara na URL) e monta o shell (barra + rodapé) + as seções reais. Origem: esqueleto (Task 0.6,
// Fatia A2.0) -> destaque em tramitação (Task 1.3, A2.1) -> balcões e-SIC/LGPD + navegação cívica (Tasks
// 2.1-2.3, A2.2, esta fatia). `ente` ainda não resolve um nome de exibição real (câmara-por-tenant é
// [GAP] de fatia futura) — usamos o próprio slug como rótulo, honesto (não inventa um nome bonito para
// uma câmara que ainda não foi consultada).

import { BarraInstitucional } from "../../../barra-institucional";
import { RodapeInstitucional } from "../../../rodape-institucional";
import { Capa } from "../../../capa";
import { SecaoEmTramitacao } from "../../../secao-em-tramitacao";
import { BalcaoEsic } from "../../../balcao-esic";
import { BalcaoLgpd } from "../../../balcao-lgpd";
import { NavegacaoCivica } from "../../../navegacao-civica";

export default async function PaginaPortalCidadao({
  params,
}: {
  params: Promise<{ ente: string }>;
}) {
  const { ente } = await params;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional nomeCasa={ente} />
      <main id="conteudo">
        <Capa />
        <div className="envelope">
          <SecaoEmTramitacao ente={ente} />

          <section className="secao" id="balcoes" aria-labelledby="balcoes-titulo">
            <div className="secao-cabeca">
              <h2 id="balcoes-titulo">Os seus direitos, em dois balcões</h2>
            </div>
            <div className="balcoes">
              <BalcaoEsic ente={ente} />
              <BalcaoLgpd ente={ente} />
            </div>
          </section>

          <NavegacaoCivica />
        </div>
      </main>
      <RodapeInstitucional nomeCasa={ente} />
    </>
  );
}
