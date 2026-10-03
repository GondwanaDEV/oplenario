"use client";

// A MESA DA AUDIÊNCIA PÚBLICA (ADR-0021 A2/A3) — a secretaria conduz as falas do cidadão: vê os dados da audiência,
// ajusta o tempo único de fala e abre/fecha as inscrições do portal, inscreve no dia quem está presente, chama a
// próxima pessoa, cronometra a fala com o MESMO relógio da tribuna (regressivo, âmbar no último minuto, "+excedido"
// esgotado — sempre com texto junto, a cor nunca é o único sinal) e encerra, ou marca ausente quem não respondeu.
//
// Recebe `token` por prop (testável sem <AuthProvider>, mesmo split de form-agendar-sessao.tsx). IO em
// use-audiencia.ts; derivações puras em audiencia-vista.ts. Abrir e encerrar a SESSÃO continua no Comando da Mesa
// (/sessoes/:id/conduzir) — aqui só as falas. Cada escrita recarrega a audiência: o servidor é a verdade da fila.

import { useEffect, useState } from "react";
import Link from "next/link";
import { comToken } from "@/lib/nav";
import { formatarTempo } from "@/lib/cronometro";
import {
  FALA_COMO,
  rotuloEstadoAudiencia,
  rotuloEstadoInscricao,
  rotuloFalaComo,
  rotuloFinalidade,
  rotuloReferencia,
  type AudienciaOut,
  type InscricaoOut,
} from "@/lib/contrato-audiencia";
import {
  LIMITE_TEMA,
  TEMPO_FALA_MAX,
  TEMPO_FALA_MIN,
  cronometroDaFala,
  filaDaAudiencia,
  podeChamar,
  podeInscreverPresencial,
  quandoPorExtenso,
  quemFala,
  rotuloTempoFala,
  tempoFalaEmSegundos,
  validarInscricaoPresencial,
} from "@/lib/audiencia-vista";
import {
  ajustarAudiencia,
  chamarInscrita,
  encerrarFala,
  inscreverPresencial,
  marcarAusente,
  useAudienciaMesa,
  type Resultado,
} from "@/lib/use-audiencia";
import "@/app/sessoes/[id]/conduzir/conduzir.css";
import "./audiencia.css";

/** Date.now() reavaliado a cada segundo — o relógio da fala. Mesmo padrão do painel da tribuna (pausa sob
 *  prefers-reduced-motion; sem matchMedia, só tica). O tempo enviado ao encerrar é medido na hora do clique. */
function useAgora(ligado: boolean): number {
  const [agora, setAgora] = useState(() => Date.now());
  useEffect(() => {
    if (!ligado) return;
    const mq = typeof window !== "undefined" && typeof window.matchMedia === "function"
      ? window.matchMedia("(prefers-reduced-motion: reduce)")
      : null;
    let id: ReturnType<typeof setInterval> | null = null;
    const start = () => { if (!mq?.matches && id === null) id = setInterval(() => setAgora(Date.now()), 1000); };
    const stop = () => { if (id !== null) { clearInterval(id); id = null; } };
    const onChange = () => (mq?.matches ? stop() : start());
    start();
    mq?.addEventListener?.("change", onChange);
    return () => { stop(); mq?.removeEventListener?.("change", onChange); };
  }, [ligado]);
  return agora;
}

const CHIP_ESTADO: Record<string, string> = {
  inscrita: "chip-neutro",
  falando: "chip-info",
  falou: "chip-ok",
  ausente: "chip-alerta",
  desistiu: "chip-neutro",
};

export function MesaAudiencia({ token, sessaoId }: { token: string | null; sessaoId: string }) {
  const { estado, recarregar } = useAudienciaMesa(token, sessaoId);

  if (estado.fase === "carregando") {
    return (
      <main className="envelope mesa-audiencia">
        <p className="nota-terminal" aria-busy="true">Carregando a audiência…</p>
      </main>
    );
  }
  if (estado.fase === "erro") {
    return (
      <main className="envelope mesa-audiencia">
        <h1 className="ma-titulo">Não foi possível abrir a Mesa da audiência</h1>
        <p role="alert" className="erro-inline">{estado.mensagem}</p>
      </main>
    );
  }
  return <Mesa token={token} audiencia={estado.dado} recarregar={recarregar} />;
}

function Mesa({ token, audiencia: a, recarregar }: { token: string | null; audiencia: AudienciaOut; recarregar: () => void }) {
  const fila = filaDaAudiencia(a.inscricoes);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  /** Toda escrita passa por aqui: trava os botões, diz o resultado e recarrega a fila do servidor. */
  async function agir(fn: () => Promise<Resultado<unknown>>, ok: string): Promise<boolean> {
    setEnviando(true);
    setErro(null);
    setAviso(null);
    const r = await fn();
    setEnviando(false);
    if (r.ok) setAviso(ok);
    else setErro(r.mensagem);
    recarregar();
    return r.ok;
  }

  const quando = quandoPorExtenso(a.agendadaPara);
  const referencia = rotuloReferencia(a.referencia);

  return (
    <main className="envelope mesa-audiencia">
      <section className="bloco estado-atual" aria-labelledby="ma-titulo">
        <div className="bloco-cabeca">
          <div>
            <p className="eyebrow">Audiência pública nº {a.numero}</p>
            <h1 id="ma-titulo">{a.tema}</h1>
          </div>
          <span className="chip selo-estado chip-neutro">{rotuloEstadoAudiencia(a.estado)}</span>
        </div>
        <dl className="ma-dados">
          <div><dt>Promovida por</dt><dd>{a.comissao.nome}</dd></div>
          <div><dt>Finalidade</dt><dd>{rotuloFinalidade(a.finalidade)}{referencia ? ` · ${referencia}` : ""}</dd></div>
          <div><dt>Data e hora</dt><dd className="mono">{quando ?? "a definir"}</dd></div>
          <div><dt>Local</dt><dd>{a.local ?? "não informado"}</dd></div>
          {a.proposicao && (
            <div className="ma-largo"><dt>Matéria relacionada</dt><dd><b>{a.proposicao.rotulo}</b> — {a.proposicao.ementa}</dd></div>
          )}
          <div><dt>Tempo de fala</dt><dd>{rotuloTempoFala(a.tempoFalaSegundos)} por pessoa</dd></div>
          <div><dt>Inscrições pelo portal</dt><dd>{a.inscricoesAbertas ? "Abertas" : "Fechadas"}</dd></div>
        </dl>
        <p className="ma-rodape">
          Abrir e encerrar a sessão fica no{" "}
          <Link href={comToken(`/sessoes/${a.sessaoId}/conduzir`, token)}>Comando da Mesa</Link>. A audiência não
          delibera e não exige quórum.
        </p>
      </section>

      <div className="ma-avisos" aria-live="polite">
        {aviso && <p role="status" className="aviso-ok">{aviso}</p>}
        {erro && <p role="alert" className="erro-inline">{erro}</p>}
      </div>

      {fila.falando && (
        <NaPalavra
          inscricao={fila.falando}
          tempoFalaSegundos={a.tempoFalaSegundos}
          enviando={enviando}
          onEncerrar={(usado) =>
            agir(() => encerrarFala(token, a.sessaoId, fila.falando!.id, usado), `Fala encerrada (${formatarTempo(usado)}).`)
          }
        />
      )}

      <section className="bloco" aria-labelledby="ma-fila">
        <div className="bloco-cabeca">
          <h2 id="ma-fila">Inscritos, em ordem de inscrição</h2>
          <span className="eyebrow">{fila.aguardando} aguardando</span>
        </div>
        <div className="bloco-corpo">
          {a.estado !== "aberta" && podeInscreverPresencial(a) && (
            <p className="nota-mesa ma-nota">Para chamar as pessoas, a sessão precisa estar aberta no Comando da Mesa.</p>
          )}
          {fila.ordenadas.length === 0 ? (
            <p className="nota-terminal">Ninguém inscrito ainda.</p>
          ) : (
            <ol className="fila">
              {fila.ordenadas.map((i) => (
                <LinhaDaFila
                  key={i.id}
                  inscricao={i}
                  proxima={fila.proxima?.id === i.id}
                  podeChamar={podeChamar(a)}
                  enviando={enviando}
                  onChamar={() => agir(() => chamarInscrita(token, a.sessaoId, i.id), `${i.nome} está com a palavra.`)}
                  onAusente={() => agir(() => marcarAusente(token, a.sessaoId, i.id), `Ausência registrada: ${i.nome}.`)}
                />
              ))}
            </ol>
          )}
        </div>
      </section>

      {podeInscreverPresencial(a) && (
        <InscreverPresencial
          enviando={enviando}
          onInscrever={(corpo) => agir(() => inscreverPresencial(token, a.sessaoId, corpo), "Inscrição presencial registrada.")}
        />
      )}

      {podeInscreverPresencial(a) && (
        <Ajustes
          audiencia={a}
          enviando={enviando}
          onAjustar={(ajuste, ok) => agir(() => ajustarAudiencia(token, a.sessaoId, ajuste), ok)}
        />
      )}
    </main>
  );
}

function NaPalavra({
  inscricao,
  tempoFalaSegundos,
  enviando,
  onEncerrar,
}: {
  inscricao: InscricaoOut;
  tempoFalaSegundos: number;
  enviando: boolean;
  onEncerrar: (tempoUsadoSegundos: number) => void;
}) {
  const agora = useAgora(true);
  const { tempo, relogio } = cronometroDaFala(inscricao, tempoFalaSegundos, agora);
  const esgotado = tempo.situacao === "esgotado";
  return (
    <section className="bloco tribuna" aria-labelledby="ma-palavra">
      <div className="bloco-cabeca">
        <h2 id="ma-palavra">Com a palavra</h2>
        <span className="chip chip-info">{inscricao.protocolo}</span>
      </div>
      <div className="bloco-corpo">
        <div className="cronometro" role="group" aria-label="Cronômetro da fala">
          <div className="crono-topo">
            <span className="crono-orador">{quemFala(inscricao)}</span>
            <span className={`crono-tempo ${tempo.situacao}`} aria-live="off" data-testid="relogio">
              {relogio}
            </span>
          </div>
          <p className="crono-limite">
            {rotuloFalaComo(inscricao.falaComo)} · {inscricao.tema}
          </p>
          {!esgotado && tempo.limite !== null && <p className="crono-limite">restantes de {formatarTempo(tempo.limite)}</p>}
          {esgotado && (
            <p role="alert" className="crono-esgotado">
              Tempo esgotado — encerre a fala.
            </p>
          )}
          <div className="crono-acoes">
            <button
              type="button"
              className="btn btn-encerrar btn-mini"
              disabled={enviando}
              onClick={() => onEncerrar(cronometroDaFala(inscricao, tempoFalaSegundos, Date.now()).decorrido)}
            >
              Encerrar fala
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}

function LinhaDaFila({
  inscricao: i,
  proxima,
  podeChamar,
  enviando,
  onChamar,
  onAusente,
}: {
  inscricao: InscricaoOut;
  proxima: boolean;
  podeChamar: boolean;
  enviando: boolean;
  onChamar: () => void;
  onAusente: () => void;
}) {
  return (
    <li className={`fila-item${i.estado === "falando" ? " orador" : ""}${i.estado === "inscrita" ? "" : " ma-fora"}`}>
      <span className="fila-ordem" aria-label={`Ordem ${i.ordem}`}>{i.ordem}</span>
      <span className="fila-quem">
        <b>{quemFala(i)}</b>
        <span className="fila-fase">
          {i.tema} · {rotuloFalaComo(i.falaComo)} · {i.origem === "presencial_secretaria" ? "inscrição presencial" : "pelo portal (gov.br)"}
        </span>
        {i.estado === "falou" && i.tempoUsadoSegundos !== null && (
          <span className="fila-fase">Falou por {formatarTempo(i.tempoUsadoSegundos)}</span>
        )}
      </span>
      <span className={`chip ${CHIP_ESTADO[i.estado] ?? "chip-neutro"}`}>
        {proxima ? "Próxima da fila" : rotuloEstadoInscricao(i.estado)}
      </span>
      {i.estado === "inscrita" && (
        <span className="fila-acoes">
          <button
            type="button"
            className="btn btn-primaria btn-mini"
            disabled={enviando || !podeChamar}
            aria-label={`Chamar ${i.nome}`}
            onClick={onChamar}
          >
            Chamar
          </button>
          <button
            type="button"
            className="btn btn-fantasma btn-mini"
            disabled={enviando}
            aria-label={`Marcar ${i.nome} como ausente`}
            onClick={onAusente}
          >
            Ausente
          </button>
        </span>
      )}
    </li>
  );
}

function InscreverPresencial({
  enviando,
  onInscrever,
}: {
  enviando: boolean;
  onInscrever: (corpo: Record<string, unknown>) => Promise<boolean>;
}) {
  const [nome, setNome] = useState("");
  const [falaComo, setFalaComo] = useState("individual");
  const [entidade, setEntidade] = useState("");
  const [tema, setTema] = useState("");
  const [falta, setFalta] = useState<string | null>(null);

  async function enviar(e: React.FormEvent) {
    e.preventDefault();
    const v = validarInscricaoPresencial({ nome, falaComo, entidade, tema });
    if (!v.ok) {
      setFalta(v.mensagem);
      return;
    }
    setFalta(null);
    if (await onInscrever(v.corpo)) {
      setNome("");
      setEntidade("");
      setTema("");
      setFalaComo("individual");
    }
  }

  return (
    <section className="bloco" aria-labelledby="ma-inscrever">
      <div className="bloco-cabeca">
        <h2 id="ma-inscrever">Inscrever quem está presente</h2>
      </div>
      <form className="bloco-corpo ma-form" onSubmit={enviar} noValidate>
        <p className="nota-mesa">A pessoa entra no fim da fila. A fala é pública e entra na ata e na transmissão — avise antes de inscrever.</p>
        <div className="ma-linha">
          <div className="campo">
            <label htmlFor="ma-nome">Nome</label>
            <input id="ma-nome" type="text" value={nome} maxLength={200} onChange={(e) => setNome(e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="ma-fala-como">Fala como</label>
            <select id="ma-fala-como" value={falaComo} onChange={(e) => setFalaComo(e.target.value)}>
              {FALA_COMO.map((f) => (
                <option key={f.valor} value={f.valor}>{f.rotulo}</option>
              ))}
            </select>
          </div>
          {falaComo !== "individual" && (
            <div className="campo">
              <label htmlFor="ma-entidade">Entidade, conselho ou movimento</label>
              <input id="ma-entidade" type="text" value={entidade} maxLength={200} onChange={(e) => setEntidade(e.target.value)} />
            </div>
          )}
        </div>
        <div className="campo">
          <label htmlFor="ma-tema">Tema da fala</label>
          <input id="ma-tema" type="text" value={tema} maxLength={LIMITE_TEMA} onChange={(e) => setTema(e.target.value)} />
        </div>
        {falta && <p role="alert" className="erro-inline">{falta}</p>}
        <div>
          <button type="submit" className="btn btn-primaria btn-mini" disabled={enviando}>
            {enviando ? "Enviando…" : "Inscrever"}
          </button>
        </div>
      </form>
    </section>
  );
}

function Ajustes({
  audiencia: a,
  enviando,
  onAjustar,
}: {
  audiencia: AudienciaOut;
  enviando: boolean;
  onAjustar: (ajuste: { tempoFalaSegundos?: number; inscricoesAbertas?: boolean; local?: string }, ok: string) => Promise<boolean>;
}) {
  const [minutos, setMinutos] = useState(String(Math.round(a.tempoFalaSegundos / 60)));
  const [local, setLocal] = useState(a.local ?? "");
  const [falta, setFalta] = useState<string | null>(null);

  function salvarTempo() {
    const t = tempoFalaEmSegundos(minutos);
    if (!t.ok) {
      setFalta(t.mensagem);
      return;
    }
    setFalta(null);
    void onAjustar({ tempoFalaSegundos: t.segundos }, `Tempo de fala: ${rotuloTempoFala(t.segundos)} por pessoa.`);
  }

  return (
    <section className="bloco" aria-labelledby="ma-ajustes">
      <div className="bloco-cabeca">
        <h2 id="ma-ajustes">Ajustes da audiência</h2>
      </div>
      <div className="bloco-corpo ma-form">
        <div className="ma-linha">
          <div className="campo">
            <label htmlFor="ma-tempo">Tempo de fala (minutos)</label>
            <div className="ma-inline">
              <input id="ma-tempo" type="number" min={TEMPO_FALA_MIN} max={TEMPO_FALA_MAX} step={1} value={minutos}
                onChange={(e) => setMinutos(e.target.value)} />
              <button type="button" className="btn btn-contorno btn-mini" disabled={enviando} onClick={salvarTempo}>
                Salvar tempo
              </button>
            </div>
            <span className="fila-fase">Vale para quem ainda não falou. A fala em curso segue o tempo com que foi chamada.</span>
          </div>
          <div className="campo">
            <label htmlFor="ma-local">Local</label>
            <div className="ma-inline">
              <input id="ma-local" type="text" value={local} onChange={(e) => setLocal(e.target.value)} />
              <button type="button" className="btn btn-contorno btn-mini" disabled={enviando || !local.trim()}
                onClick={() => void onAjustar({ local: local.trim() }, "Local atualizado.")}>
                Salvar local
              </button>
            </div>
          </div>
        </div>
        {falta && <p role="alert" className="erro-inline">{falta}</p>}
        <div className="ma-inscricoes">
          <p>
            Inscrições pelo portal: <b>{a.inscricoesAbertas ? "abertas" : "fechadas"}</b>.{" "}
            {a.inscricoesAbertas
              ? "Fechar impede novas inscrições pelo gov.br; a Mesa ainda inscreve quem estiver presente."
              : "Reabrir volta a aceitar inscrições pelo gov.br."}
          </p>
          <button
            type="button"
            className="btn btn-contorno btn-mini"
            disabled={enviando}
            onClick={() =>
              void onAjustar(
                { inscricoesAbertas: !a.inscricoesAbertas },
                a.inscricoesAbertas ? "Inscrições pelo portal fechadas." : "Inscrições pelo portal reabertas.",
              )
            }
          >
            {a.inscricoesAbertas ? "Fechar inscrições" : "Reabrir inscrições"}
          </button>
        </div>
      </div>
    </section>
  );
}
