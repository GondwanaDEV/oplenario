// Rota pública do Portal do Cidadão — GET /portal/casa/{ente}/materias/{proposicaoId} (Task 3.2, Fatia
// A2.3, o click-through de destaque-tramitacao.tsx/mais-tramitacao.tsx). Server Component fino: recebe
// os params da URL e monta o shell (barra + rodapé, já sob (publico)/layout.tsx) + SecaoFicha (Client
// Component — o fetch real mora lá, mesmo split de page.tsx/secao-em-tramitacao.tsx de A2.1: um fetch
// relativo não resolve em Server Component/SSR).

import { BarraInstitucional } from "../../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../../rodape-institucional";
import { SecaoFicha } from "../../../../../secao-ficha";

export default async function PaginaFichaMateria({
  params,
}: {
  params: Promise<{ ente: string; proposicaoId: string }>;
}) {
  const { ente, proposicaoId } = await params;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={ente} />
      <main id="conteudo" className="envelope">
        <SecaoFicha ente={ente} proposicaoId={proposicaoId} />
      </main>
      <RodapeInstitucional nomeCasa={ente} />
    </>
  );
}
