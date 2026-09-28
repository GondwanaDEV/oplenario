// Rota pública do Portal do Cidadão — os DADOS ABERTOS da Casa (Onda E): GET /portal/casa/{ente}/dados-abertos.
// Server Component fino (mesmo split das demais páginas do portal): o nome da Casa no servidor, o catálogo no
// componente cliente.

import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { SecaoDadosAbertos } from "../../../../secao-dados-abertos";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaDadosAbertos({ params }: { params: Promise<{ ente: string }> }) {
  const { ente } = await params;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <SecaoDadosAbertos ente={ente} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
