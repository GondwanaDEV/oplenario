"use client";

// Modo TV da sessão (docs/22) — porte de produto/design-system/o-plenario/telas/modo-tv.html. A TV do
// plenário para o PÚBLICO presente: moldura de telejornal (relógio + selo ao vivo + letreiro) e um miolo que
// segue a fase da sessão. Aberta pelo operador logado (botão "Modo TV" no cockpit da Mesa e no painel), numa
// janela nova que herda a sessão dele — o SSE já autoriza qualquer ator do tenant em sessão não-secreta.
//
// Mesmos dados do painel (`usePlenario` com quórum+votação, `usePauta`); toda regra de exibição mora no
// view-model puro `lib/tv-vista.ts` + `lib/tv-letreiro.ts` (testados). Aqui só: layout, tela cheia, wake
// lock, cursor e o intervalo do veredito.

import { useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { AuthProvider, useAuth } from "@/lib/auth";
import { usePlenario, type EstadoConexao } from "@/lib/use-plenario";
import { usePauta } from "@/lib/use-pauta";
import type { PautaOut, SessaoOut } from "@/lib/contrato";
import { vistaDoQuorum, type EstadoPlenario, type PlacarVotacao } from "@/lib/plenario-reducer";
import {
  DURACAO_RESULTADO_MS,
  dataDaTv,
  encerrouAoVivo,
  faseDaTv,
  itensDaPautaTv,
  relogioDaTv,
  seloDaTv,
  subRelogioDaTv,
  tituloDaSessao,
  vistaResultadoTv,
  vistaTribunaTv,
  vistaVotacaoTv,
  type FaseTv,
  type ItemPautaTv,
} from "@/lib/tv-vista";
import { frasesDoLetreiro } from "@/lib/tv-letreiro";
import "./tv.css";

export default function PaginaTv() {
  const params = useParams<{ id: string }>();
  const search = useSearchParams();
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <ConteudoTv id={params.id} />
    </AuthProvider>
  );
}

/** Relógio da TV: tica a cada segundo SEMPRE. Ao contrário do painel (que pausa sob reduced-motion), aqui a
 * hora é a informação da tela, não uma animação — o texto que muda não é movimento. */
function useRelogio(): number {
  const [agora, setAgora] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setAgora(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return agora;
}

/** O veredito em tela cheia por DURACAO_RESULTADO_MS, só na transição vista ao vivo (`encerrouAoVivo`).
 * Derivado DURANTE O RENDER a partir do placar anterior (mesmo padrão de reset-em-render de
 * use-documento-detalhe.ts) — nada de setState dentro de effect. */
function useVeredito(placar: PlacarVotacao | null, agora: number): boolean {
  const [montadaEm] = useState(agora);
  const [anterior, setAnterior] = useState<PlacarVotacao | null>(placar);
  const [ate, setAte] = useState(0);
  if (placar !== anterior) {
    if (encerrouAoVivo(anterior, placar, agora, montadaEm)) setAte(agora + DURACAO_RESULTADO_MS);
    setAnterior(placar);
  }
  return agora < ate;
}

/** Tela cheia de verdade (Fullscreen API). O navegador só a concede com um gesto do usuário NA PRÓPRIA
 * janela — por isso o overlay de um clique. Se o navegador recusar, a TV segue em janela (nunca trava). */
function useTelaCheia() {
  const [cheia, setCheia] = useState(false);
  const [dispensada, setDispensada] = useState(false);
  useEffect(() => {
    const sync = () => setCheia(document.fullscreenElement !== null);
    document.addEventListener("fullscreenchange", sync);
    return () => document.removeEventListener("fullscreenchange", sync);
  }, []);
  const entrar = useCallback(async () => {
    try {
      await document.documentElement.requestFullscreen({ navigationUI: "hide" });
    } catch {
      // recusado (política do navegador / iframe): segue em janela
    }
    setDispensada(true);
  }, []);
  return { cheia, pedir: !cheia && !dispensada, entrar };
}

/** A TV não pode apagar no meio da sessão. Screen Wake Lock onde houver; o navegador solta o lock quando a
 * aba sai de vista, então re-adquire no `visibilitychange`. Sem suporte: nada acontece (a TV do plenário
 * costuma ter a economia de energia desligada de qualquer forma). */
function useManterAcesa() {
  useEffect(() => {
    let lock: WakeLockSentinel | null = null;
    let vivo = true;
    const pedir = async () => {
      if (!("wakeLock" in navigator) || document.visibilityState !== "visible") return;
      try {
        const l = await navigator.wakeLock.request("screen");
        if (vivo) lock = l;
        else void l.release();
      } catch {
        // recusado (bateria/política): sem lock, a TV segue funcionando
      }
    };
    void pedir();
    const onVis = () => void pedir();
    document.addEventListener("visibilitychange", onVis);
    return () => {
      vivo = false;
      document.removeEventListener("visibilitychange", onVis);
      void lock?.release().catch(() => {});
    };
  }, []);
}

/** O cursor some depois de 3 s parado — numa TV de parede ele é só uma seta perdida no meio da tela. */
function useCursorOculto(): boolean {
  const [oculto, setOculto] = useState(false);
  useEffect(() => {
    let t: ReturnType<typeof setTimeout> | undefined;
    const mexeu = () => {
      setOculto(false);
      clearTimeout(t);
      t = setTimeout(() => setOculto(true), 3000);
    };
    mexeu();
    window.addEventListener("mousemove", mexeu);
    return () => {
      clearTimeout(t);
      window.removeEventListener("mousemove", mexeu);
    };
  }, []);
  return oculto;
}

function ConteudoTv({ id }: { id: string }) {
  const { token } = useAuth();
  const { sessao, estado, conexao, erro } = usePlenario(id, token, { comQuorum: true, comVotacao: true });
  const { pauta } = usePauta(id, token, estado?.estado ?? null);
  const agora = useRelogio();
  const exibindoVeredito = useVeredito(estado?.placar ?? null, agora);
  const telaCheia = useTelaCheia();
  const cursorOculto = useCursorOculto();
  useManterAcesa();

  let miolo: React.ReactNode;
  let fase: FaseTv | "aviso" = "aviso";
  if (conexao === "erro") {
    const reservada = sessao?.["tipo-sessao"] === "secreta" || erro === "Acesso ao painel negado.";
    miolo = reservada ? (
      <Aviso titulo="Sessão reservada" texto="Esta sessão não é transmitida ao público. A TV volta a exibir o plenário na próxima sessão pública." />
    ) : (
      <Aviso titulo="Sem sinal da sessão" texto={`${erro ?? "Não foi possível abrir a sessão."} Confira a conexão e reabra o Modo TV pelo painel da Mesa.`} />
    );
  } else if (!sessao || !estado) {
    miolo = <Aviso titulo="Conectando ao plenário…" texto="A TV mostra a sessão assim que o sinal ao vivo chegar." />;
  } else {
    fase = faseDaTv(estado.estado, estado.placar, exibindoVeredito);
    miolo = <Miolo fase={fase} sessao={sessao} estado={estado} pauta={pauta} agora={agora} />;
  }

  return (
    <main className={`tv${cursorOculto ? " cursor-oculto" : ""}`} aria-label="Modo TV da sessão plenária" data-fase={fase}>
      <Topo sessao={sessao} estado={estado} conexao={conexao} agora={agora} />
      <div className="faixa" aria-hidden="true">
        <span className="a" />
        <span className="b" />
        <span className="c" />
        <span className="d" />
      </div>
      <div className="tv-miolo-area">{miolo}</div>
      <Rodape sessao={sessao} estado={estado} pauta={pauta} agora={agora} />
      {telaCheia.pedir && (
        <button type="button" className="tv-entrar" onClick={() => void telaCheia.entrar()}>
          <span className="tv-entrar-titulo">Entrar em tela cheia</span>
          <span className="tv-entrar-sub">Clique uma vez. Para sair, pressione Esc.</span>
        </button>
      )}
    </main>
  );
}

// ---------------------------------------------------------------- moldura

function Topo({ sessao, estado, conexao, agora }: { sessao: SessaoOut | null; estado: EstadoPlenario | null; conexao: EstadoConexao; agora: number }) {
  const estadoSessao = estado?.estado ?? sessao?.estado ?? "";
  const selo = sessao ? seloDaTv(estadoSessao, conexao) : null;
  const sub = sessao ? subRelogioDaTv(sessao, estadoSessao, agora) : null;
  return (
    <header className="tv-topo">
      <div className="tv-marca">
        <Brasao />
        <div className="tv-casa">
          Câmara Municipal
          <span>Plenário · {dataDaTv(agora)}</span>
        </div>
      </div>
      <div className="tv-sessao">{sessao ? tituloDaSessao(sessao) : ""}</div>
      {selo ? (
        <span className={`tv-selo ${selo.tom}`}>
          <span className="pulso" aria-hidden="true" />
          {selo.rotulo}
        </span>
      ) : (
        <span />
      )}
      <div className="tv-relogio" role="timer" aria-label="Horário">
        <b>{relogioDaTv(agora)}</b>
        {sub && <span>{sub}</span>}
      </div>
    </header>
  );
}

function Rodape({ sessao, estado, pauta, agora }: { sessao: SessaoOut | null; estado: EstadoPlenario | null; pauta: PautaOut | null; agora: number }) {
  const frases = sessao && estado ? frasesDoLetreiro(sessao, estado, pauta, agora) : [];
  // Duração proporcional ao texto: a leitura segue na mesma velocidade com 2 ou 7 frases.
  const caracteres = frases.reduce((n, f) => n + f.antes.length + f.destaque.length + f.depois.length, 0);
  const duracao = `${Math.max(20, Math.round(caracteres / 6))}s`;
  return (
    <footer className="tv-rodape">
      <span className="etq">Plenário</span>
      <div className="letreiro" aria-label="Acontecimentos da sessão">
        {frases.length > 0 && (
          <ul style={{ animationDuration: duracao }}>
            {frases.map((f, i) => (
              <li key={i}>
                <span>
                  {f.antes}
                  <b>{f.destaque}</b>
                  {f.depois}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <span className="dir">O Plenário · transmissão pública</span>
    </footer>
  );
}

function Aviso({ titulo, texto }: { titulo: string; texto: string }) {
  return (
    <section className="tv-miolo f-pausa" aria-live="polite">
      <h1>{titulo}</h1>
      <p>{texto}</p>
    </section>
  );
}

// ---------------------------------------------------------------- miolo por fase

function Miolo({ fase, sessao, estado, pauta, agora }: { fase: FaseTv; sessao: SessaoOut; estado: EstadoPlenario; pauta: PautaOut | null; agora: number }) {
  const itens = itensDaPautaTv(pauta, estado.placar);
  switch (fase) {
    case "abertura":
      return (
        <section className="tv-miolo f-abertura" aria-label="Abertura da sessão">
          <div className="abertura-hero">
            <span className="data">{dataDaTv(agora)}</span>
            <h1>{tituloDaSessao(sessao)}</h1>
            <p>A sessão vai começar. Acompanhe aqui a pauta, a tribuna e as votações ao vivo.</p>
          </div>
          <Pauta itens={itens} rotulo={itens.length > 0 ? `Pauta do dia · ${itens.length} ${itens.length === 1 ? "item" : "itens"}` : "Pauta do dia"} />
        </section>
      );
    case "votacao":
      return <Votacao estado={estado} />;
    case "resultado":
      return <Resultado estado={estado} />;
    case "pausa":
      return (
        <Aviso titulo="Sessão suspensa" texto="Os trabalhos serão retomados em instantes. A pauta e as votações voltam a aparecer aqui automaticamente." />
      );
    case "encerrada":
      return (
        <Aviso
          titulo={estado.estado === "nao_realizada" ? "Sessão não realizada" : "Sessão encerrada"}
          texto={`${tituloDaSessao(sessao)}. Obrigado pela presença — as próximas sessões aparecem aqui ao vivo.`}
        />
      );
    case "em-curso":
    default:
      return <EmCurso estado={estado} itens={itens} agora={agora} />;
  }
}

function Pauta({ itens, rotulo }: { itens: ItemPautaTv[]; rotulo: string }) {
  return (
    <div className="cartao cartao-pauta">
      <p className="rot">{rotulo}</p>
      {itens.length === 0 ? (
        <p className="pauta-vazia">A pauta desta sessão ainda não foi publicada.</p>
      ) : (
        <ol className="pauta">
          {itens.map((it) => (
            <li key={it.id} className={it.emVotacao ? "atual" : undefined}>
              <span className="ord">{it.ordem}</span>
              <span className="it">
                <b>{it.sigla}</b>
                <span>{it.descricao}</span>
              </span>
              <span className="fase">{it.emVotacao ? "Em votação" : it.fase}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function EmCurso({ estado, itens, agora }: { estado: EstadoPlenario; itens: ItemPautaTv[]; agora: number }) {
  const tribuna = vistaTribunaTv(estado, agora);
  const q = vistaDoQuorum(estado);
  return (
    <section className="tv-miolo f-em-curso" aria-label="Sessão em curso">
      <div className="coluna">
        <div className="cartao tribuna">
          <p className="rot">{tribuna ? `Na tribuna${tribuna.fase ? ` · ${tribuna.fase}` : ""}` : "Tribuna"}</p>
          {tribuna ? (
            <>
              <div className="quem">
                <span className="avatar-tv" aria-hidden="true">
                  {tribuna.iniciais}
                </span>
                <div>
                  <b>{tribuna.nome}</b>
                  {tribuna.detalhe && <span>{tribuna.detalhe}</span>}
                </div>
              </div>
              <div className="crono" role="timer" aria-label="Tempo de fala">
                <b>{tribuna.decorrido}</b>
                <span>{tribuna.pausado ? "pausado" : "no uso da palavra"}</span>
              </div>
            </>
          ) : (
            <p className="tribuna-livre">Ninguém com a palavra no momento.</p>
          )}
        </div>
        <div className="cartao">
          <p className="rot">Quórum</p>
          <div className="quorum-tv" aria-live="polite" aria-atomic="true">
            {q.status === "ok" ? (
              <>
                <b>
                  {q.presentes} de {q.membrosDaCasa}
                </b>
                <span>vereadores presentes</span>
              </>
            ) : (
              <span>{q.status === "carregando" ? "Carregando o quórum…" : "Contagem de quórum indisponível no momento."}</span>
            )}
          </div>
        </div>
      </div>
      <Pauta itens={itens} rotulo="Pauta do dia" />
    </section>
  );
}

const ICONE_VOTO = {
  sim: "M5 12l5 5L20 6",
  nao: "M18 6 6 18M6 6l12 12",
  abstencao: "M5 12h14",
} as const;
const NOME_VOTO = { sim: "Sim", nao: "Não", abstencao: "Abstenção" } as const;

function Icone({ d }: { d: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

function Votacao({ estado }: { estado: EstadoPlenario }) {
  const v = vistaVotacaoTv(estado);
  if (!v) return null;
  const total = v.sim !== null && v.nao !== null && v.abstencao !== null ? v.sim + v.nao + v.abstencao : v.votaram;
  const pct = (n: number | null) => (n !== null && v.podemVotar ? `${Math.min(100, (n / v.podemVotar) * 100)}%` : "0%");
  const n = (x: number | null) => (x === null ? "—" : String(x));
  return (
    <section className="tv-miolo f-votacao" aria-label="Votação em curso">
      <div className="materia">
        <span className="num">
          {v.numero} <small>votação {v.modalidade}</small>
        </span>
        {v.ementa && <h1>{v.ementa}</h1>}
      </div>

      <div className="contagem" aria-live="polite" aria-atomic="true">
        <div className="kpi">
          <p className="rot">Podem votar</p>
          <b>{n(v.podemVotar)}</b>
          <span className="sub">{v.membrosDaCasa !== null ? `presentes de ${v.membrosDaCasa}` : "presentes"}</span>
        </div>
        <div className="kpi">
          <p className="rot">Já votaram</p>
          <b>{v.votaram}</b>
          <span className="sub">{v.votaram === 1 ? "voto registrado" : "votos registrados"}</span>
        </div>
        <div className="kpi faltam">
          <p className="rot">Faltam</p>
          <b>{n(v.faltam)}</b>
          <span className="sub">aguardando voto</span>
        </div>
        <div className="kpi progresso">
          <p className="rot">Total de votos · {total}</p>
          {v.modalidade === "nominal" ? (
            <>
              <div
                className="trilho"
                role="img"
                aria-label={`${v.sim} sim, ${v.nao} não, ${v.abstencao} abstenções${v.podemVotar !== null ? ` de ${v.podemVotar} presentes` : ""}`}
              >
                <i className="t-sim" style={{ width: pct(v.sim) }} />
                <i className="t-nao" style={{ width: pct(v.nao) }} />
                <i className="t-abs" style={{ width: pct(v.abstencao) }} />
              </div>
              <span className="sub">
                {v.sim} sim · {v.nao} não · {v.abstencao} abst.
              </span>
            </>
          ) : (
            <span className="sub">Votação secreta: o placar por voto aparece só no encerramento.</span>
          )}
        </div>
      </div>

      {v.modalidade === "nominal" ? (
        <div className="vot-corpo">
          <div className="placar">
            {(["sim", "nao", "abstencao"] as const).map((k) => (
              <div key={k} className={`pl pl-${k === "abstencao" ? "abs" : k}`}>
                <span className="r">
                  <Icone d={ICONE_VOTO[k]} />
                  {NOME_VOTO[k]}
                </span>
                <span className="n">{v[k]}</span>
              </div>
            ))}
          </div>
          <ul className="nominal" aria-label="Votos nominais">
            {v.nominais.map((it, i) => (
              <li key={`${it.nome}-${i}`} className="vt">
                <span className={`mk mk-${it.voto === "abstencao" ? "abs" : it.voto}`} aria-hidden="true">
                  <Icone d={ICONE_VOTO[it.voto]} />
                </span>
                <span className="vn">
                  <b>{it.nome}</b>
                  <span>{NOME_VOTO[it.voto]}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <div className="secreta">
          <p>
            <b>Votação secreta.</b> A TV mostra quantos votos foram lançados — nunca quem votou o quê.
          </p>
        </div>
      )}
      {v.avisoLacuna && (
        <p className="aviso-lacuna" role="status">
          O sinal teve uma lacuna — confira o resultado oficial com a Mesa.
        </p>
      )}
    </section>
  );
}

function Resultado({ estado }: { estado: EstadoPlenario }) {
  const r = vistaResultadoTv(estado);
  if (!r) return null;
  const aprovada = r.resultado === "aprovada";
  return (
    <section className="tv-miolo f-resultado" aria-label="Resultado da votação" aria-live="assertive">
      <span className="materia-res">
        {r.numero} · votação {r.modalidade}
      </span>
      <div className={`veredito ${r.resultado}`}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <path d={aprovada ? "M7 12.5l3.2 3.2L17 9" : "M8.5 8.5l7 7M15.5 8.5l-7 7"} />
        </svg>
        {aprovada ? "Aprovada" : "Rejeitada"}
      </div>
      {r.ementa && <p className="ementa-res">{r.ementa}</p>}
      <div className="placar-final" aria-label={`Placar final: ${r.sim} sim, ${r.nao} não, ${r.abstencao} abstenções`}>
        <span className="s">
          {r.sim} <small>sim</small>
        </span>
        <span className="n">
          {r.nao} <small>não</small>
        </span>
        <span className="a">
          {r.abstencao} <small>abst.</small>
        </span>
      </div>
    </section>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 40 40" role="img" aria-label="O Plenário">
      <circle cx="20" cy="20" r="19" fill="#FFF7EA" stroke="#A6BFA2" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#2C5638" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#3F6E92" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#C0693F" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#CFA65C" />
    </svg>
  );
}
