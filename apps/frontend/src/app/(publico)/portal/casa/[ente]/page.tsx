// Esqueleto da rota pública do Portal do Cidadão (Task 0.6, Fatia A2.0) — GET /portal/casa/{ente}.
// Server Component: recebe `ente` (o slug da câmara na URL) e monta o shell (barra + rodapé), com um
// <EmBreve> provisório no corpo — o conteúdo real (destaque/balcões/navegação cívica) entra nas
// Fatias A2.1-A2.3. `ente` ainda não resolve um nome de exibição real (isso é a Fatia A2.1, via
// backend) — nesta fatia usamos o próprio slug como rótulo temporário, honesto (não inventa um nome
// bonito para uma câmara que ainda não foi consultada).

import { EmBreve } from "@/lib/em-breve";
import { BarraInstitucional } from "../../../barra-institucional";
import { RodapeInstitucional } from "../../../rodape-institucional";
import { Capa } from "../../../capa";
import { SecaoEmTramitacao } from "../../../secao-em-tramitacao";
import { BalcaoEsic } from "../../../balcao-esic";
import { BalcaoLgpd } from "../../../balcao-lgpd";

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

          <EmBreve
            titulo="Navegação cívica"
            motivo="A navegação para Sessões/Transparência/Ouvidoria/Dados abertos/Agenda/Carta de Serviços chega na próxima task (2.3) desta fatia."
          />
        </div>
      </main>
      <RodapeInstitucional nomeCasa={ente} />
    </>
  );
}
