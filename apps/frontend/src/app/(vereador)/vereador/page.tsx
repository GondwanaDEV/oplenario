"use client";

// A home do vereador — estado FORA DE SESSÃO (Onda C1, Task 7). Porte 1:1 de
// produto/design-system/o-plenario/telas/vereador-app.html (bloco `.so-fora` + o comum `.card`/`.secao-tit`),
// só o estado calmo: SEM votação ao vivo, SEM placar (isso é a C3, estado `.so-em-sessao`). Composição:
// useAuth (token, já resolvido pelo GuardVereador do layout) + useMeuPainel (Task 7.2) + useAcusarCiencia
// (Task 7.2) + derivarHome (Task 6, view-model puro). `sessoes` fica `[]` nesta fatia — não há endpoint de
// listagem de sessões ainda (carry documentado em meu-painel-vista.ts); `proximaSessao` é sempre `null` até
// esse carry fechar, então o card "próxima sessão" mostra o estado honesto "sem sessão agendada" em vez de
// fingir dado que não existe.

import { useAuth } from "@/lib/auth";
import { useMeuPainel } from "@/lib/use-meu-painel";
import { useAcusarCiencia } from "@/lib/use-acusar-ciencia";
import { derivarHome, type HomeVereadorVista } from "@/lib/meu-painel-vista";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import type { CienciaPendenteOut, ProposicaoResumoMeuPainelOut } from "@/lib/contrato-legislativo.gen";
import "./vereador-home.css";

export default function PaginaHomeVereador() {
  const { token } = useAuth();
  const { dados, estado, recarregar } = useMeuPainel(token);
  const { acusar, estado: estadoCiencia, erro: erroCiencia } = useAcusarCiencia(token);
  // `vista.meusPareceres` (Task 6) é lido pelo view-model e testado, mas esta tela NAO renderiza uma seção
  // própria para ele (review MEDIUM react, achado pós-merge) — `vereador-app.html` (fonte do design) também
  // não mostra "meus pareceres" no estado fora-de-sessão; registrado como CARRY explícito no plano da fatia,
  // não fingido como coberto.
  const vista = derivarHome(dados, []);

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar sua home</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }
  if (estado === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }

  async function darCiencia(ciencia: CienciaPendenteOut) {
    try {
      await acusar({ eventoRef: ciencia.parecerId, tipo: "parecer_publicado" });
      await recarregar();
    } catch {
      // erro já fica exposto via `erroCiencia` (useAcusarCiencia seta `estado`/`erro`) — aqui só evita a
      // unhandled promise rejection; o botão volta a ficar habilitado (estadoCiencia sai de "enviando").
    }
  }

  return (
    <>
      {/* sr-only: a home não tem heading visível de nível 1 no estado "pronto" — sem isso a árvore de
          headings pula direto pro h2, quebrando a navegação estrutural de leitor de tela. */}
      <h1 className="sr-only">Sua home</h1>

      <section className="fora-hero" aria-label="Fora de sessão">
        <span className="estado">
          <span className="dot" aria-hidden="true" />
          Sem sessão agora
        </span>
        <h2>Tudo em dia.</h2>
        <p className="resumo">
          Nenhuma votação aberta.{" "}
          {vista.ciencias.length > 0 && (
            <>
              Você tem <b>{vista.ciencias.length}</b> {vista.ciencias.length === 1 ? "ciência" : "ciências"} a
              registrar
              {vista.minhasProposicoes.length > 0 ? " e " : ". "}
            </>
          )}
          {vista.minhasProposicoes.length > 0 && (
            <>
              <b>{vista.minhasProposicoes.length}</b>{" "}
              {vista.minhasProposicoes.length === 1 ? "proposição" : "proposições"} em andamento.
            </>
          )}
        </p>
        <div className="fora-prox">
          <ProximaSessaoResumo sessao={vista.proximaSessao} />
        </div>
      </section>

      {vista.ciencias.length > 0 && (
        <section aria-live="polite" aria-label="Para sua ciência">
          <h2 className="secao-tit">Para sua ciência</h2>
          {erroCiencia && (
            <p role="status" className="erro-inline">
              Não foi possível registrar a ciência: {erroCiencia}
            </p>
          )}
          {vista.ciencias.map((c) => (
            <CartaoCiencia key={c.parecerId} ciencia={c} onDarCiencia={() => darCiencia(c)} enviando={estadoCiencia === "enviando"} />
          ))}
        </section>
      )}

      <h2 className="secao-tit">Suas proposições</h2>
      {vista.minhasProposicoes.length === 0 ? (
        <p className="vazio">Nenhuma proposição sua ainda.</p>
      ) : (
        vista.minhasProposicoes.map((p) => <CartaoProposicao key={p.id} proposicao={p} />)
      )}
    </>
  );
}

function ProximaSessaoResumo({ sessao }: { sessao: HomeVereadorVista["proximaSessao"] }) {
  // Carry documentado (meu-painel-vista.ts): sem endpoint de listagem de sessões ainda, `derivarHome` é
  // sempre chamado com `sessoes=[]` nesta fatia — então `sessao` é sempre `null` na prática, e o ramo real
  // abaixo fica sem cobertura de dado real até esse carry fechar. O componente já toma a prop (em vez de
  // hard-codar o vazio) para que, no dia em que o carry fechar, baste passar a lista real — sem precisar
  // tocar este componente de novo.
  if (!sessao) {
    return (
      <>
        <div className="cal" aria-hidden="true">
          <b>—</b>
          <span>—</span>
        </div>
        <div className="info">
          <b>Nenhuma sessão agendada</b>
          <span>Ainda não há próxima sessão publicada.</span>
        </div>
      </>
    );
  }
  const data = sessao["agendada-para"] ? new Date(sessao["agendada-para"]) : null;
  return (
    <>
      <div className="cal" aria-hidden="true">
        <b>{data ? data.getDate() : "—"}</b>
        <span>{data ? data.toLocaleDateString("pt-BR", { month: "short" }) : "—"}</span>
      </div>
      <div className="info">
        <b>{sessao["tipo-sessao"] ?? "Próxima sessão"}</b>
        <span>{data ? data.toLocaleDateString("pt-BR", { weekday: "long", hour: "2-digit", minute: "2-digit" }) : ""}</span>
      </div>
    </>
  );
}

function CartaoCiencia({
  ciencia,
  onDarCiencia,
  enviando,
}: {
  ciencia: CienciaPendenteOut;
  onDarCiencia: () => void;
  enviando: boolean;
}) {
  return (
    <article className="ciencia">
      <span className="ci-top">Parecer publicado</span>
      <h3>
        A comissão deu parecer sobre o seu{" "}
        <span className="num-inline">{formatarNumeroProposicao(ciencia.tipo, ciencia.sequencial, ciencia.ano)}</span>
      </h3>
      <p className="meta">
        {ciencia.ementa}. Acusar ciência fica registrado com data e hora — é a prova de que você foi
        notificada.
      </p>
      <div className="ci-acao">
        <button className="btn btn-primaria btn-mini" type="button" onClick={onDarCiencia} disabled={enviando}>
          Dar ciência
        </button>
      </div>
    </article>
  );
}

function CartaoProposicao({ proposicao }: { proposicao: ProposicaoResumoMeuPainelOut }) {
  return (
    <article className="card">
      <div className="card-top">
        <span className="num">{formatarNumeroProposicao(proposicao.tipo, proposicao.sequencial, proposicao.ano)}</span>
      </div>
      <h3>{proposicao.ementa}</h3>
      <p className="meta">Estado: {proposicao.estado.replaceAll("_", " ")}</p>
    </article>
  );
}
