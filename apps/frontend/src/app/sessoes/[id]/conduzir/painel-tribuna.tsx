"use client";

// Painel de TRIBUNA do Comando da Mesa (§22.6 eixo F/G) — subcomponente da tela /conduzir. Cobre as duas
// camadas: INTENÇÃO (inscrever na fila, registrar desistência) e EXECUÇÃO (chamar o inscrito à tribuna =
// iniciar a fala, controlar o cronômetro — pausar/retomar/+tempo/aparte — e encerrar a fala). IO/CAS em
// `use-tribuna-mesa.ts`; derivações puras em `tribuna-mesa-vista.ts` + `cronometro-mesa-vista.ts` + o
// cronômetro compartilhado `cronometro.ts` (o MESMO que o telão usa). Só orquestração + apresentação.

import { useEffect, useState } from "react";
import { useTribunaMesa, type EventoCronometroManual } from "@/lib/use-tribuna-mesa";
import { derivarFila, membrosInscriveis, rotuloOradorAtual, FASES_TRIBUNA } from "@/lib/tribuna-mesa-vista";
import { segundosDecorridos, formatarTempo } from "@/lib/cronometro";
import { estaPausado, segundosAdicionaisConcedidos, apartesConcedidos } from "@/lib/cronometro-mesa-vista";

/** Date.now() reavaliado a cada segundo (relógio ao vivo); pausa sob prefers-reduced-motion. Mesmo padrão do
 * telão (`/plenario`). */
function useAgora(): number {
  const [agora, setAgora] = useState(() => Date.now());
  useEffect(() => {
    // matchMedia pode não existir (jsdom/SSR): sem ele, só tica — o respeito a reduced-motion é um plus.
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
  }, []);
  return agora;
}

export function PainelTribuna({ sessaoId, token }: { sessaoId: string; token: string | null }) {
  const { tribuna, composicao, estado, inscrever, desistir, iniciarFala, registrarEventoCronometro, encerrarFala } =
    useTribunaMesa(sessaoId, token);
  const agora = useAgora();

  const [vereadorId, setVereadorId] = useState("");
  const [fase, setFase] = useState(FASES_TRIBUNA[0].valor);
  const [enviando, setEnviando] = useState(false);
  const [erroAcao, setErroAcao] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  const membros = composicao?.membros ?? [];
  const inscritos = tribuna?.inscritos ?? [];
  const oradorObj = tribuna?.oradorAtual ?? null;
  const marcos = tribuna?.marcosCronometro ?? [];
  const fila = derivarFila(inscritos, membros, oradorObj);
  const oradorAtualNome = rotuloOradorAtual(oradorObj, membros);
  const inscriveis = membrosInscriveis(membros, inscritos);
  const temOrador = !!oradorObj;

  async function comAcao(fn: () => Promise<{ ok: boolean; erro?: string }>, okMsg: string) {
    setEnviando(true);
    setErroAcao(null);
    setAviso(null);
    const r = await fn();
    setEnviando(false);
    if (r.ok) setAviso(okMsg);
    else setErroAcao(r.erro ?? "Não foi possível concluir.");
    return r.ok;
  }

  async function onInscrever() {
    if (!vereadorId) {
      setErroAcao("Escolha o orador a inscrever.");
      return;
    }
    const ok = await comAcao(() => inscrever(vereadorId, fase), "Orador inscrito na tribuna.");
    if (ok) setVereadorId("");
  }

  const onDesistir = (inscricaoId: string, lockVersion: number) =>
    comAcao(() => desistir(inscricaoId, lockVersion), "Desistência registrada.");

  const onChamar = (oradorId: string, faseInscricao: string, inscricaoId: string) =>
    comAcao(() => iniciarFala(oradorId, faseInscricao, { inscricaoId }), "Orador chamado à tribuna.");

  const onEvento = (tipo: EventoCronometroManual, segundos?: number, msg = "Cronômetro atualizado.") => {
    if (!oradorObj) return;
    return comAcao(() => registrarEventoCronometro(oradorObj.falaId, tipo, segundos), msg);
  };

  const onEncerrarFala = () => {
    if (!oradorObj) return;
    return comAcao(() => encerrarFala(oradorObj.falaId, oradorObj.lockVersion), "Fala encerrada.");
  };

  const pausado = estaPausado(marcos);
  const decorrido = oradorObj ? segundosDecorridos(oradorObj.iniciouEm, marcos, agora) : 0;
  const adicionais = segundosAdicionaisConcedidos(marcos);
  const apartes = apartesConcedidos(marcos);

  return (
    <section className="bloco tribuna" aria-labelledby="tribuna-titulo">
      <div className="bloco-cabeca">
        <h2 id="tribuna-titulo">Tribuna</h2>
        {oradorAtualNome && <span className="chip chip-info">Na tribuna: {oradorAtualNome}</span>}
      </div>
      <div className="bloco-corpo">
        {aviso && (
          <p role="status" className="aviso-ok">
            {aviso}
          </p>
        )}
        {erroAcao && (
          <p role="alert" className="erro-inline">
            {erroAcao}
          </p>
        )}
        {estado === "erro" && (
          <p className="nota-terminal">
            <span>Não foi possível carregar a tribuna agora.</span>
          </p>
        )}

        {/* CRONÔMETRO — camada de execução: só aparece com alguém na tribuna */}
        {oradorObj && (
          <div className="cronometro" role="group" aria-label="Cronômetro da fala">
            <div className="crono-topo">
              <span className="crono-orador">{oradorAtualNome}</span>
              <span className={pausado ? "crono-tempo pausado" : "crono-tempo"} aria-live="off">
                {formatarTempo(decorrido)}
                {pausado && <span className="crono-flag">pausado</span>}
              </span>
            </div>
            {(adicionais > 0 || apartes > 0) && (
              <p className="crono-extra">
                {adicionais > 0 && <span>+{formatarTempo(adicionais)} concedidos</span>}
                {apartes > 0 && <span>{apartes} aparte(s)</span>}
              </p>
            )}
            <div className="crono-acoes">
              <button type="button" className="btn btn-contorno btn-mini" disabled={enviando}
                onClick={() => onEvento(pausado ? "retomada" : "pausada", undefined, pausado ? "Fala retomada." : "Fala pausada.")}>
                {pausado ? "Retomar" : "Pausar"}
              </button>
              <button type="button" className="btn btn-contorno btn-mini" disabled={enviando}
                onClick={() => onEvento("tempo_adicional_concedido", 60, "1 min concedido.")}>
                +1 min
              </button>
              <button type="button" className="btn btn-contorno btn-mini" disabled={enviando}
                onClick={() => onEvento("aparte_concedido", undefined, "Aparte concedido.")}>
                Aparte
              </button>
              <button type="button" className="btn btn-encerrar btn-mini" disabled={enviando} onClick={onEncerrarFala}>
                Encerrar fala
              </button>
            </div>
          </div>
        )}

        <h3 className="tribuna-sub">Fila de oradores</h3>
        {fila.length === 0 ? (
          <p className="nota-mesa">
            <span>Ninguém inscrito. Inscreva um orador abaixo.</span>
          </p>
        ) : (
          <ol className="fila">
            {fila.map((l) => (
              <li key={l.inscricaoId} className={l.ehOrador ? "fila-item orador" : "fila-item"}>
                <span className="fila-ordem">{l.ordem}</span>
                <span className="fila-quem">
                  <b>{l.nome}</b>
                  <span className="fila-fase">{l.faseRotulo}</span>
                </span>
                {l.ehOrador ? (
                  <span className="chip chip-info">Falando</span>
                ) : (
                  <span className="fila-acoes">
                    {!temOrador && (
                      <button
                        type="button"
                        className="btn btn-primaria btn-mini"
                        disabled={enviando}
                        onClick={() => onChamar(l.vereadorId, l.fase, l.inscricaoId)}
                      >
                        Chamar à tribuna
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn btn-fantasma btn-mini"
                      disabled={enviando}
                      onClick={() => onDesistir(l.inscricaoId, l.lockVersion)}
                    >
                      Registrar desistência
                    </button>
                  </span>
                )}
              </li>
            ))}
          </ol>
        )}

        <div className="tribuna-inscrever">
          <h3 className="tribuna-sub">Inscrever orador</h3>
          {inscriveis.length === 0 ? (
            <p className="nota-mesa">
              <span>Todos os vereadores da composição já estão na fila.</span>
            </p>
          ) : (
            <div className="tribuna-form">
              <div className="campo">
                <label htmlFor="orador">Orador</label>
                <select id="orador" value={vereadorId} onChange={(e) => setVereadorId(e.target.value)}>
                  <option value="">— escolha o orador —</option>
                  {inscriveis.map((m) => (
                    <option key={m.vereadorId} value={m.vereadorId}>
                      {m.nomeParlamentar ?? `Vereador(a) ${m.vereadorId.slice(0, 8)}`}
                      {m.cargoMesa ? ` · ${m.cargoMesa}` : ""}
                    </option>
                  ))}
                </select>
              </div>
              <div className="campo">
                <label htmlFor="fase-tribuna">Fase</label>
                <select id="fase-tribuna" value={fase} onChange={(e) => setFase(e.target.value)}>
                  {FASES_TRIBUNA.map((f) => (
                    <option key={f.valor} value={f.valor}>
                      {f.rotulo}
                    </option>
                  ))}
                </select>
              </div>
              <button type="button" className="btn btn-primaria" disabled={enviando} onClick={onInscrever}>
                {enviando ? "Inscrevendo…" : "Inscrever"}
              </button>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
