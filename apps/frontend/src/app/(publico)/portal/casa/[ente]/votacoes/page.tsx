// Rota pública do Portal do Cidadão — as VOTAÇÕES da Câmara: GET /portal/casa/{ente}/votacoes.
// Server Component fino (mesmo split da pauta e do livro de atas): resolve o nome da Casa no servidor e entrega a
// votação aberta (`?votacao=`) e a página (`?pagina=`) ao componente cliente, que faz o fetch. Só votação encerrada
// de sessão pública: o backend filtra; a de sessão secreta simplesmente não existe aqui.

import { BarraInstitucional } from "../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../rodape-institucional";
import { VotacoesPublicas } from "../../../../votacoes-publicas";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function PaginaVotacoesPublicas({
  params,
  searchParams,
}: {
  params: Promise<{ ente: string }>;
  searchParams: Promise<{ votacao?: string; pagina?: string }>;
}) {
  const { ente } = await params;
  const { votacao, pagina } = await searchParams;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  const n = Number.parseInt(pagina ?? "1", 10);
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <VotacoesPublicas ente={ente} votacao={votacao ?? null} pagina={Number.isFinite(n) && n > 1 ? n : 1} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
