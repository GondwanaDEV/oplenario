// Rota pública do Portal do Cidadão — as CONTAS do Prefeito e da Câmara (ADR-0021 Parte B): GET /portal/casa/{ente}/contas.
// Server Component fino (mesmo split das pautas e do livro de atas): resolve o nome da Casa no servidor e entrega ao
// componente cliente, que faz o fetch. Os documentos baixados são só os do Tribunal: o backend filtra.

import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { ContasPublicas } from "../../../../contas-publicas";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaContasPublicas({ params }: { params: Promise<{ ente: string }> }) {
  const { ente } = await params;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <ContasPublicas ente={ente} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
