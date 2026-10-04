"use client";

// O COPILOTO DO RELATOR (ADR-0019, Eixo 5 / fatia 2) — o painel único dos dois editores do parecer de comissão: o da
// secretaria (/parecer/:id) e o do vereador-relator (/parecer/:id/redigir). Um botão pede à IA o rascunho da análise de
// constitucionalidade e juridicidade; o rascunho aparece AQUI, com o selo "não é parecer", os avisos sobre as normas da
// Casa, os pontos a confirmar e de onde veio cada citação. Só vai ao campo Análise quando a pessoa pede (usar, substituir
// ou acrescentar) — e nada é salvo até ela salvar pelo fluxo de sempre.

import { useState } from "react";
import {
  EXPLICACAO_COPILOTO,
  ROTULO_BOTAO_ANALISE,
  SELO_RASCUNHO_IA,
  avisoDaAnalise,
  avisoDasNormas,
  juntarAnalise,
  rotuloDaCitacao,
  type ModoDeUso,
  type ResultadoAnalise,
} from "@/lib/copiloto-analise-vista";
import { ReportarErroIa } from "@/lib/reportar-erro-ia";
import "./copiloto-analise.css";

export function CopilotoAnalise({
  pedir,
  analiseAtual,
  aoUsar,
  token = null,
}: {
  /** Pede o rascunho ao core (a borda da secretaria ou a do relator). */
  pedir: () => Promise<ResultadoAnalise>;
  /** O texto que está hoje no campo Análise. */
  analiseAtual: string;
  /** Põe o texto novo no campo Análise (o formulário continua dono do campo). */
  aoUsar: (texto: string) => void;
  /** A credencial de quem pede (a mesma do `pedir`): vai ao "Reportar erro" (8.4). */
  token?: string | null;
}) {
  const [montando, setMontando] = useState(false);
  const [resultado, setResultado] = useState<ResultadoAnalise | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  async function aoPedir() {
    setMontando(true);
    setAviso(null);
    setResultado(null);
    try {
      setResultado(await pedir());
    } finally {
      setMontando(false);
    }
  }

  function usar(modo: ModoDeUso) {
    if (resultado?.tipo !== "rascunho") return;
    aoUsar(juntarAnalise(analiseAtual, resultado.analise.texto, modo));
    setResultado(null);
    setAviso("O rascunho foi para o campo Análise. Revise, resolva os pontos a confirmar e salve.");
  }

  const campoVazio = analiseAtual.trim() === "";

  return (
    <section className="cop-analise" aria-label="Rascunho da análise pela IA">
      <div className="cop-analise-cab">
        <button type="button" className="btn btn-contorno" onClick={aoPedir} disabled={montando}>
          {montando ? "Rascunhando…" : ROTULO_BOTAO_ANALISE}
        </button>
        <p className="cop-analise-aj">{EXPLICACAO_COPILOTO}</p>
      </div>

      {aviso && (
        <p role="status" className="cop-analise-msg">
          {aviso}
        </p>
      )}
      {resultado?.tipo === "nada" && (
        <p role="status" className="cop-analise-msg">
          {resultado.mensagem}
        </p>
      )}

      {resultado?.tipo === "rascunho" && (
        <div className="cop-analise-rascunho">
          <span className="cop-analise-selo">{SELO_RASCUNHO_IA}</span>
          {[avisoDasNormas(resultado.normas), avisoDaAnalise(resultado.analise)]
            .filter((t): t is string => t !== null)
            .map((t) => (
              <p key={t} className="cop-analise-aviso" role="note">
                {t}
              </p>
            ))}
          {resultado.analise.pontosAConfirmar.length > 0 && (
            <div className="cop-analise-pontos">
              <b>A confirmar antes de salvar</b>
              <ul>
                {resultado.analise.pontosAConfirmar.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="cop-analise-texto" aria-label="Texto do rascunho">
            {resultado.analise.texto}
          </div>
          {resultado.analise.citacoes.length > 0 && (
            <details className="cop-analise-fontes">
              <summary>De onde veio ({resultado.analise.citacoes.length})</summary>
              <ol>
                {resultado.analise.citacoes.map((c, i) => (
                  <li key={i} className={c.status === "conferida" ? undefined : "cop-analise-falha"}>
                    <b>{rotuloDaCitacao(c)}</b>
                    {c.trecho && <span> — “{c.trecho}”</span>}
                    {c.status !== "conferida" && <span> (não conferida)</span>}
                  </li>
                ))}
              </ol>
            </details>
          )}
          {resultado.analise.execucaoIa && <ReportarErroIa execucaoId={resultado.analise.execucaoIa} token={token} />}
          <div className="cop-analise-acoes">
            {campoVazio ? (
              <button type="button" className="btn btn-primaria btn-mini" onClick={() => usar("substituir")}>
                Usar no campo Análise
              </button>
            ) : (
              <>
                <button type="button" className="btn btn-primaria btn-mini" onClick={() => usar("substituir")}>
                  Substituir o texto da Análise
                </button>
                <button type="button" className="btn btn-contorno btn-mini" onClick={() => usar("acrescentar")}>
                  Acrescentar ao fim da Análise
                </button>
              </>
            )}
            <button type="button" className="btn btn-fantasma btn-mini" onClick={() => setResultado(null)}>
              Descartar o rascunho
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
