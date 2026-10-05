// Rota pública do Portal do Cidadão — a FICHA de uma lei ou norma: GET /portal/casa/{ente}/leis/{normaId}.
// Server Component fino (mesmo split da lista): resolve o nome da Casa e entrega o id ao componente cliente, que faz
// o fetch. Rota pública: fora do gate do middleware.

import { BarraInstitucional } from "../../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../../rodape-institucional";
import { FichaDaNorma } from "../../../../../leis-e-normas";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaFichaDaNorma({ params }: { params: Promise<{ ente: string; normaId: string }> }) {
  const { ente, normaId } = await params;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <FichaDaNorma ente={ente} normaId={normaId} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
