"use client";

// Cockpit ao vivo do vereador (Onda C3, Marco MFE-3): confirma presença + vota do próprio celular sobre o
// MESMO SSE/placar oficial que a Mesa vê (usePlenario/placar-vista, nenhum estado paralelo — o cockpit NUNCA
// mantém uma segunda fonte de verdade do placar). Porte mobile do bloco `.so-em-sessao`/`.ao-vivo` de
// produto/design-system/o-plenario/telas/vereador-app.html; markup do placar é um resumo próprio (barra +
// legenda), não o grid nominal completo da Mesa (esse vive em sessoes/[id]/plenario, desktop-oriented).

import { useAuth } from "@/lib/auth";
import { useMinhaSessaoAtual } from "@/lib/use-minha-sessao-atual";
import { useMeuPainel } from "@/lib/use-meu-painel";
import { usePlenario } from "@/lib/use-plenario";
import { derivarPlacar } from "@/lib/placar-vista";
import { useConfirmarPresenca } from "@/lib/use-confirmar-presenca";
import { useMeuVoto, type VotoNominalIn } from "@/lib/use-meu-voto";
import { derivarMeuVoto } from "@/lib/meu-voto-vista";
import "./votar.css";

const NOME_VOTO: Record<VotoNominalIn, string> = { sim: "Sim", nao: "Não", abstencao: "Abstenção" };

export default function VotarPage() {
  const { token } = useAuth();
  const { sessaoId, estado: estadoSessaoAtual } = useMinhaSessaoAtual(token);
  const { dados: painel } = useMeuPainel(token);
  const meuVereadorId = painel?.vereadorId ?? null;

  const { estado: estadoPlenario, conexao } = usePlenario(sessaoId ?? "", token);
  const { confirmar, estado: estadoConfirmar, erro: erroConfirmar } = useConfirmarPresenca(token);
  const { votar, estado: estadoVotar, erro: erroVotar } = useMeuVoto(token);

  if (estadoSessaoAtual === "carregando") {
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

  const vista = derivarMeuVoto(estadoPlenario, meuVereadorId);
  const placar = derivarPlacar(estadoPlenario?.placar ?? null);

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

  const total = placar.kind === "nominal" ? placar.sim + placar.nao + placar.abstencao : 0;
  const pctSim = total > 0 && placar.kind === "nominal" ? Math.round((placar.sim / total) * 100) : 0;
  const pctNao = total > 0 && placar.kind === "nominal" ? Math.round((placar.nao / total) * 100) : 0;

  return (
    <main className="votar-pagina">
      <h1 className="sr-only">Cockpit de votação</h1>

      <section className="ao-vivo" aria-label="Votação ao vivo" aria-live="polite">
        <div className="av-top">
          {conexao === "reconectando" ? (
            <span className="av-vivo av-off">Reconectando…</span>
          ) : (
            <span className="av-vivo">
              <span className="pulso" aria-hidden="true" />
              Ao vivo
            </span>
          )}
        </div>

        {placar.kind === "nenhuma" && <p className="voto-nota">Nenhuma votação aberta no momento.</p>}

        {placar.kind !== "nenhuma" && (
          <>
            {vista.ciclo === "sem-presenca" && (
              <div className="presenca-cta">
                <p className="voto-nota">Confirme sua presença para poder votar.</p>
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
                <p className="voto-nota">
                  Você votou <b>{NOME_VOTO[vista.meuVoto]}</b>. O voto já entra no placar oficial abaixo.
                </p>
              </div>
            )}

            {vista.ciclo === "secreta" && (
              <p className="voto-nota">Votação secreta: o voto só é registrável pelo terminal da Mesa (§22.6).</p>
            )}

            {vista.ciclo === "encerrada" && <p className="voto-nota">Votação encerrada.</p>}

            {erroVotar && (
              <p role="alert" className="voto-erro">
                {erroVotar}
              </p>
            )}

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
          </>
        )}
      </section>
    </main>
  );
}
