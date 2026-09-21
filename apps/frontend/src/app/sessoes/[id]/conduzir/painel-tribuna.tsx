"use client";

// Painel de TRIBUNA do Comando da Mesa (§22.6 eixo F, camada de intenção) — subcomponente da tela
// /conduzir. As duas escritas que só existiam via API: inscrever um orador na fila e registrar desistência.
// IO/CAS em `use-tribuna-mesa.ts`; derivação em `tribuna-mesa-vista.ts`. Só orquestração + apresentação.
//
// A EXECUÇÃO (iniciar/encerrar fala, cronômetro) é outra frente e não entra aqui — este painel monta a fila
// e mostra quem está na tribuna agora (leitura), sem controlar o cronômetro.

import { useState } from "react";
import { useTribunaMesa } from "@/lib/use-tribuna-mesa";
import { derivarFila, membrosInscriveis, rotuloOradorAtual, FASES_TRIBUNA } from "@/lib/tribuna-mesa-vista";

export function PainelTribuna({ sessaoId, token }: { sessaoId: string; token: string | null }) {
  const { tribuna, composicao, estado, inscrever, desistir } = useTribunaMesa(sessaoId, token);

  const [vereadorId, setVereadorId] = useState("");
  const [fase, setFase] = useState(FASES_TRIBUNA[0].valor);
  const [enviando, setEnviando] = useState(false);
  const [erroAcao, setErroAcao] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  const membros = composicao?.membros ?? [];
  const inscritos = tribuna?.inscritos ?? [];
  const fila = derivarFila(inscritos, membros, tribuna?.oradorAtual ?? null);
  const oradorAtual = rotuloOradorAtual(tribuna?.oradorAtual ?? null, membros);
  const inscriveis = membrosInscriveis(membros, inscritos);

  async function onInscrever() {
    if (!vereadorId) {
      setErroAcao("Escolha o orador a inscrever.");
      return;
    }
    setEnviando(true);
    setErroAcao(null);
    setAviso(null);
    const r = await inscrever(vereadorId, fase);
    setEnviando(false);
    if (r.ok) {
      setVereadorId("");
      setAviso("Orador inscrito na tribuna.");
    } else {
      setErroAcao(r.erro);
    }
  }

  async function onDesistir(inscricaoId: string, lockVersion: number) {
    setErroAcao(null);
    setAviso(null);
    const r = await desistir(inscricaoId, lockVersion);
    if (!r.ok) setErroAcao(r.erro);
    else setAviso("Desistência registrada.");
  }

  return (
    <section className="bloco tribuna" aria-labelledby="tribuna-titulo">
      <div className="bloco-cabeca">
        <h2 id="tribuna-titulo">Tribuna</h2>
        {oradorAtual && <span className="chip chip-info">Na tribuna: {oradorAtual}</span>}
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
                  <button
                    type="button"
                    className="btn btn-fantasma btn-mini"
                    onClick={() => onDesistir(l.inscricaoId, l.lockVersion)}
                  >
                    Registrar desistência
                  </button>
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
