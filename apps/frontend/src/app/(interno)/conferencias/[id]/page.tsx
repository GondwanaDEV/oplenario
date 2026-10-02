"use client";

// Rota /conferencias/:id (interno) — uma nota técnica da IA (Faixa B / B.8, ADR-0013). A secretaria lê o rascunho
// (cada número remete ao dispositivo que sustenta a frase, conferido pelo sistema), e decide: aproveitar — editando,
// se quiser — ou descartar. Uma decisão só; nada anda na tramitação por causa da nota.

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  TETO_TEXTO_NOTA,
  avisoDaNota,
  faltaParaAproveitar,
  linhaDaNota,
  numeroDaNota,
  textoAproveitado,
} from "@/lib/conferencias-vista";
import { decidirNota, useNotaTecnica } from "@/lib/use-conferencias";
import type { NotaTecnicaOut } from "@/lib/contrato-legislativo.gen";
import { GuardSecretaria } from "../../guard-secretaria";
import { TopoInterno } from "../../topo";
import { LeituraDaNota } from "../leitura-nota";
import "../conferencias.css";

export default function PaginaNota() {
  return (
    <GuardSecretaria>
      <Conteudo />
    </GuardSecretaria>
  );
}

type Modo = "ler" | "editar" | "confirmar-descarte";

function Conteudo() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  const { estado, setEstado } = useNotaTecnica(token, id ?? null);
  const [modo, setModo] = useState<Modo>("ler");
  const [texto, setTexto] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function decidir(desfecho: "aproveitada" | "descartada", t?: string) {
    if (!id) return;
    setEnviando(true);
    setErro(null);
    const r = await decidirNota(token, id, desfecho, t);
    setEnviando(false);
    if (r.ok) {
      setEstado({ fase: "pronto", dado: r.dado });
      setModo("ler");
    } else setErro(r.mensagem);
  }

  return (
    <>
      <TopoInterno area="Conferências" />
      <main className="envelope conf conf-nota">
        <Link className="conf-voltar" href={comToken("/conferencias", token)}>← Conferência das proposições</Link>
        {estado.fase === "carregando" && <p role="status">Carregando a nota…</p>}
        {estado.fase === "erro" && <p className="conf-erro" role="alert">{estado.mensagem}</p>}
        {estado.fase === "pronto" && (
          <Nota n={estado.dado} token={token} modo={modo} texto={texto} enviando={enviando} erro={erro}
            setTexto={setTexto}
            aproveitar={() => { setTexto(estado.dado.textoLimpo); setErro(null); setModo("editar"); }}
            descartar={() => { setErro(null); setModo("confirmar-descarte"); }}
            voltar={() => { setErro(null); setModo("ler"); }}
            decidir={decidir} />
        )}
      </main>
    </>
  );
}

function Nota({ n, token, modo, texto, enviando, erro, setTexto, aproveitar, descartar, voltar, decidir }: {
  n: NotaTecnicaOut;
  token: string | null;
  modo: Modo;
  texto: string;
  enviando: boolean;
  erro: string | null;
  setTexto: (t: string) => void;
  aproveitar: () => void;
  descartar: () => void;
  voltar: () => void;
  decidir: (d: "aproveitada" | "descartada", t?: string) => void;
}) {
  const aviso = avisoDaNota(n.incerteza, n.motivosIncerteza);
  const falta = faltaParaAproveitar(texto);
  return (
    <>
      <header className="conf-cabeca">
        <p className="conf-numero">{numeroDaNota(n)}</p>
        <h1>{n.ementa}</h1>
        <p className="conf-sub">
          {linhaDaNota(n)} ·{" "}
          <Link href={comToken(`/ficha-materia/${n.proposicaoId}`, token)}>Abrir a ficha da matéria</Link>
        </p>
      </header>

      {n.estado === "pendente" && (
        <p className="conf-selo-ia" role="note">
          <b>Rascunho produzido por IA.</b> Não é parecer nem decisão: confira cada ponto antes de aproveitar.
        </p>
      )}
      {n.estado === "pendente" && aviso && <p className="conf-aviso" role="note">{aviso}</p>}

      {n.estado === "pendente" && modo !== "editar" && <LeituraDaNota n={n} />}

      {n.estado !== "pendente" && (
        <section aria-label={n.estado === "aproveitada" ? "Nota aproveitada" : "Nota descartada"}>
          {n.estado === "aproveitada" ? (
            <article className="conf-papel">{textoAproveitado(n)}</article>
          ) : (
            <p className="conf-vazio">A secretaria descartou esta nota. Ela fica guardada só como registro.</p>
          )}
        </section>
      )}

      {erro && <p className="conf-erro" role="alert">{erro}</p>}

      {n.estado === "pendente" && modo === "ler" && (
        <div className="conf-acoes">
          <button type="button" className="btn btn-primaria" onClick={aproveitar}>Aproveitar a nota</button>
          <button type="button" className="btn btn-fantasma" onClick={descartar}>Descartar</button>
        </div>
      )}

      {n.estado === "pendente" && modo === "confirmar-descarte" && (
        <div className="conf-confirma" role="group" aria-label="Confirmar o descarte">
          <p><b>Descartar esta nota?</b> Ela sai da fila e não pode ser aproveitada depois.</p>
          <div className="conf-acoes">
            <button type="button" className="btn btn-contorno" disabled={enviando} onClick={() => decidir("descartada")}>
              {enviando ? "Descartando…" : "Descartar a nota"}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={voltar}>Voltar</button>
          </div>
        </div>
      )}

      {n.estado === "pendente" && modo === "editar" && (
        <section className="conf-editor" aria-label="Aproveitar a nota">
          <h2>Aproveitar a nota</h2>
          <p className="conf-sub">
            O texto vem sem as marcas de citação. Ajuste o que precisar: o que você guardar aqui é o texto da secretaria.
          </p>
          <label htmlFor="conf-texto">Texto da nota técnica</label>
          <textarea id="conf-texto" value={texto} rows={12} maxLength={TETO_TEXTO_NOTA + 200}
            onChange={(e) => setTexto(e.target.value)} />
          {falta && <p className="conf-falta">{falta}</p>}
          <div className="conf-acoes">
            <button type="button" className="btn btn-primaria" disabled={enviando || falta !== null}
              onClick={() => decidir("aproveitada", texto.trim() === n.textoLimpo ? undefined : texto.trim())}>
              {enviando ? "Guardando…" : "Guardar a nota"}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={voltar}>Voltar à leitura</button>
          </div>
        </section>
      )}
    </>
  );
}
