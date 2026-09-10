// AcoesCard — card "Ações" do rail (Onda B Slice 3; ganhou "Ver pós-aprovação" na Slice 7). Porte de
// ficha-materia.html:255-271. Nenhuma das 3 ações originais tem backend fiado ainda (Incluir na pauta =
// módulo pauta; Gerar ficha PDF = exportação; Distribuir a comissão = fluxo de distribuição) — mesma
// disciplina de BalcaoLgpd (balcao-lgpd.tsx): botões INERTES (disabled + aria-disabled) em vez de fingir
// que funcionam, com um único <EmBreve> honesto explicando o porquê (Global Constraints "sem dado falso").
//
// "Ver pós-aprovação" (Onda B Slice 7, spec §4) é a ÚNICA ação REAL do card — só aparece quando
// `proposicao.aprovada` (Fatia 3 do achado T3-A: `estado` é texto livre de template por câmara, nenhuma
// rota HTTP o move para "aprovada" — o gate antigo era ao mesmo tempo frouxo e morto. `aprovada` é o
// booleano derivado no backend da votação encerrada com resultado `aprovada`, ver controllers.clj). É o
// ponto de entrada da rota /pos-aprovacao/:id; a própria página de destino mostra "Gerar autógrafo" se
// ainda não existir um, evitando um 2º ponto de decisão aqui.

import Link from "next/link";
import { EmBreve } from "@/lib/em-breve";
import { comToken } from "@/lib/nav";
import type { ProposicaoDetalheOut } from "@/lib/contrato-legislativo.gen";

const ACOES = ["Incluir na pauta", "Gerar ficha PDF", "Distribuir a comissão"];

export function AcoesCard({ proposicao, token }: { proposicao: ProposicaoDetalheOut; token: string | null }) {
  return (
    <div className="card">
      <h3>Ações</h3>
      <div className="card-acoes">
        {proposicao.aprovada && (
          <Link href={comToken(`/pos-aprovacao/${proposicao.id}`, token)} className="btn btn-contorno">
            Ver pós-aprovação
          </Link>
        )}
        {ACOES.map((rotulo) => (
          <button
            key={rotulo}
            className="btn btn-contorno"
            type="button"
            disabled
            aria-disabled="true"
            aria-describedby="ficha-acoes-embreve"
          >
            {rotulo}
          </button>
        ))}
      </div>
      <div id="ficha-acoes-embreve">
        <EmBreve
          titulo="Ações da matéria"
          motivo="Incluir na pauta, gerar a ficha em PDF e distribuir a comissão ainda não têm o backend fiado nesta fatia — chegam em fatias futuras (pauta, exportação, distribuição)."
        />
      </div>
    </div>
  );
}
