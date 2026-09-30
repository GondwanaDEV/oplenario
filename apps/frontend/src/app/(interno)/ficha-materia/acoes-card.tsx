// AcoesCard — card "Ações" do rail (Onda B Slice 3; ganhou "Ver pós-aprovação" na Slice 7 e "Encaminhar às comissões" no
// ADR-0019). Porte de ficha-materia.html:255-271. "Incluir na pauta" (módulo pauta) e "Gerar ficha PDF" (exportação) ainda
// não têm backend fiado — mesma disciplina de BalcaoLgpd (balcao-lgpd.tsx): botões INERTES (disabled + aria-disabled) em
// vez de fingir que funcionam, com um único <EmBreve> honesto explicando o porquê (Global Constraints "sem dado falso").
//
// "Encaminhar às comissões" (ADR-0019, Eixo 6) SUBSTITUI o antigo botão inerte "Distribuir a comissão": abre o diálogo em
// que a secretaria escolhe as comissões e, se quiser, o relator de cada uma; o servidor abre um parecer por comissão. Só a
// secretaria o vê (o backend recusa os demais com 403); `papeis` vem por prop, como `token`, para o card ser testável
// sem <AuthProvider>.
//
// "Ver pós-aprovação" (Onda B Slice 7, spec §4) é ação REAL — só aparece quando `proposicao.aprovada` (Fatia 3 do achado
// T3-A: `estado` é texto livre de template por câmara, nenhuma rota HTTP o move para "aprovada" — o gate antigo era ao
// mesmo tempo frouxo e morto. `aprovada` é o booleano derivado no backend da votação encerrada com resultado `aprovada`,
// ver controllers.clj). É o ponto de entrada da rota /pos-aprovacao/:id; a própria página de destino mostra "Gerar
// autógrafo" se ainda não existir um, evitando um 2º ponto de decisão aqui.

import { useRef, useState } from "react";
import Link from "next/link";
import { EmBreve } from "@/lib/em-breve";
import { comToken } from "@/lib/nav";
import type { ProposicaoDetalheOut } from "@/lib/contrato-legislativo.gen";
import { EncaminharComissoes } from "./encaminhar-comissoes";

const ACOES = ["Incluir na pauta", "Gerar ficha PDF"];

export function AcoesCard({
  proposicao,
  token,
  papeis = [],
  onMudou,
}: {
  proposicao: ProposicaoDetalheOut;
  token: string | null;
  papeis?: string[];
  // chamado depois de encaminhar às comissões — a ficha refaz o GET para a aba Pareceres mostrar os pareceres novos
  onMudou?: () => void;
}) {
  const [encaminhando, setEncaminhando] = useState(false);
  const gatilho = useRef<HTMLButtonElement>(null);
  const ehSecretaria = papeis.includes("secretario");

  function fechar() {
    setEncaminhando(false);
    // o foco volta ao botão que abriu o diálogo
    queueMicrotask(() => gatilho.current?.focus());
  }

  return (
    <div className="card">
      <h3>Ações</h3>
      <div className="card-acoes">
        {proposicao.aprovada && (
          <Link href={comToken(`/pos-aprovacao/${proposicao.id}`, token)} className="btn btn-contorno">
            Ver pós-aprovação
          </Link>
        )}
        {ehSecretaria && (
          <button ref={gatilho} type="button" className="btn btn-contorno" onClick={() => setEncaminhando(true)}>
            Encaminhar às comissões
          </button>
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
          motivo="Incluir na pauta e gerar a ficha em PDF ainda não têm o backend fiado nesta fatia — chegam em fatias futuras (pauta, exportação)."
        />
      </div>
      {encaminhando && (
        <EncaminharComissoes
          proposicaoId={proposicao.id}
          token={token}
          aoFechar={fechar}
          aoEncaminhar={() => onMudou?.()}
        />
      )}
    </div>
  );
}
