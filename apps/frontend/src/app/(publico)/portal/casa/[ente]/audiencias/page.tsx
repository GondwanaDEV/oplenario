// Rota pública /portal/casa/{ente}/audiencias — as audiências públicas da Casa, próximas e realizadas (ADR-0021 A5).
// Server Component fino (mesmo split das pautas e do livro de atas): o nome da Casa vem do servidor; a lista é
// buscada no cliente. O backend só entrega as audiências transmitidas ao público.

import { BarraInstitucional } from "@/app/(publico)/barra-institucional";
import { RodapeInstitucional } from "@/app/(publico)/rodape-institucional";
import { ListaAudiencias } from "@/app/(publico)/audiencias-publicas";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaAudienciasPublicas({ params }: { params: Promise<{ ente: string }> }) {
  const { ente } = await params;
  const casa = await buscarNomeCasa(ente);
  const nomeCasa = casa?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} acessoRestritoDesde={casa?.acessoRestritoDesde} />
      <main id="conteudo" className="envelope">
        <ListaAudiencias ente={ente} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
