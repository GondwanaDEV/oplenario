// FichaCabecalho — o cabeçalho da ficha (.ficha-cab) + a faixa de azulejo HERÓI da tramitação, juntos
// (Onda B Slice 3). Porte de ficha-materia.html:143-175. Reusa formatarNumeroProposicao/
// formatarEspecieProposicao (proposicoes-vista.ts, extraídos nesta fatia p/ este fim) + categorizarSituacao
// (mesmo chip `.chip-${categoria}` da lista) + derivarTramitacao/descreverFaixa (mesmo `estado` livre/
// template-driven já tratado em tramitacao-vista.ts, AzulejoFaixa já construída na A2 — nada de vocabulário
// novo aqui). Meta line honesta: só os campos reais disponíveis em ProposicaoDetalheOut (autoria +
// atualização) — "Relatoria"/"Protocolo geral" do mockup não têm contraparte no nosso modelo ainda e ficam
// FORA (Global Constraint "sem dado falso").

import { formatarNumeroProposicao, formatarEspecieProposicao, categorizarSituacao } from "@/lib/proposicoes-vista";
import { derivarTramitacao, descreverFaixa } from "@/lib/tramitacao-vista";
import { AzulejoFaixa } from "@/lib/charts/azulejo-faixa";
import { formatarData } from "@/lib/formatar-data";
import type { ProposicaoDetalheOut } from "@/lib/contrato-legislativo.gen";

export function FichaCabecalho({ proposicao }: { proposicao: ProposicaoDetalheOut }) {
  const numero = formatarNumeroProposicao(proposicao.tipo, proposicao.sequencial, proposicao.ano);
  const especie = formatarEspecieProposicao(proposicao.tipo);
  const { estagios, rotuloSituacao } = derivarTramitacao(proposicao.estado);
  const categoria = categorizarSituacao(proposicao.estado);

  return (
    <>
      <div className="ficha-cab">
        <div className="topo-linha">
          <span className="num">{numero}</span>
          <span className="especie">{especie}</span>
          <span className={`chip chip-${categoria}`}>{rotuloSituacao}</span>
        </div>
        <h1>{proposicao.ementa}</h1>
        <div className="ficha-meta-linha">
          <span>
            Autoria{" "}
            {proposicao.autorTexto ? <b>{proposicao.autorTexto}</b> : "não informada"}
          </span>
          <span>
            Atualizada em <b>{formatarData(proposicao.atualizadoEm)}</b>
          </span>
        </div>
      </div>

      <section className="tramitacao" aria-label="Tramitação da matéria">
        <div className="rotulo">
          <span className="eyebrow">Onde está a matéria</span>
        </div>
        <AzulejoFaixa estagios={estagios} rotuloAria={descreverFaixa(numero, estagios)} />
      </section>
    </>
  );
}
