"use client";

// Cockpit ao vivo do vereador (Onda C3, Marco MFE-3): confirma presença + vota do próprio celular sobre o
// MESMO SSE/placar oficial que a Mesa vê (usePlenario/placar-vista, nenhum estado paralelo — o cockpit NUNCA
// mantém uma segunda fonte de verdade do placar). Porte mobile do bloco `.so-em-sessao`/ilha-hero de
// produto/design-system/o-plenario/telas/vereador-app.html (aqui `.cockpit-ao-vivo`/`.cav-*` — prefixo
// PRÓPRIO, não `.ao-vivo`/`.av-*`: essas colidem com a badge inline da Mesa em sessoes/[id]/plenario/
// plenario.css, forma diferente); markup do placar é um resumo próprio (barra + legenda), não o grid
// nominal completo da Mesa. `conexao`/`estadoSessaoAtual`/`estadoPainel` em erro têm suas PRÓPRIAS telas —
// nunca caem no estado calmo "sem sessão" nem no badge otimista "Ao vivo" (review HIGH: um vereador cuja
// conexão SSE caiu no meio de uma votação nominal não pode ver uma tela que finge estar tudo bem).

import { useAuth } from "@/lib/auth";
import { useMinhaSessaoAtual } from "@/lib/use-minha-sessao-atual";
import { useMeuPainel } from "@/lib/use-meu-painel";
import { usePlenario } from "@/lib/use-plenario";
import { derivarPlacar, type VistaPlacar } from "@/lib/placar-vista";
import { useConfirmarPresenca } from "@/lib/use-confirmar-presenca";
import { useMeuVoto, type VotoNominalIn } from "@/lib/use-meu-voto";
import { derivarMeuVoto } from "@/lib/meu-voto-vista";
import { useDetalheVotacao } from "@/lib/use-detalhe-votacao";
import { tituloObjetoVotacao } from "@/lib/titulo-objeto-votacao";
import "./votar.css";

const NOME_VOTO: Record<VotoNominalIn, string> = { sim: "Sim", nao: "Não", abstencao: "Abstenção" };

export default function VotarPage() {
  const { token } = useAuth();
  const { sessaoId, estado: estadoSessaoAtual } = useMinhaSessaoAtual(token);
  const { dados: painel, estado: estadoPainel } = useMeuPainel(token);
  const meuVereadorId = painel?.vereadorId ?? null;

  const { estado: estadoPlenario, conexao, erro: erroConexao } = usePlenario(sessaoId ?? "", token);
  const { confirmar, estado: estadoConfirmar, erro: erroConfirmar } = useConfirmarPresenca(token);
  const { votar, estado: estadoVotar, erro: erroVotar } = useMeuVoto(token);
  // fatia "demo-tres-consertos" #2 — achado ao vivo: a tela inteira era "Sim/Não/Abster" sem dizer SOBRE
  // O QUE. `votacaoId` do placar já basta pra resolver (o servidor re-deriva objeto-tipo/objeto-id da
  // própria votação — nunca confia em campo algum vindo do cliente). Hook chamado SEMPRE, antes de
  // qualquer `return` condicional abaixo (Rules of Hooks) — `sessaoId`/`votacaoId` ausentes só fazem o
  // hook devolver estado "ocioso" internamente, nunca pulam a chamada.
  const { dados: detalheVotacao, estado: estadoDetalheVotacao } = useDetalheVotacao(
    sessaoId,
    estadoPlenario?.placar?.votacaoId ?? null,
    token,
  );

  // review HIGH (revisao final de branch): `estadoSessaoAtual`/`estadoPainel` "erro" NAO podem cair no
  // mesmo ramo de "sem sessao agora" (calmo) nem no de "carregando" — um vereador cuja auth falhou ou cujo
  // fetch caiu precisa ver ISSO, nao uma tela tranquila que parece dizer "nada acontecendo".
  if (estadoSessaoAtual === "erro") {
    return (
      <main className="votar-pagina tela-estado">
        <h1>Não foi possível abrir o cockpit</h1>
        <p>Não deu para descobrir sua sessão atual. Tente novamente em instantes.</p>
      </main>
    );
  }
  if (estadoSessaoAtual === "carregando" || estadoPainel === "carregando") {
    return (
      <main className="votar-pagina tela-estado">
        <p>Carregando…</p>
      </main>
    );
  }
  if (!sessaoId) {
    return (
      <main className="votar-pagina tela-estado">
        <h1>Nenhuma sessão em curso agora.</h1>
        <p>Assim que uma sessão abrir, o cockpit de votação aparece aqui.</p>
      </main>
    );
  }
  // review HIGH: sem isto, `conexao === "erro"` (SSE negado/caído) caia no `else` do badge e mostrava
  // "Ao vivo" pulsando com "nenhuma votação aberta" — uma tela CONFIANTE mentindo sobre estar conectada,
  // no pior momento possível (uma votação nominal em curso que o celular já não está mais recebendo).
  if (conexao === "erro") {
    return (
      <main className="votar-pagina tela-estado">
        <h1>Não foi possível abrir o painel ao vivo</h1>
        <p>{erroConexao ?? "Erro desconhecido."}</p>
      </main>
    );
  }

  // review MEDIUM: `estadoPainel === "erro"` significa que `meuVereadorId` nunca resolve — sem este aviso,
  // `derivarMeuVoto` cai permanentemente em "sem-presenca" (presente sempre false) sem qualquer sinal de
  // que a causa é uma falha de rede, nao a ausencia real de presenca.
  const identidadeIndisponivel = estadoPainel === "erro";
  const vista = derivarMeuVoto(estadoPlenario, meuVereadorId);
  // review adversarial (frente 'truncamento-familia'): faltava o 2o argumento — o MESMO avisoLacuna que já
  // chega em `estadoPlenario` (o reducer é compartilhado com a Mesa) ficava mudo aqui, e é o vereador quem
  // aperta o botão de voto olhando este placar. `PlacarMini`, abaixo, é quem renderiza o aviso.
  const placar = derivarPlacar(estadoPlenario?.placar ?? null, estadoPlenario?.avisoLacuna ?? false);
  const tituloVotacao = tituloObjetoVotacao(detalheVotacao, estadoDetalheVotacao);

  async function aoConfirmarPresenca() {
    if (!sessaoId) return;
    try {
      await confirmar(sessaoId);
    } catch {
      // erro já exposto via `erroConfirmar` — só evita a unhandled promise rejection.
    }
  }

  async function aoVotar(voto: VotoNominalIn) {
    if (!sessaoId || !estadoPlenario?.placar) return;
    try {
      await votar(sessaoId, estadoPlenario.placar.votacaoId, voto);
    } catch {
      // erro já exposto via `erroVotar`.
    }
  }

  return (
    <main className="votar-pagina">
      <h1 className="sr-only">Cockpit de votação</h1>

      {/* .cockpit-ao-vivo (NAO .ao-vivo — review MEDIUM: a Mesa's plenario.css já tem uma badge .ao-vivo
          inline pequena, classe/forma DIFERENTE; nome colidido quebraria uma das duas se as duas folhas
          algum dia carregarem juntas). aria-live so' na FRASE de status (abaixo), nunca na section inteira
          — o placar-mini muda a cada voto.registrado e re-anunciaria a cada toque (review MEDIUM a11y). */}
      <section className="cockpit-ao-vivo" aria-label="Votação ao vivo">
        <div className="cav-top">
          {conexao === "reconectando" ? (
            <span className="cav-vivo cav-off">Reconectando…</span>
          ) : conexao === "carregando" ? (
            <span className="cav-vivo cav-off">Conectando…</span>
          ) : (
            <span className="cav-vivo">
              <span className="pulso" aria-hidden="true" />
              Ao vivo
            </span>
          )}
        </div>

        {identidadeIndisponivel && (
          <p role="alert" className="voto-erro">
            Não foi possível confirmar sua identificação agora — presença/voto podem não refletir seu estado real.
          </p>
        )}

        {placar.kind === "nenhuma" && (
          <p className="voto-nota" aria-live="polite">
            Nenhuma votação aberta no momento.
          </p>
        )}

        {placar.kind !== "nenhuma" && (
          <>
            {/* achado ao vivo (fatia "demo-tres-consertos" #2): antes daqui a tela inteira era
                "Sim/Não/Abster" sem dizer SOBRE O QUE — o vereador votava num objeto não identificado.
                `role="status"` (não alert): é informação, não erro, mesmo quando o texto explica uma
                falha de identificação — o `voto-erro` abaixo já cobre o caso realmente crítico. */}
            <p className="titulo-objeto-votacao" role="status">
              {tituloVotacao}
            </p>
            {vista.ciclo === "sem-presenca" && (
              <div className="presenca-cta">
                <p className="voto-nota" aria-live="polite">
                  Confirme sua presença para poder votar.
                </p>
                <button
                  className="btn btn-primaria"
                  type="button"
                  onClick={aoConfirmarPresenca}
                  disabled={estadoConfirmar === "enviando"}
                >
                  Confirmar presença
                </button>
                {erroConfirmar && (
                  <p role="alert" className="voto-erro">
                    {erroConfirmar}
                  </p>
                )}
              </div>
            )}

            {vista.ciclo === "pode-votar" && (
              <div className="votar" role="group" aria-label="Seu voto na votação corrente">
                <button className="vbtn vbtn-sim" type="button" onClick={() => aoVotar("sim")} disabled={estadoVotar === "enviando"}>
                  Sim
                </button>
                <button className="vbtn vbtn-nao" type="button" onClick={() => aoVotar("nao")} disabled={estadoVotar === "enviando"}>
                  Não
                </button>
                <button className="vbtn vbtn-abs" type="button" onClick={() => aoVotar("abstencao")} disabled={estadoVotar === "enviando"}>
                  Abster
                </button>
              </div>
            )}

            {vista.ciclo === "ja-votou" && vista.meuVoto && (
              <div className="voto-estado">
                <p className="voto-nota" aria-live="polite">
                  Você votou <b>{NOME_VOTO[vista.meuVoto]}</b>. O voto já entra no placar oficial abaixo.
                </p>
              </div>
            )}

            {vista.ciclo === "secreta" && (
              <p className="voto-nota" aria-live="polite">
                Votação secreta: o voto só é registrável pelo terminal da Mesa (§22.6).
              </p>
            )}

            {vista.ciclo === "encerrada" && (
              <p className="voto-nota" aria-live="polite">
                Votação encerrada.
              </p>
            )}

            {erroVotar && (
              <p role="alert" className="voto-erro">
                {erroVotar}
              </p>
            )}

            <PlacarMini placar={placar} />
          </>
        )}
      </section>
    </main>
  );
}

/** Placar-mini do cockpit do vereador. §22.6 SIGILO: a NOMINAL mostra sim/não; a SECRETA só o contador —
 * a distinção mora em `derivarPlacar` (testado), aqui só mapeamento. Extraído do corpo de `VotarPage`
 * (review adversarial, frente 'truncamento-familia') para ser testável com um `VistaPlacar` direto, sem
 * montar os 5 hooks de rede da página — mesmo racional de `Placar` em sessoes/[id]/plenario/page.tsx, que
 * é quem já renderiza este aviso para a Mesa; aqui é o MESMO aviso pelo caminho do vereador. */
export function PlacarMini({ placar }: { placar: VistaPlacar }) {
  if (placar.kind === "nenhuma") return null;

  const total = placar.kind === "nominal" ? placar.sim + placar.nao + placar.abstencao : 0;
  const pctSim = total > 0 && placar.kind === "nominal" ? Math.round((placar.sim / total) * 100) : 0;
  const pctNao = total > 0 && placar.kind === "nominal" ? Math.round((placar.nao / total) * 100) : 0;

  return (
    <>
      {placar.kind === "nominal" && (
        <div className="placar-mini">
          <div
            className="barra"
            role="img"
            aria-label={`Parcial: ${placar.sim} sim, ${placar.nao} não${
              placar.faltam !== null ? `, ${placar.faltam} ainda não votaram` : ""
            }`}
          >
            <span className="seg-sim" style={{ width: `${pctSim}%` }} />
            <span className="seg-nao" style={{ width: `${pctNao}%` }} />
          </div>
          <div className="leg">
            <span>
              Sim <b>{placar.sim}</b>
            </span>
            <span>
              Não <b>{placar.nao}</b>
            </span>
            {placar.faltam !== null && (
              <span className="parcial">
                faltam <b>{placar.faltam}</b> {placar.encerrada ? "" : "· parcial"}
              </span>
            )}
          </div>
        </div>
      )}

      {placar.kind === "secreta" && (
        <div className="placar-mini">
          <p className="voto-nota">
            {placar.encerrada && placar.totais
              ? `Resultado: ${placar.totais.sim} sim, ${placar.totais.nao} não, ${placar.totais.abstencao} abstenção.`
              : `${placar.registrados} votos lançados (contador anônimo).`}
          </p>
        </div>
      )}

      {placar.avisoLacuna && (
        <p className="aviso-corte" role="status">
          o sinal do servidor teve uma <b>lacuna</b> durante esta sessão — confira o resultado oficial
          antes de decidir por este número
        </p>
      )}
    </>
  );
}
