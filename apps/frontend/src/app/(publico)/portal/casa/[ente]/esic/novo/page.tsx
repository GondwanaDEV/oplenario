// Rota pública /portal/casa/{ente}/esic/novo — formulário do cidadão (ADR-0015). Server Component fino: o nome da Casa
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
  const casa = await buscarNomeCasa(ente);
  const nomeCasa = casa?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      {/* ADR-0018: a Casa suspensa segue recebendo o pedido — a faixa diz que o sistema está restrito */}
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} acessoRestritoDesde={casa?.acessoRestritoDesde} />
      <main id="conteudo" className="envelope">
        <Hero
          rotulo="Acesso à informação"
          titulo="Peça uma informação à Câmara."
          texto="Qualquer pessoa pode pedir informações públicas, sem precisar dizer por quê. O pedido tem prazo de resposta e, se a resposta não servir, cabe recurso."
          lei={<>Direito garantido pela <b>Lei nº 12.527/2011 (LAI)</b> — resposta em até 20 dias, prorrogáveis por mais 10.</>}
        />
        <FormularioComSessao ente={ente} qual="esic" />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
