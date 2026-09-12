"use client";

// /acompanhamentos (fatia "demo-tres-consertos" #3) — a PRIMEIRA tela da superfície autenticada do
// cidadão. Achado ao vivo (Daouda, 12/09/2026): logada como Roberta Costa Aguiar (vínculo `cidadao`, sem
// papel), GET /portal/acompanhamentos já respondia 200 com 3 matérias reais — mas nenhuma page.tsx do FE
// chamava essa rota; a única tela que serviria algo dela (/notificacoes) vive sob o grupo (vereador), cujo
// guard bloqueia quem não tem o papel "vereador" (ela nunca tem). Este grupo ((cidadao)) e esta rota são
// NOVOS — não reabrem o guard do vereador nem tocam nele.
//
// Lista+total (regra da casa, frente "truncamento-familia"): o par {acompanhamentos, acompanhamentos-total}
// já é o contrato do backend — "Mostrando N de M" nunca esconde um teto atingido.

import { useAuth } from "@/lib/auth";
import { useMeusAcompanhamentos, type MeusAcompanhamentosOut } from "@/lib/use-meus-acompanhamentos";
import { derivarMeusAcompanhamentosVista } from "@/lib/meus-acompanhamentos-vista";
import { formatarDataSimples } from "@/lib/formatar-data";
import "./acompanhamentos.css";

export default function PaginaAcompanhamentos() {
  const { token } = useAuth();
  const { dados, estado } = useMeusAcompanhamentos(token);

  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <div id="conteudo" className="envelope ac-pagina">
        <header className="ac-cab">
          <p className="eyebrow">Portal do Cidadão</p>
          <h1>Minhas matérias acompanhadas</h1>
        </header>

        {estado === "carregando" && (
          <p className="ac-nota" aria-live="polite">
            Carregando…
          </p>
        )}

        {estado === "erro" && (
          <p className="ac-nota ac-erro" role="alert">
            Não foi possível carregar os seus acompanhamentos agora. Tente novamente em instantes.
          </p>
        )}

        {estado === "pronto" && dados && <ListaAcompanhamentos dados={dados} />}
      </div>
    </>
  );
}

function ListaAcompanhamentos({ dados }: { dados: MeusAcompanhamentosOut }) {
  const linhas = derivarMeusAcompanhamentosVista(dados.acompanhamentos);

  if (linhas.length === 0) {
    return (
      <p className="ac-nota" aria-live="polite">
        Você ainda não acompanha nenhuma matéria. Ao ver uma proposição no portal público, use
        &ldquo;Acompanhar&rdquo; para recebê-la aqui.
      </p>
    );
  }

  return (
    <>
      {/* nunca esconde o teto (regra da casa): a contagem é sempre dita, atingido ou não. */}
      <p className="ac-total" aria-live="polite">
        Mostrando {linhas.length} de {dados.acompanhamentosTotal} matérias que você acompanha.
      </p>
      <ul className="ac-lista">
        {linhas.map((linha) => (
          <li key={linha.proposicaoId} className="ac-item">
            <div className="ac-item-topo">
              <span className={linha.situacao ? `chip chip-${linha.situacao.categoria}` : "chip chip-neutro"}>
                {linha.situacao ? linha.situacao.rotulo : "Indisponível"}
              </span>
              <span className="ac-desde">seguindo desde {formatarDataSimples(linha.seguidoEm)}</span>
            </div>
            <p className="ac-titulo">{linha.titulo}</p>
            {linha.ementa && <p className="ac-ementa">{linha.ementa}</p>}
            {linha.indisponivel && (
              <p className="ac-indisponivel">
                Os detalhes desta matéria ainda não chegaram ao portal — tente novamente mais tarde.
              </p>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
