// Rota pública do Portal do Cidadão — as LEIS E NORMAS publicadas pela Casa: GET /portal/casa/{ente}/leis.
// Server Component fino (mesmo split das pautas, atas e contas): resolve o nome da Casa no servidor e entrega o filtro
// da URL (`?tipo=&ano=&numero=`) ao componente cliente, que faz o fetch. Rota pública: fora do gate do middleware.

import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { LeisDaCasa } from "../../../../leis-e-normas";
import { lerFiltro } from "@/lib/leis-vista";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaLeisPublicas({
  params,
  searchParams,
}: {
  params: Promise<{ ente: string }>;
  searchParams: Promise<{ tipo?: string | string[]; ano?: string | string[]; numero?: string | string[] }>;
}) {
  const { ente } = await params;
  const filtro = lerFiltro(await searchParams);
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <LeisDaCasa ente={ente} filtro={filtro} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
