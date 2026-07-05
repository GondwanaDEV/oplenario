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
          <EmBreve
            titulo="Em tramitação agora"
            motivo="O destaque de proposições em tramitação (lido da listagem pública real) e os balcões de e-SIC/LGPD chegam nas próximas fatias."
          />
        </div>
      </main>
      <RodapeInstitucional nomeCasa={ente} />
    </>
  );
}
