// Rota pública /portal/casa/{ente}/lgpd/novo — formulário do cidadão (ADR-0015). Server Component fino: o nome da Casa
// vem do servidor; a sessão e o envio moram no cliente (FormularioComSessao).

import { BarraInstitucional } from "@/app/(publico)/barra-institucional";
import { RodapeInstitucional } from "@/app/(publico)/rodape-institucional";
import { FormularioComSessao, Hero } from "@/app/(publico)/formularios-cidadao";
import { buscarNomeCasa } from "@/lib/portal-api";
import { direitoDaUrl } from "@/lib/formularios-cidadao";

export default async function Pagina({
  params,
  searchParams,
}: {
  params: Promise<{ ente: string }>;
  searchParams: Promise<{ tipo?: string }>;
}) {
  const { ente } = await params;
  const { tipo } = await searchParams;
  const nomeCasa = (await buscarNomeCasa(ente))?.nomeOficial ?? ente;
  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} />
      <main id="conteudo" className="envelope">
        <Hero
          rotulo="Os meus dados pessoais"
          titulo="Seus dados na Câmara."
          texto="Você pode saber quais dados pessoais seus a Câmara trata, corrigi-los, pedir que sejam eliminados, saber com quem foram compartilhados e revogar um consentimento."
          lei={<>Direitos do titular garantidos pela <b>Lei nº 13.709/2018 (LGPD)</b>, art. 18.</>}
        />
        <FormularioComSessao ente={ente} qual="lgpd" tipoInicial={direitoDaUrl(tipo)} />
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
