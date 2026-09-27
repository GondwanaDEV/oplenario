"use client";

// O corpo da /busca: o campo, o filtro por tipo e a lista. Recebe o `token` por prop para ser testável sem
// <AuthProvider> (mesmo split de fila-gravacoes.tsx). A IA fora do ar não esvazia a tela: o backend responde com as
// proposições pela ementa e o aviso, e o aviso aparece acima da lista (R-IA-1).

import Link from "next/link";
import { useState } from "react";
import { comToken } from "@/lib/nav";
import { useBusca } from "@/lib/use-busca";
import {
  FILTROS,
  destacar,
  resumoDaBusca,
  termosDaConsulta,
  vistaResultado,
  type TipoResultado,
} from "@/lib/busca-vista";

function Trecho({ texto, termos }: { texto: string; termos: string[] }) {
  return (
    <p className="busca-trecho">
      {destacar(texto, termos).map((p, i) => (p.destaque ? <mark key={i}>{p.texto}</mark> : <span key={i}>{p.texto}</span>))}
    </p>
  );
}

export function PainelBusca({ token = null }: { token?: string | null }) {
  const { estado, buscar } = useBusca(token);
  const [consulta, setConsulta] = useState("");
  const [filtro, setFiltro] = useState(0);

  function enviar(tipos: TipoResultado[]) {
    const q = consulta.trim();
    if (q.length >= 2) buscar(q, tipos);
  }

  const termos = estado.fase === "pronto" ? termosDaConsulta(estado.consulta) : [];
  const resultados = estado.fase === "pronto" ? estado.resposta.resultados.map(vistaResultado) : [];

  return (
    <main className="envelope busca">
      <header className="busca-cabeca">
        <h1>Buscar na Casa</h1>
        <p className="busca-sub">
          Proposições e o que foi dito em plenário. Escreva do seu jeito — a busca entende o assunto, não só a palavra
          exata.
        </p>
      </header>

      <form
        className="busca-form"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          enviar(FILTROS[filtro].tipos);
        }}
      >
        <label className="busca-rotulo" htmlFor="busca-q">
          O que você procura?
        </label>
        <div className="busca-linha">
          <input
            id="busca-q"
            type="search"
            value={consulta}
            maxLength={300}
            placeholder="Ex.: merenda nas escolas do interior"
            onChange={(e) => setConsulta(e.target.value)}
          />
          <button type="submit" className="btn btn-primario" disabled={consulta.trim().length < 2 || estado.fase === "buscando"}>
            {estado.fase === "buscando" ? "Buscando…" : "Buscar"}
          </button>
        </div>
        <div className="busca-filtros" role="group" aria-label="Mostrar">
          {FILTROS.map((f, i) => (
            <button
              key={f.rotulo}
              type="button"
              className="busca-filtro"
              aria-pressed={i === filtro}
              onClick={() => {
                setFiltro(i);
                if (estado.fase === "pronto" || estado.fase === "erro") enviar(f.tipos);
              }}
            >
              {f.rotulo}
            </button>
          ))}
        </div>
      </form>

      {estado.fase === "buscando" && <p role="status">Buscando…</p>}
      {estado.fase === "erro" && (
        <p className="busca-erro" role="alert">
          {estado.mensagem}
        </p>
      )}

      {estado.fase === "pronto" && (
        <section aria-label="Resultados">
          {estado.resposta.aviso && (
            <p className="busca-aviso" role="status">
              {estado.resposta.aviso}
            </p>
          )}
          <p className="busca-resumo" role="status">
            {resumoDaBusca(resultados.length)}
          </p>
          {resultados.length > 0 && (
            <ol className="busca-lista">
              {resultados.map((r) => (
                <li key={r.chave} className="busca-item">
                  <p className="busca-tipo">{r.rotuloTipo}</p>
                  <Link href={comToken(r.href, token)} className="busca-titulo" prefetch={false}>
                    {r.titulo}
                  </Link>
                  {r.detalhe && <p className="busca-detalhe">{r.detalhe}</p>}
                  {r.trecho && <Trecho texto={r.trecho} termos={termos} />}
                </li>
              ))}
            </ol>
          )}
        </section>
      )}
    </main>
  );
}
