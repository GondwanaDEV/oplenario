// Rota pública do Portal do Cidadão — GET /portal/casa/{ente}. Server Component: recebe `ente` (o slug
// da câmara na URL) e monta o shell (barra + rodapé) + as seções reais. Origem: esqueleto (Task 0.6,
// Fatia A2.0) -> destaque em tramitação (Task 1.3, A2.1) -> balcões e-SIC/LGPD + navegação cívica (Tasks
// 2.1-2.3, A2.2) -> nome real da Casa (fast-follow pós-A2: `buscarNomeCasa` resolve o UUID da rota pro
// nome oficial ANTES do primeiro paint — falha/[GAP] degrada pro próprio slug, honesto, nunca pior que
// antes deste fix).

import { BarraInstitucional } from "../../../barra-institucional";
import { RodapeInstitucional } from "../../../rodape-institucional";
import { Capa } from "../../../capa";
import { SecaoEmTramitacao } from "../../../secao-em-tramitacao";
import { BalcaoEsic } from "../../../balcao-esic";
import { BalcaoLgpd } from "../../../balcao-lgpd";
import { NavegacaoCivica } from "../../../navegacao-civica";
import { buscarNomeCasa } from "../../../../../lib/portal-api";

export default async function PaginaPortalCidadao({
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
      <BarraInstitucional ente={ente} nomeCasa={nomeCasa} paginaAtual="inicio" />
      <main id="conteudo">
        <Capa />
        <div className="envelope">
          <SecaoEmTramitacao ente={ente} />

          <section className="secao" id="balcoes" aria-labelledby="balcoes-titulo">
            <div className="secao-cabeca">
              <h2 id="balcoes-titulo">Os seus direitos, em dois balcões</h2>
            </div>
            <div className="balcoes">
              {/* key={ente}-prefixado: review A2.2 (item 2) — sem isso, navegar câmara A→B mantém o
                  useState local (protocolo/estado/status) do balcão de A, podendo pintar o resultado de A
                  sobre B. O key força remount por tenant; ver também a guarda de reentrância em
                  balcao-esic.tsx. Prefixo distinto por balcão (review A2.3 item 4): `key={ente}` cru nos
                  dois irmãos colidia (mesma key em siblings) e disparava o warning de key duplicada do
                  React. */}
              <BalcaoEsic key={`esic-${ente}`} ente={ente} />
              <BalcaoLgpd key={`lgpd-${ente}`} ente={ente} />
            </div>
          </section>

          <NavegacaoCivica />
        </div>
      </main>
      <RodapeInstitucional nomeCasa={nomeCasa} />
    </>
  );
}
