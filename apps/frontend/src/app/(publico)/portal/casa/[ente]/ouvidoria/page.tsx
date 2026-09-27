// Rota pública /portal/casa/{ente}/ouvidoria — formulário do cidadão (ADR-0015). Server Component fino: o nome da Casa
// vem do servidor; a sessão e o envio moram no cliente (FormularioComSessao).

import { BarraInstitucional } from "@/app/(publico)/barra-institucional";
import { RodapeInstitucional } from "@/app/(publico)/rodape-institucional";
import { FormularioComSessao, Hero } from "@/app/(publico)/formularios-cidadao";
import { buscarNomeCasa } from "@/lib/portal-api";

export default async function Pagina({
  params,
}: {
  params: Promise<{ ente: string }>;
}) {
  const { ente } = await params;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <Hero
          rotulo="Ouvidoria"
          titulo="Fale com a Câmara."
          texto="Registre uma reclamação, denúncia, sugestão, elogio ou solicitação sobre os serviços da Casa. Sua manifestação é encaminhada à Ouvidoria e tem prazo de resposta."
          lei={<>Direito garantido pela <b>Lei nº 13.460/2017</b> — prazo de resposta de até 30 dias, prorrogável uma vez.</>}
        />
        <FormularioComSessao ente={ente} qual="ouvidoria" />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
