// Rota pública do Portal do Cidadão — GET /portal/casa/{ente}/vereadores/{vereadorId} (Onda E fatia 2,
// Task 6). Server Component fino: recebe os params da URL e monta o shell (barra + rodapé, já sob
// (publico)/layout.tsx) + SecaoPerfilVereador (Client Component — o fetch real mora lá, mesmo split de
// materias/[proposicaoId]/page.tsx: um fetch relativo não resolve em Server Component/SSR).
//
// NUNCA `notFound()`: a página sempre renderiza, e quem degrada é a seção. O 404 do backend é fail-closed
// e colapsa "não existe" com "é de outra Casa" — transformá-lo na 404 do Next devolveria ao visitante um
// oráculo de existência que o contrato fechou de propósito.

import { BarraInstitucional } from "../../../../../barra-institucional";
import { RodapeInstitucional } from "../../../../../rodape-institucional";
import { SecaoPerfilVereador } from "../../../../../secao-perfil-vereador";
import { buscarNomeCasa } from "../../../../../../../lib/portal-api";

export default async function PaginaPerfilVereador({
  params,
}: {
  params: Promise<{ ente: string; vereadorId: string }>;
}) {
  const { ente, vereadorId } = await params;
  // buscarNomeCasa é servidor-a-servidor (BACKEND_URL) — evita flash de UUID. null NÃO vira 404: degrada
  // para o slug cru da URL, nunca pior que o comportamento anterior.
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <SecaoPerfilVereador ente={ente} vereadorId={vereadorId} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
