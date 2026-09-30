// Rota pública do Portal do Cidadão — a PAUTA OFICIAL das sessões (ADR-0019 fatia 3): GET /portal/casa/{ente}/pautas.
// Server Component fino (mesmo split do livro de atas): resolve o nome da Casa no servidor e entrega a sessão pedida
// na URL (`?sessao=`) ao componente cliente, que faz o fetch. Só sessões públicas e não secretas: o backend filtra.

import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { PautasOficiais } from "../../../../pautas-oficiais";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaPautasPublicas({
  params,
  searchParams,
}: {
  params: Promise<{ ente: string }>;
  searchParams: Promise<{ sessao?: string }>;
}) {
  const { ente } = await params;
  const { sessao } = await searchParams;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <PautasOficiais ente={ente} sessao={sessao ?? null} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
