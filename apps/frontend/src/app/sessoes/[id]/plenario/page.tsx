"use client";

// Painel do plenário ao vivo (HERO M4) — porta produto/design-system/.../sessao-ao-vivo.html ligada às
// rotas reais: GET /api/sessoes/:id (estado inicial) + SSE /api/sessoes/:id/plenario (eventos). Mostra AO
// VIVO o que o contrato emite: estado da sessão, quórum/presença, tribuna/cronômetro, inscritos, pauta e
// o placar de votação (votacao.aberta/voto.registrado/votacao.encerrada — sigilo §22.6 no view-model puro).

import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useTema } from "@/lib/tema";
import { usePlenario, type EstadoConexao } from "@/lib/use-plenario";
import { usePauta } from "@/lib/use-pauta";
import { segundosDecorridos, formatarTempo } from "@/lib/cronometro";
import type { EstadoPlenario, PlacarVotacao } from "@/lib/plenario-reducer";
import { derivarPlacar, type VistaNominal, type VistaSecreta } from "@/lib/placar-vista";
import type { SessaoOut, PautaOut } from "@/lib/contrato";
import { AuthProvider, useAuth } from "@/lib/auth";
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
  // vem da sessão (Keycloak, carry F1.4). O guard (AuthContext, src/lib/auth.tsx) lança se um token chegar
  // por querystring em prod — o AuthProvider não chama nenhum hook próprio, então pode lançar ANTES de montar
  // o conteúdo sem violar a ordem de hooks deste componente nem a do conteúdo interno (review react HIGH).
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <ConteudoPlenario id={params.id} />
    </AuthProvider>
  );
}

function ConteudoPlenario({ id }: { id: string }) {
  const { token } = useAuth();
  const { sessao, estado, conexao, erro } = usePlenario(id, token);
  // pauta viva (GET; re-busca quando a fase muda). Chamado ANTES dos early-returns p/ ordem de hooks estável.
  const { pauta } = usePauta(id, token, estado?.estado ?? null);

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
  return <Painel sessao={sessao} estado={estado} conexao={conexao} pauta={pauta} />;
}

function Painel({ sessao, estado, conexao, pauta }: { sessao: SessaoOut; estado: EstadoPlenario; conexao: EstadoConexao; pauta: PautaOut | null }) {
  const agora = useAgora(); // um único relógio p/ a página inteira (review react MEDIUM: evita 2 intervals e drift)
  return (
    <>
      <Topo sessao={sessao} estado={estado} conexao={conexao} agora={agora} />
      <Fases estado={estado.estado} />
      <main className="envelope">
        <div className="cabine">
          <Palco sessao={sessao} estado={estado} pauta={pauta} />
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

const NOME_FASE: Record<string, string> = {
  expediente: "Expediente",
  grande_expediente: "Grande Expediente",
  ordem_do_dia: "Ordem do Dia",
  explicacoes_pessoais: "Explicações Pessoais",
  tribuna_livre_cidadao: "Tribuna Livre",
};
const NOME_TIPO_ITEM: Record<string, string> = {
  proposicao: "Proposição",
  leitura: "Leitura",
  comunicado: "Comunicado",
  homenagem: "Homenagem",
};

function Palco({ sessao, estado, pauta }: { sessao: SessaoOut; estado: EstadoPlenario; pauta: PautaOut | null }) {
  const emCurso = estado.estado === "aberta";
  const itens = pauta?.itens ?? [];
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
        {/* capitalização vem do CSS (.palco h1 { text-transform: capitalize }); evita crash se vier "" */}
        {sessao["tipo-sessao"]} nº {sessao["numero-sequencial"]}
      </h1>
      <p className="palco-autoria">
        Modalidade <b>{sessao.modalidade}</b>
        {sessao["transmite-publica"] ? " · transmissão pública" : " · sessão reservada"}
        {sessao["permite-voto-secreto"] ? " · admite voto secreto" : ""}
      </p>

      <section className="pauta" aria-labelledby="pauta-titulo">
        <h2 id="pauta-titulo">Pauta da sessão</h2>
        {itens.length === 0 ? (
          <p className="pauta-vazia">Nenhum item ativo na pauta {pauta ? "ainda." : "(não publicada)."}</p>
        ) : (
          <ol className="pauta-lista">
            {itens.map((it) => (
              <li key={it.id} className="pauta-item">
                <span className="pauta-ordem" aria-hidden="true">{it.ordem}</span>
                <span className="pauta-corpo">
                  <span className="pauta-fase">{NOME_FASE[it.fase] ?? it.fase}</span>
                  <span className="pauta-desc">
                    {it["tipo-item"] === "proposicao"
                      ? `${NOME_TIPO_ITEM.proposicao} · matéria vinculada`
                      : it["texto-descricao"] ?? (NOME_TIPO_ITEM[it["tipo-item"]] ?? it["tipo-item"])}
                  </span>
                </span>
                <span className={`pauta-tag tipo-${it["tipo-item"]}`}>{NOME_TIPO_ITEM[it["tipo-item"]] ?? it["tipo-item"]}</span>
              </li>
            ))}
          </ol>
        )}
      </section>

      <Placar placar={estado.placar} />
    </section>
  );
}

const NOME_VOTO: Record<string, string> = { sim: "Sim", nao: "Não", abstencao: "Abst." };

/** Marca visual do voto (✓ / ✗ / —); o texto do voto fica visível ao lado (a11y), a marca é decorativa. */
function MarcaVoto({ voto }: { voto: string }) {
  const d = voto === "sim" ? "M4 10l4 4 8-9" : voto === "nao" ? "M5 5l10 10M15 5L5 15" : "M4 10h12";
  return (
    <span className={`mk mk-${voto}`} aria-hidden="true">
      <svg width="13" height="13" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
        <path d={d} />
      </svg>
    </span>
  );
}

/** Placar da votação corrente. §22.6 SIGILO: a NOMINAL mostra quem votou o quê; a SECRETA só o contador.
 * A escolha do que renderizar mora no view-model puro `derivarPlacar` (testado) — aqui só mapeamento. */
function Placar({ placar }: { placar: PlacarVotacao | null }) {
  const v = derivarPlacar(placar);
  if (v.kind === "nenhuma") return null;
  // aria-live NÃO fica na section inteira (anunciaria título+grade nominal a cada voto); mora só nos números
  // que mudam (Tally / contador), que já estão montados desde a abertura — review react MAJOR (a11y).
  return (
    <section className="placar-bloco" aria-labelledby="placar-titulo">
      <div className="placar-cabeca">
        <h2 id="placar-titulo">Votação {v.encerrada ? "encerrada" : "em curso"}</h2>
        {v.encerrada && v.resultado ? (
          <span className={`placar-resultado ${v.resultado}`}>{v.resultado}</span>
        ) : (
          <span className="placar-vivo">
            <span className="pulso" aria-hidden="true" />
            Aberta
          </span>
        )}
      </div>
      {v.kind === "nominal" ? <PlacarNominal v={v} /> : <PlacarSecreta v={v} />}
    </section>
  );
}

function Tally({ sim, nao, abstencao }: { sim: number; nao: number; abstencao: number }) {
  // <dl> exprime rótulo→valor nativamente; aria-atomic faz a AT anunciar "Sim 5, Não 3, Abstenção 1" como
  // unidade a cada atualização (review react MENOR-2 + a região viva escopada do MAJOR).
  return (
    <dl className="placar-tally" aria-label="Contagem de votos" aria-live="polite" aria-atomic="true">
      <div className="pl-card pl-sim">
        <dt className="rot">Sim</dt>
        <dd><b>{sim}</b></dd>
      </div>
      <div className="pl-card pl-nao">
        <dt className="rot">Não</dt>
        <dd><b>{nao}</b></dd>
      </div>
      <div className="pl-card pl-abs">
        <dt className="rot">Abstenção</dt>
        <dd><b>{abstencao}</b></dd>
      </div>
    </dl>
  );
}

function PlacarMeta({ faltam, baseMembros }: { faltam: number | null; baseMembros: number | null }) {
  if (faltam === null || baseMembros === null) return null;
  return (
    <p className="placar-meta">
      {faltam > 0 ? (
        <>
          faltam votar <b>{faltam}</b> de <b>{baseMembros}</b>
        </>
      ) : (
        <>
          todos os <b>{baseMembros}</b> votaram
        </>
      )}
    </p>
  );
}

function PlacarNominal({ v }: { v: VistaNominal }) {
  return (
    <>
      <Tally sim={v.sim} nao={v.nao} abstencao={v.abstencao} />
      <PlacarMeta faltam={v.faltam} baseMembros={v.baseMembros} />
      {v.votosParciais && (
        <p className="placar-parcial">Lista nominal parcial (após reconexão) — a contagem acima é a oficial do servidor.</p>
      )}
      {v.votos.length > 0 && (
        <ul className="placar-nominal" aria-label="Votos nominais">
          {v.votos.map((it) => (
            <li key={it.vereadorId} className="vt">
              <MarcaVoto voto={it.voto} />
              <span className="vn">
                {/* só o id (truncado) — não há rota de cadastro p/ nome/partido ainda (mesmo critério da tribuna) */}
                <b>{it.vereadorId.slice(0, 8)}</b>
                <span>{NOME_VOTO[it.voto] ?? it.voto}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function PlacarSecreta({ v }: { v: VistaSecreta }) {
  return (
    <>
      <p className="placar-sigilo">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
          <rect x="3.2" y="7" width="9.6" height="6.5" rx="1.5" />
          <path d="M5.2 7V5a2.8 2.8 0 0 1 5.6 0v2" />
        </svg>
        <span>
          <b>Votação secreta.</b> O painel mostra apenas quantos votos foram lançados — nunca quem votou o quê (§22.6).
        </span>
      </p>
      {v.encerrada && v.totais ? (
        <>
          <Tally sim={v.totais.sim} nao={v.totais.nao} abstencao={v.totais.abstencao} />
          <PlacarMeta faltam={v.faltam} baseMembros={v.baseMembros} />
        </>
      ) : (
        <div className="placar-contador" aria-live="polite" aria-atomic="true">
          <b>{v.registrados}</b>
          <span>votos lançados</span>
        </div>
      )}
    </>
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
