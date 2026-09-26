"use client";

// A home do vereador — estado FORA DE SESSÃO (Onda C1, Task 7) + defeito #16 (MATA) fechado. Porte 1:1 de
// produto/design-system/o-plenario/telas/vereador-app.html (bloco `.so-fora` + o comum `.card`/`.secao-tit`),
// só o estado calmo: SEM placar nominal (isso continua sendo a C3 — `/votar` — pra onde este componente
// LINCA quando há sessão ao vivo, em vez de duplicar o cockpit aqui). Composição: useAuth (token, já
// resolvido pelo GuardVereador do layout) + useMeuPainel + useSessoes (GET /api/sessoes, defeito #16) +
// useAcusarCiencia + derivarHome (view-model puro).
//
// O defeito: a home dizia "SEM SESSÃO AGORA" / "Nenhuma sessão agendada" no MESMO segundo em que existia
// uma sessão ABERTA (com orador na tribuna) e uma AGENDADA — porque não havia rota de listagem, então
// `sessoes` era SEMPRE `[]` e "não sei" virava "não há". A rota existe agora (GET /sessoes); a parte que
// PERMANECE deste componente (e é o que fecha o defeito de verdade) é NUNCA deixar `estadoSessoes`
// "carregando"/"erro" cair nos mesmos textos que "de fato nenhuma sessão" — `HeroSessao`/`ProximaSessaoResumo`
// abaixo tomam `estadoSessoes` explicitamente e escolhem o texto por ESSE estado, não só pelo dado.
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useMeuPainel } from "@/lib/use-meu-painel";
import { useSessoes, type EstadoSessoes } from "@/lib/use-sessoes";
import { useAcusarCiencia } from "@/lib/use-acusar-ciencia";
import { useSubscricoesHome } from "@/lib/use-subscricao";
import { derivarHome, type HomeVereadorVista } from "@/lib/meu-painel-vista";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { formatarTipoSessao } from "@/lib/pauta-convocacao-vista";
import { derivarTramitacao } from "@/lib/tramitacao-vista";
import { comToken } from "@/lib/nav";
import type {
  CienciaPendenteOut,
  ParecerResumoMeuPainelOut,
  ProposicaoResumoMeuPainelOut,
} from "@/lib/contrato-legislativo.gen";
import "./vereador-home.css";

export default function PaginaHomeVereador() {
  const { token } = useAuth();
  const { dados, estado, recarregar } = useMeuPainel(token);
  const { sessoes, estado: estadoSessoes } = useSessoes(token);
  const { acusar, estado: estadoCiencia, erro: erroCiencia } = useAcusarCiencia(token);
  const vista = derivarHome(dados, sessoes);
  // fatia 2c: pedidos de subscrição para mim + meus requerimentos coletivos esperando coautores
  const { convites, propostas } = useSubscricoesHome(token);

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
        <HeroSessao
          estadoSessoes={estadoSessoes}
          sessaoAoVivo={vista.sessaoAoVivo}
          ciencias={vista.ciencias.length}
          proposicoes={vista.minhasProposicoes.length}
          cienciasTruncado={vista.cienciasTruncado}
          proposicoesTruncado={vista.proposicoesTruncado}
          token={token}
        />
        <div className="fora-prox">
          <ProximaSessaoResumo estadoSessoes={estadoSessoes} sessao={vista.proximaSessao} />
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
          {/* Frente "truncamento-familia" — a MAIS GRAVE das 3 listas: cada `parecerId` cortado é uma
              ciência que você não tem como dar (o eventoRef só existe nesta lista). AUTORITATIVO do
              servidor, nunca deduzido de `vista.ciencias.length`. */}
          {vista.cienciasTruncado && (
            <p role="status" className="aviso-corte">
              Mostrando as <b>{vista.ciencias.length}</b> ciências mais recentes — pode haver mais
              esperando sua ciência fora desta lista.
            </p>
          )}
          {vista.ciencias.map((c) => (
            <CartaoCiencia key={c.parecerId} ciencia={c} onDarCiencia={() => darCiencia(c)} enviando={estadoCiencia === "enviando"} />
          ))}
        </section>
      )}

      {convites.length > 0 && (
        <section aria-label="Pedidos de subscrição">
          <h2 className="secao-tit">Pedidos de subscrição</h2>
          {convites.map((c) => (
            <article key={c.propostaId} className="card">
              <div className="card-top">
                <span className="num">{c.tipoRequerimento}</span>
              </div>
              <h3>{c.ementa}</h3>
              <p className="meta">{c.autorNome} convidou você para subscrever.</p>
              <div className="card-acao">
                <Link className="btn btn-primaria btn-mini" href={comToken(`/requerimento/proposta/${c.propostaId}`, token)}>
                  Ler e responder
                </Link>
              </div>
            </article>
          ))}
        </section>
      )}

      {propostas.length > 0 && (
        <section aria-label="Requerimentos esperando coautores">
          <h2 className="secao-tit">Esperando coautores</h2>
          {propostas.map((p) => (
            <article key={p.id} className="card">
              <div className="card-top">
                <span className="num">{p.tipoRequerimento}</span>
              </div>
              <h3>{p.ementa}</h3>
              <p className="meta">
                {p.confirmadas} {p.confirmadas === 1 ? "confirmou" : "confirmaram"} · {p.pendentes}{" "}
                {p.pendentes === 1 ? "aguarda" : "aguardam"}
                {p.recusadas > 0 ? ` · ${p.recusadas} ${p.recusadas === 1 ? "recusou" : "recusaram"}` : ""}
              </p>
              <div className="card-acao">
                <Link className="btn btn-contorno btn-mini" href={comToken(`/requerimento/proposta/${p.id}`, token)}>
                  Acompanhar e protocolar
                </Link>
              </div>
            </article>
          ))}
        </section>
      )}

      {(vista.meusPareceres.aguardando.length > 0 || vista.pareceresTruncado) && (
        <section aria-label="Meus pareceres">
          <h2 className="secao-tit">Meus pareceres</h2>
          {/* achado da revisão adversarial: este aviso vivia DENTRO do `{aguardando.length > 0 && ...}` —
              quando o corte do servidor derruba justamente os pareceres em aberto (só sobram terminais na
              lista cortada), a seção inteira sumia e o aviso ia junto, no único caso em que ele importa.
              O gate da seção acima agora inclui `pareceresTruncado`; a lista de cards abaixo continua
              mostrando só `aguardando` (pode ficar vazia — o aviso é quem carrega a informação aqui). */}
          {vista.pareceresTruncado && (
            <p role="status" className="aviso-corte">
              Mostrando os <b>{vista.meusPareceres.aguardando.length + vista.meusPareceres.concluidos.length}</b> pareceres
              mais recentes — pode haver mais fora desta lista.
            </p>
          )}
          {vista.meusPareceres.aguardando.map((p) => (
            <CartaoParecer key={p.id} parecer={p} token={token} />
          ))}
        </section>
      )}

      <div className="secao-linha">
        <h2 className="secao-tit">Suas proposições</h2>
        {/* fatia 2a: o requerimento nasce aqui, no login do vereador (modelo da Casa + assinatura) */}
        <Link className="btn btn-primaria btn-mini" href={comToken("/requerimento/novo", token)}>
          Novo requerimento
        </Link>
      </div>
      {vista.proposicoesTruncado && (
        <p role="status" className="aviso-corte">
          Mostrando as <b>{vista.minhasProposicoes.length}</b> proposições mais recentes — pode haver
          mais fora desta lista.
        </p>
      )}
      {vista.minhasProposicoes.length === 0 ? (
        <p className="vazio">Nenhuma proposição sua ainda.</p>
      ) : (
        vista.minhasProposicoes.map((p) => <CartaoProposicao key={p.id} proposicao={p} />)
      )}
    </>
  );
}

/** Resumo textual dos números de ciências/proposições — extraído para ser reaproveitado pelos 4 estados
 * de `HeroSessao` sem repetir a mesma expressão condicional 4 vezes.
 *
 * achado da revisão adversarial: este é o PRIMEIRO número que o vereador lê (herói, acima da dobra) — e
 * `ciencias`/`proposicoes` aqui já são o TAMANHO da lista cortada, não o total. Sem `cienciasTruncado`/
 * `proposicoesTruncado` (mesmo booleano AUTORITATIVO que os avisos das seções abaixo usam), este texto
 * afirmava "Você tem 50 ciências" como se 50 fosse o total, exatamente quando 50 é só o teto. */
function ResumoContagens({
  ciencias,
  proposicoes,
  cienciasTruncado,
  proposicoesTruncado,
}: {
  ciencias: number;
  proposicoes: number;
  cienciasTruncado: boolean;
  proposicoesTruncado: boolean;
}) {
  if (ciencias === 0 && proposicoes === 0) return null;
  return (
    <>
      {ciencias > 0 && (
        <>
          Você tem <b>{cienciasTruncado ? `${ciencias}+` : ciencias}</b> {ciencias === 1 ? "ciência" : "ciências"} a registrar
          {proposicoes > 0 ? " e " : ". "}
        </>
      )}
      {proposicoes > 0 && (
        <>
          <b>{proposicoesTruncado ? `${proposicoes}+` : proposicoes}</b> {proposicoes === 1 ? "proposição" : "proposições"} em andamento.
        </>
      )}
    </>
  );
}

/** O badge + título + resumo do herói fora-de-sessão. É AQUI que o defeito #16 morava de verdade: o badge
 * dizia "Sem sessão agora" e o resumo "Nenhuma votação aberta." incondicionalmente — nunca checavam se
 * havia dado nenhum, então mentiam sempre que `estadoSessoes` não tivesse chegado a "pronto" (ou tivesse
 * chegado a "pronto" com uma sessão ABERTA/SUSPENSA na lista). `estadoSessoes === "carregando" | "erro"`
 * são os dois ramos que NÃO PODEM cair no texto "Sem sessão agora" nem em "Tudo em dia."/"Nenhuma votação
 * aberta." — essas são afirmações factuais que só valem depois que a listagem confirma. */
function HeroSessao({
  estadoSessoes,
  sessaoAoVivo,
  ciencias,
  proposicoes,
  cienciasTruncado,
  proposicoesTruncado,
  token,
}: {
  estadoSessoes: EstadoSessoes;
  sessaoAoVivo: HomeVereadorVista["sessaoAoVivo"];
  ciencias: number;
  proposicoes: number;
  cienciasTruncado: boolean;
  proposicoesTruncado: boolean;
  token: string | null;
}) {
  if (estadoSessoes === "erro") {
    return (
      <>
        <span className="estado">
          <span className="dot" aria-hidden="true" />
          Sessão: não verificada
        </span>
        <h2>Não foi possível confirmar</h2>
        <p className="resumo">
          Não foi possível verificar se há sessão em andamento agora.{" "}
          <ResumoContagens ciencias={ciencias} proposicoes={proposicoes} cienciasTruncado={cienciasTruncado} proposicoesTruncado={proposicoesTruncado} />
        </p>
      </>
    );
  }
  if (estadoSessoes === "carregando") {
    return (
      <>
        <span className="estado">
          <span className="dot" aria-hidden="true" />
          Verificando sessão…
        </span>
        <h2>Um instante…</h2>
        <p className="resumo">
          <ResumoContagens ciencias={ciencias} proposicoes={proposicoes} cienciasTruncado={cienciasTruncado} proposicoesTruncado={proposicoesTruncado} />
        </p>
      </>
    );
  }
  if (sessaoAoVivo) {
    return (
      <>
        <span className="estado">
          <span className="dot" aria-hidden="true" />
          Sessão em andamento
        </span>
        <h2>A sessão está acontecendo agora.</h2>
        <p className="resumo">
          <Link href={comToken("/votar", token)}>Acompanhar a sessão</Link>.{" "}
          <ResumoContagens ciencias={ciencias} proposicoes={proposicoes} cienciasTruncado={cienciasTruncado} proposicoesTruncado={proposicoesTruncado} />
        </p>
      </>
    );
  }
  return (
    <>
      <span className="estado">
        <span className="dot" aria-hidden="true" />
        Sem sessão agora
      </span>
      <h2>Tudo em dia.</h2>
      <p className="resumo">
        Nenhuma votação aberta. <ResumoContagens ciencias={ciencias} proposicoes={proposicoes} cienciasTruncado={cienciasTruncado} proposicoesTruncado={proposicoesTruncado} />
      </p>
    </>
  );
}

/** O card "próxima sessão". A REPROVA que dá nome ao defeito #16: `estadoSessoes` "carregando"/"erro" tem
 * de renderizar um texto DIFERENTE de "Nenhuma sessão agendada" — mesmo com `sessao === null` nos três
 * casos (carregando/erro/de fato nenhuma dão `null` igualmente, porque `derivarHome` é pura e não sabe
 * distinguir "lista vazia porque ainda não chegou" de "lista vazia porque não há sessão"; ver o comentário
 * em `derivarHome`). Um componente que decidisse o texto só por `if (!sessao)` — como a versão antiga deste
 * arquivo — voltaria a dizer "Nenhuma sessão agendada" enquanto o fetch está em voo ou falhou; é
 * exatamente isso que `page.test.tsx` ("estado honesto…") reprova. */
function ProximaSessaoResumo({
  estadoSessoes,
  sessao,
}: {
  estadoSessoes: EstadoSessoes;
  sessao: HomeVereadorVista["proximaSessao"];
}) {
  if (estadoSessoes === "carregando") {
    return (
      <>
        <div className="cal" aria-hidden="true">
          <b>—</b>
          <span>—</span>
        </div>
        <div className="info">
          <b>Carregando agenda…</b>
          <span>Buscando a próxima sessão.</span>
        </div>
      </>
    );
  }
  if (estadoSessoes === "erro") {
    return (
      <>
        <div className="cal" aria-hidden="true">
          <b>—</b>
          <span>—</span>
        </div>
        <div className="info">
          <b>Agenda indisponível</b>
          <span>Não foi possível carregar a próxima sessão. Tente novamente em instantes.</span>
        </div>
      </>
    );
  }
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
  const data = sessao.agendadaPara ? new Date(sessao.agendadaPara) : null;
  return (
    <>
      <div className="cal" aria-hidden="true">
        <b>{data ? data.getDate() : "—"}</b>
        <span>{data ? data.toLocaleDateString("pt-BR", { month: "short" }) : "—"}</span>
      </div>
      <div className="info">
        {/* defeito F2/regressao (caminhada pos-fatia): o card mostrava a chave crua do enum ("ordinaria").
            `formatarTipoSessao` (pauta-convocacao-vista.ts) e' o mapa ja existente pra esse vocabulario —
            mesmo usado no titulo da convocacao ("16ª Sessão Ordinária"); reaproveitado aqui, sem 2º
            vocabulario pro mesmo enum. */}
        <b>{sessao.tipoSessao ? formatarTipoSessao(sessao.tipoSessao) : "Próxima sessão"}</b>
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

function CartaoParecer({ parecer, token }: { parecer: ParecerResumoMeuPainelOut; token: string | null }) {
  return (
    <article className="card">
      <h3>Parecer em {parecer.estado.replaceAll("_", " ")}</h3>
      <div className="card-acao">
        {/* Rota REAL (Task 11): o grupo (vereador) não entra na URL -> /parecer/:id/assinar. `comToken`
            preserva o ?token= de dev entre navegações internas — mesmo padrão do tabbar (layout.tsx) e do
            router.push de volta em parecer/[id]/assinar/page.tsx; sem ele o clique perderia o token dev. */}
        <Link className="btn btn-primaria btn-mini" href={comToken(`/parecer/${parecer.id}/assinar`, token)}>
          Revisar e assinar
        </Link>
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
      {/* #17 do ledger (CONSTRANGE), pego junto por reusar o mesmo mapa: `derivarTramitacao` (o
          view-model que /ficha-materia ja usa) rotula "em_comissoes" -> "Em comissões", "arquivada" ->
          "Arquivada" etc., com fallback humanizado fail-closed pra estado fora do vocabulario — nunca a
          chave crua com underscore. */}
      <p className="meta">Estado: {derivarTramitacao(proposicao.estado).rotuloSituacao}</p>
    </article>
  );
}
