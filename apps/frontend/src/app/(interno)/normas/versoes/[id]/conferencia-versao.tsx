"use client";

// A conferência: o texto na ilha de papel, separado em dispositivos (cada um com o rótulo com que será citado), os
// pontos a conferir no alto e, enquanto espera conferência, publicar em dois passos ou descartar.

import Link from "next/link";
import { useState } from "react";
import { comToken } from "@/lib/nav";
import { ESTADO, emBlocos, marcaDoDispositivo, rotuloDaEspecie, type VersaoOut } from "@/lib/normas-vista";
import { conferirVersao, useVersao } from "@/lib/use-normas";

function dataBr(iso: string | null) {
  if (!iso) return null;
  const [a, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${a}`;
}

function Texto({ v }: { v: VersaoOut }) {
  return (
    <article className="normas-papel" aria-label="Texto separado em dispositivos">
      {emBlocos(v.dispositivos).map((b, i) => (
        <section key={i}>
          {b.agrupador && <h3 className="normas-agrupador">{b.agrupador}</h3>}
          {b.dispositivos.map((d) => (
            <p key={d.endereco} id={d.endereco} className={`normas-disp normas-disp-${d.tipo}`} title={d.rotulo}>
              {d.tipo !== "preambulo" && <b>{marcaDoDispositivo(d)} </b>}
              {d.texto}
            </p>
          ))}
        </section>
      ))}
    </article>
  );
}

export function ConferenciaVersao({ token = null, id }: { token?: string | null; id: string }) {
  const { estado, setEstado } = useVersao(token, id);
  const [confirmar, setConfirmar] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  async function decidir(decisao: "publicar" | "descartar") {
    setErro(null);
    setEnviando(true);
    const r = await conferirVersao(token, id, decisao);
    setEnviando(false);
    setConfirmar(false);
    if (r.ok) setEstado({ fase: "pronto", dado: r.dado });
    else setErro(r.mensagem);
  }

  if (estado.fase === "carregando") return <main className="envelope normas"><p role="status">Carregando…</p></main>;
  if (estado.fase === "erro")
    return <main className="envelope normas"><p className="normas-erro" role="alert">{estado.mensagem}</p></main>;
  const v = estado.dado;
  const aberta = v.versao.estado === "em_conferencia";
  return (
    <main className="envelope normas">
      <header className="normas-cabeca">
        <div>
          <p className="normas-tipo">{rotuloDaEspecie(v.norma.especie)}</p>
          <h1>{v.norma.titulo}</h1>
          <p className="normas-sub">
            <span className={`normas-estado normas-estado-${v.versao.estado}`}>{ESTADO[v.versao.estado]}</span>
            {" · "}
            {v.versao.nDispositivos} dispositivos · fonte: {v.versao.fonte}
            {v.versao.consolidadaAte && ` · consolidada até ${dataBr(v.versao.consolidadaAte)}`}
            {v.versao.decididaEm && ` · decidida em ${dataBr(v.versao.decididaEm)}`}
          </p>
        </div>
        <Link className="btn btn-fantasma" href={comToken("/normas", token)}>Voltar às normas</Link>
      </header>

      {aberta && (
        <section className="normas-conferir" aria-label="Conferência">
          <p>
            Leia o texto abaixo como o sistema o separou. Passe o mouse sobre um dispositivo para ver como ele será citado
            (ex.: “art. 12, § 1º, II”). Se algo estiver fora do lugar, descarte, corrija o texto e importe de novo.
          </p>
          {v.alertas.length > 0 && (
            <ul className="normas-alertas" aria-label="Pontos a conferir">
              {v.alertas.map((a, i) => <li key={i}>{a}</li>)}
            </ul>
          )}
          {erro && <p className="normas-erro" role="alert">{erro}</p>}
          {confirmar ? (
            <div className="normas-acoes" role="group" aria-label="Confirmar publicação">
              <p className="normas-confirma">
                Publicar esta versão? Ela passa a ser a que vale, e o assistente passa a citá-la. A versão anterior, se
                houver, fica guardada.
              </p>
              <button type="button" className="btn btn-primaria" disabled={enviando} onClick={() => void decidir("publicar")}>
                Publicar
              </button>
              <button type="button" className="btn btn-fantasma" onClick={() => setConfirmar(false)}>Voltar</button>
            </div>
          ) : (
            <div className="normas-acoes">
              <button type="button" className="btn btn-primaria" onClick={() => setConfirmar(true)}>
                Conferi o texto
              </button>
              <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => void decidir("descartar")}>
                Descartar
              </button>
            </div>
          )}
        </section>
      )}

      <Texto v={v} />
    </main>
  );
}
