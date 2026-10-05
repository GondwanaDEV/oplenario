// Rota pública do Portal do Cidadão — a LISTA dos vereadores em exercício: GET /portal/casa/{ente}/vereadores.
// Server Component fino (mesmo split das pautas, das contas e dos dados abertos): o nome da Casa no servidor, a lista
// no componente cliente, que faz o fetch. Cada nome leva ao perfil público (`./[vereadorId]`).

import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { ListaVereadores } from "../../../../lista-vereadores";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaListaVereadores({ params }: { params: Promise<{ ente: string }> }) {
  const { ente } = await params;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <ListaVereadores ente={ente} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
