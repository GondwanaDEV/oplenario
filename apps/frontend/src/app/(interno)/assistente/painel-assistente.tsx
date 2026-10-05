"use client";

// O corpo do /assistente: a conversa desta visita e o campo. Recebe o `token` por prop para ser testável sem
// <AuthProvider> (mesmo split de painel-busca.tsx). Cada resposta mostra O QUE o assistente consultou (os passos) e
// de onde veio cada frase (as citações conferidas contra o que o sistema devolveu). Assistente fora do ar não trava
// nada: a mensagem manda seguir pela tela (R-IA-1). Sob cada resposta, "Reportar erro" (feature 8.4) quando o core
// mandou o id da execução na IA.

import { useState } from "react";
import Link from "next/link";
import { comRotuloDoPasso, rotuloDoPasso, type Conversa } from "@/lib/assistente-vista";
import { avisoDoRascunho, paragrafosDoRascunho, rotuloDaCitacao } from "@/lib/rascunho-ata-vista";
import { useAssistente } from "@/lib/use-assistente";
import { comToken } from "@/lib/nav";
import { ReportarErroIa } from "@/lib/reportar-erro-ia";

export const SUGESTOES_SECRETARIA = [
  "Qual a situação do PL 11/2026?",
  "Por onde passou o PL 11/2026?",
  "O que vai ser votado na próxima sessão?",
  "Qual o quórum para derrubar um veto?",
];

const CONFIANCA_CONSULTA = "Só consulta: não protocola, não assina, não altera nada. Confira as fontes antes de usar a resposta.";

function Resposta({ conversa, token }: { conversa: Conversa; token: string | null }) {
  const { passos, resposta, indisponivel } = conversa;
  const propostas = conversa.propostas ?? [];
  const citacoes = resposta ? resposta.citacoes.map((c) => comRotuloDoPasso(c, passos)) : [];
  const aviso = resposta ? avisoDoRascunho(resposta.incerteza, resposta.contaminado ? ["conteudo_de_terceiro"] : []) : null;
  return (
    <div className="assistente-resposta">
      {passos.length > 0 && (
        <ul className="assistente-passos" aria-label="O que a Clara consultou">
          {passos.map((p, i) => (
            <li key={i} className={p.ok ? undefined : "assistente-passo-falha"}>{rotuloDoPasso(p)}</li>
          ))}
        </ul>
      )}
      {indisponivel && <p className="assistente-indisponivel" role="status">{indisponivel}</p>}
      {propostas.length > 0 && (
        <ul className="assistente-propostas" aria-label="Propostas preparadas">
          {propostas.map((p) => (
            <li key={p.id} className="assistente-proposta">
              <span className="assistente-selo">Proposta — nada foi feito ainda</span>
              <b>{p.titulo}</b>
              <Link className="btn btn-primaria" href={comToken(`/propostas/${p.id}`, token)}>
                {p.ritual === "assinatura" ? "Revisar e assinar" : "Revisar e confirmar"}
              </Link>
            </li>
          ))}
        </ul>
      )}
      {resposta && (
        <>
          {aviso && <p className="assistente-aviso" role="note">{aviso}</p>}
          <div className="assistente-texto">
            {paragrafosDoRascunho(resposta.texto, citacoes, resposta.paragrafosSemFonte).map((p, i) => (
              <p key={i} className={p.semFonte ? "assistente-sem-fonte" : undefined}>
                {p.semFonte && <span className="assistente-selo">sem fonte — confira</span>}
                {p.partes.map((x, j) =>
                  x.tipo === "citacao" ? (
                    <sup key={j} className={x.citacao?.status === "conferida" ? "assistente-cita" : "assistente-cita assistente-cita-falha"}
                      title={rotuloDaCitacao(x.citacao, "o que o sistema devolveu")}>
                      {x.n}
                    </sup>
                  ) : (
                    <span key={j}>{x.texto}</span>
                  ),
                )}
              </p>
            ))}
          </div>
          {citacoes.length > 0 && (
            <details className="assistente-fontes">
              <summary>De onde veio ({citacoes.length})</summary>
              <ol>
                {citacoes.map((c, i) => (
                  <li key={i} className={c.status === "conferida" ? undefined : "assistente-cita-falha"}>
                    <b>{rotuloDaCitacao(c, "o que o sistema devolveu")}</b>
                    {c.trecho && <span> — “{c.trecho}”</span>}
                  </li>
                ))}
              </ol>
            </details>
          )}
          <p className="assistente-selo-ia">Resposta escrita por IA a partir do sistema da Casa · {resposta.modelo}</p>
          {resposta.execucaoIa && <ReportarErroIa execucaoId={resposta.execucaoIa} token={token} />}
        </>
      )}
    </div>
  );
}

export function PainelAssistente({
  token = null,
  sugestoes = SUGESTOES_SECRETARIA,
  confianca = CONFIANCA_CONSULTA,
  publico,
}: {
  token?: string | null;
  sugestoes?: string[];
  confianca?: string;
  publico?: "secretaria" | "vereador";
}) {
  const { turnos, ocupado, perguntar } = useAssistente(token, publico);
  const [pergunta, setPergunta] = useState("");

  function enviar(texto: string) {
    const q = texto.trim();
    if (q.length < 2 || ocupado) return;
    setPergunta("");
    perguntar(q);
  }

  return (
    <main className="envelope assistente">
      <header className="assistente-cabeca">
        <div className="assistente-marca">
          <span className="assistente-glifo" aria-hidden="true">IA</span>
          <div>
            <h1>Clara</h1>
            <p className="assistente-sub">A assistente da Casa. Pergunte em palavras: ela consulta o sistema com as suas permissões e responde citando o que encontrou.</p>
          </div>
        </div>
        <p className="assistente-confianca">{confianca}</p>
      </header>

      {turnos.length === 0 && (
        <div className="assistente-sugestoes" aria-label="Sugestões">
          {sugestoes.map((s) => (
            <button key={s} type="button" className="assistente-sugestao" onClick={() => enviar(s)}>{s}</button>
          ))}
        </div>
      )}

      <ol className="assistente-conversa" aria-live="polite">
        {turnos.map((t, i) => (
          <li key={i} className="assistente-turno">
            <p className="assistente-pergunta">{t.pergunta}</p>
            {t.fase === "respondendo" && <p className="assistente-pensando" role="status">Consultando o sistema…</p>}
            {t.fase === "erro" && <p className="assistente-indisponivel" role="status">{t.mensagem}</p>}
            {t.fase === "pronto" && <Resposta conversa={t.conversa} token={token} />}
          </li>
        ))}
      </ol>

      <form
        className="assistente-form"
        onSubmit={(e) => {
          e.preventDefault();
          enviar(pergunta);
        }}
      >
        <label className="assistente-rotulo" htmlFor="assistente-q">Sua pergunta</label>
        <div className="assistente-linha">
          <input id="assistente-q" value={pergunta} maxLength={1000} placeholder="Ex.: qual a situação do PL 11/2026?"
            onChange={(e) => setPergunta(e.target.value)} />
          <button type="submit" className="btn btn-primaria" disabled={ocupado || pergunta.trim().length < 2}>Perguntar</button>
        </div>
      </form>
    </main>
  );
}
