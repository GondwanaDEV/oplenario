"use client";

// Painel do plenário ao vivo (HERO M4) — porta produto/design-system/.../sessao-ao-vivo.html ligada às
// rotas reais: GET /api/sessoes/:id (estado inicial) + SSE /api/sessoes/:id/plenario (eventos). Mostra AO
// VIVO o que o contrato emite: estado da sessão, quórum/presença, tribuna/cronômetro, inscritos. Pauta e
// votação ainda não têm rota/evento (W3 fan-out futuro) — marcadas honestamente como integração pendente.

import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useTema } from "@/lib/tema";
import { usePlenario, type EstadoConexao } from "@/lib/use-plenario";
import { segundosDecorridos, formatarTempo } from "@/lib/cronometro";
import type { EstadoPlenario } from "@/lib/plenario-reducer";
import type { SessaoOut } from "@/lib/contrato";
import "./plenario.css";

const FASES: { chave: string; nome: string }[] = [
  { chave: "agendada", nome: "Agendada" },
  { chave: "aberta", nome: "Aberta" },
  { chave: "suspensa", nome: "Suspensa" },
  { chave: "encerrada", nome: "Encerrada" },
];
const ORDEM_FASE: Record<string, number> = { agendada: 0, aberta: 1, suspensa: 2, encerrada: 3, nao_realizada: 3, arquivada: 4 };

/** Date.now() reavaliado a cada segundo (relógios ao vivo); pausa sob prefers-reduced-motion, inclusive se ligado mid-sessão. */
function useAgora(): number {
  const [agora, setAgora] = useState(() => Date.now());
  useEffect(() => {
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    let id: ReturnType<typeof setInterval> | null = null;
    const start = () => { if (!mq.matches && id === null) id = setInterval(() => setAgora(Date.now()), 1000); };
    const stop = () => { if (id !== null) { clearInterval(id); id = null; } };
    const onChange = () => (mq.matches ? stop() : start());
    start();
    mq.addEventListener("change", onChange);
    return () => { stop(); mq.removeEventListener("change", onChange); };
  }, []);
  return agora;
}

export default function PaginaPlenario() {
  const params = useParams<{ id: string }>();
  const search = useSearchParams();
  // token de dev: ?token=<json-claims> OU NEXT_PUBLIC_DEV_TOKEN — AMBOS só fora de produção. Em prod a authn
  // vem da sessão (Keycloak, carry F1.4); o guard quebra a render se um token chegar por querystring em prod.
  const tokenQuery = search.get("token");
  if (process.env.NODE_ENV === "production" && tokenQuery) {
    throw new Error("token via querystring desabilitado em produção (authn = sessão Keycloak, carry F1.4).");
  }
  const token = tokenQuery ?? (process.env.NODE_ENV !== "production" ? process.env.NEXT_PUBLIC_DEV_TOKEN ?? null : null);
  const { sessao, estado, conexao, erro } = usePlenario(params.id, token);

  if (conexao === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível abrir o painel</h1>
        <p>{erro ?? "Erro desconhecido."}</p>
      </main>
    );
  }
  if (!sessao || !estado) {
    return (
      <main className="tela-estado">
        <h1>Carregando a sessão…</h1>
        <p>Conectando ao painel ao vivo.</p>
      </main>
    );
  }
  return <Painel sessao={sessao} estado={estado} conexao={conexao} />;
}

function Painel({ sessao, estado, conexao }: { sessao: SessaoOut; estado: EstadoPlenario; conexao: EstadoConexao }) {
  const agora = useAgora(); // um único relógio p/ a página inteira (review react MEDIUM: evita 2 intervals e drift)
  return (
    <>
      <Topo sessao={sessao} estado={estado} conexao={conexao} agora={agora} />
      <Fases estado={estado.estado} />
      <main className="envelope">
        <div className="cabine">
          <Palco sessao={sessao} estado={estado} />
          <aside className="rail" aria-label="Estado do plenário ao vivo">
            <Quorum presentes={estado.presentes.length} />
            <Tribuna estado={estado} agora={agora} />
          </aside>
        </div>
      </main>
    </>
  );
}

function Topo({ sessao, estado, conexao, agora }: { sessao: SessaoOut; estado: EstadoPlenario; conexao: EstadoConexao; agora: number }) {
  const { tema, alternar } = useTema();
  const aoVivo = estado.estado === "aberta";
  const elapsed = sessao["aberta-em"] ? Math.max(0, Math.floor((agora - Date.parse(sessao["aberta-em"])) / 1000)) : 0;
  return (
    <header className="topo">
      <div className="envelope topo-grade">
        <div className="marca">
          <Brasao />
          <div>
            <p className="marca-nome">O&nbsp;Plenário</p>
            <p className="marca-orgao">Câmara Municipal</p>
          </div>
        </div>
        <div className="topo-sep" aria-hidden="true" />
        <Badge estado={estado.estado} conexao={conexao} aoVivo={aoVivo} />
        <div className="sessao-meta">
          <span className="tipo">
            Sessão {sessao["tipo-sessao"]} nº {sessao["numero-sequencial"]}
          </span>
          <span className="quando">{sessao.modalidade}</span>
        </div>
        <div className="topo-dir">
          <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
            {tema === "escuro" ? "☾" : "☀"}
            <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
          </button>
          {sessao["aberta-em"] && (
            <div className="relogio" role="timer" aria-label="Tempo de sessão">
              <span className="rotulo">Sessão há</span>
              <span>{formatarTempo(elapsed)}</span>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}

function Badge({ estado, conexao, aoVivo }: { estado: string; conexao: EstadoConexao; aoVivo: boolean }) {
  if (conexao === "reconectando") return <span className="ao-vivo off">Reconectando…</span>;
  if (estado === "suspensa") return <span className="ao-vivo pausada">Suspensa</span>;
  if (estado === "encerrada" || estado === "arquivada") return <span className="ao-vivo off">Encerrada</span>;
  if (aoVivo)
    return (
      <span className="ao-vivo">
        <span className="pulso" aria-hidden="true" />
        Ao vivo
      </span>
    );
  return <span className="ao-vivo off">{estado}</span>;
}

function Fases({ estado }: { estado: string }) {
  const atual = ORDEM_FASE[estado] ?? 0;
  return (
    <nav className="fases" aria-label="Fase da sessão">
      <div className="envelope">
        <ol className="fases-grade">
          {FASES.map((f, i) => {
            const cls = i < atual ? "fase feita" : f.chave === estado ? "fase atual" : "fase";
            return (
              <li key={f.chave} className={cls} aria-current={f.chave === estado ? "step" : undefined}>
                <span className="ord" aria-hidden="true">
                  {i < atual ? "✓" : i + 1}
                </span>
                <span className="nome">{f.nome}</span>
              </li>
            );
          })}
        </ol>
      </div>
    </nav>
  );
}

function Palco({ sessao, estado }: { sessao: SessaoOut; estado: EstadoPlenario }) {
  const emCurso = estado.estado === "aberta";
  return (
    <section className="bloco palco" aria-labelledby="materia-titulo">
      <div className="palco-cabeca">
        <span className="item-od">
          Sessão {sessao["tipo-sessao"]} · {sessao.delibera ? "deliberativa" : "não deliberativa"}
        </span>
        <span className={`selo-estado ${emCurso ? "" : "calmo"}`}>
          <span className="glifo" aria-hidden="true" />
          {estado.estado}
        </span>
      </div>
      <h1 id="materia-titulo">
        {sessao["tipo-sessao"][0].toUpperCase() + sessao["tipo-sessao"].slice(1)} nº {sessao["numero-sequencial"]}
      </h1>
      <p className="palco-autoria">
        Modalidade <b>{sessao.modalidade}</b>
        {sessao["transmite-publica"] ? " · transmissão pública" : " · sessão reservada"}
        {sessao["permite-voto-secreto"] ? " · admite voto secreto" : ""}
      </p>
      <p className="pendente-integracao">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
          <circle cx="8" cy="8" r="6.5" />
          <path d="M8 5v3.5M8 11h.01" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
        <span>
          <b>Pauta e placar de votação:</b> integração pendente. As rotas de pauta e o evento de votação ao
          vivo entram no fan-out W3 — este painel já reflete <b>estado da sessão, quórum, tribuna e inscritos</b> em tempo real.
        </span>
      </p>
    </section>
  );
}

function Quorum({ presentes }: { presentes: number }) {
  const ok = presentes > 0;
  return (
    <section className="bloco quorum" aria-labelledby="quorum-titulo">
      <div className="bloco-cabeca">
        <h2 id="quorum-titulo">Quórum</h2>
        <span className={`ok-quorum ${ok ? "" : "sem-quorum"}`}>{ok ? "Presenças registradas" : "Aguardando chamada"}</span>
      </div>
      <div className="bloco-corpo">
        <div className="quorum-num">
          <b>{presentes}</b>
          <span>vereadores presentes</span>
        </div>
        <Hemiciclo presentes={presentes} />
        <div className="quorum-legenda">
          <span>
            <i style={{ background: "var(--acao)" }} />
            Presentes {presentes}
          </span>
        </div>
        <p className="palco-autoria" style={{ margin: "0.7rem 0 0", fontSize: "var(--t-12)" }}>
          Total da Casa: aguardando rota de cadastro (fan-out). Cada presença é registro append-only.
        </p>
      </div>
    </section>
  );
}

/** Hemiciclo: distribui `presentes` assentos preenchidos em arcos (porte da geometria da tela HTML). */
function Hemiciclo({ presentes }: { presentes: number }) {
  const cx = 120;
  const cy = 116;
  const fileiras = [
    { r: 46, n: 11 },
    { r: 68, n: 15 },
    { r: 90, n: 17 },
  ];
  const seats: { x: number; y: number }[] = [];
  for (const f of fileiras) {
    for (let i = 0; i < f.n; i++) {
      const t = f.n === 1 ? 0.5 : i / (f.n - 1);
      const ang = Math.PI * (1 - t);
      seats.push({ x: cx + f.r * Math.cos(ang), y: cy - f.r * Math.sin(ang) });
    }
  }
  return (
    <svg className="hemi" viewBox="0 0 240 130" role="img" aria-label={`${presentes} vereadores presentes.`}>
      {seats.map((s, i) => (
        <circle key={i} cx={s.x.toFixed(1)} cy={s.y.toFixed(1)} r="4.6" className={i < presentes ? "presente" : "ausente"} />
      ))}
    </svg>
  );
}

function Tribuna({ estado, agora }: { estado: EstadoPlenario; agora: number }) {
  const o = estado.oradorAtual;
  const pausado = estado.marcosCronometro.length > 0 && estado.marcosCronometro[estado.marcosCronometro.length - 1].tipo === "pausada";
  // aritmética pura por tick — sem useMemo (a dep `agora` muda a cada segundo, a memo nunca acertaria; review react MINOR)
  const decorrido = o ? segundosDecorridos(o.iniciouEm, estado.marcosCronometro, agora) : 0;
  return (
    <section className="bloco larga" aria-labelledby="tribuna-titulo">
      <div className="bloco-cabeca">
        <h2 id="tribuna-titulo">Tribuna</h2>
        <span className="eyebrow" style={{ color: "var(--texto-2)" }}>
          {o ? o.fase : "livre"}
        </span>
      </div>
      <div className="bloco-corpo">
        {o ? (
          <>
            <div className="tribuna-quem">
              <span className="avatar av" aria-hidden="true">
                {o.oradorId.slice(0, 2).toUpperCase()}
              </span>
              <div>
                <b>Orador com a palavra</b>
                <span>{o.tipoFala}</span>
              </div>
            </div>
            <div className="tribuna-tempo">
              <span className="rotulo">{pausado ? "Pausado" : "No uso da palavra"}</span>
              <span className={`timer ${pausado ? "pausado" : ""}`} role="timer" aria-label="Tempo de tribuna">
                {formatarTempo(decorrido)}
              </span>
            </div>
          </>
        ) : (
          <p className="tribuna-vazia">Ninguém com a palavra no momento.</p>
        )}
        {estado.inscritos.length > 0 && (
          <ol className="inscritos" aria-label="Inscritos">
            {estado.inscritos.map((i) => (
              <li key={i.inscricaoId}>
                <span className="ord">{i.ordem}</span>
                <b>{i.vereadorId.slice(0, 8)}</b>
              </li>
            ))}
          </ol>
        )}
      </div>
    </section>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 40 40" role="img" aria-label="O Plenário">
      <circle cx="20" cy="20" r="19" fill="#FBF8F0" stroke="#E0D7BF" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#0C5340" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#1E5FA8" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#D9542B" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#E8B23A" />
    </svg>
  );
}
