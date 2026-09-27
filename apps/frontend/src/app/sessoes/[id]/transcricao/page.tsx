"use client";

// A transcrição da sessão — Faixa A / A.3 da Track IA (ADR-0008). Irmã de `/folha` e `/chamada` em
// `app/sessoes/[id]/` (sem layout de grupo: o cabeçalho é desenhado aqui, mesma disciplina das irmãs).
//
// O texto é da IA (vive no satélite, §22.3.4) e é RASCUNHO: a tela diz isso, mostra quem falou só quando o
// Caminho C soube (a palavra concedida pela Mesa), e avisa quando é preciso revisar com atenção (§16.8). IA fora
// do ar não quebra a tela: cada gravação mostra a mensagem R-IA-1.

import { useParams, useSearchParams } from "next/navigation";
import { AuthProvider, useAuth } from "@/lib/auth";
import { useTema } from "@/lib/tema";
import { useTranscricao, type GravacaoTranscrita } from "@/lib/use-transcricao";
import { avisoDeAtencao, blocosDeFala, relogio, situacao } from "@/lib/transcricao-vista";
import "./transcricao.css";

export default function PaginaTranscricao() {
  const params = useParams<{ id: string }>();
  const search = useSearchParams();
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <ConteudoTranscricao id={params.id} />
    </AuthProvider>
  );
}

export function ConteudoTranscricao({ id }: { id: string }) {
  const { token } = useAuth();
  const r = useTranscricao(id, token);
  const { tema, alternar } = useTema();

  return (
    <>
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
          <div className="sessao-meta">
            <span className="tipo">Transcrição</span>
          </div>
          <div className="topo-dir">
            <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
              {tema === "escuro" ? "☾" : "☀"}
              <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
            </button>
          </div>
        </div>
      </header>

      <main className="envelope transc">
        <div className="transc-cabeca">
          <h1>Transcrição da sessão</h1>
          <p className="transc-sub">
            Rascunho feito pela IA a partir da gravação. Quem falou vem da palavra concedida pela Mesa; quando não
            há como saber, o trecho fica sem nome. Confira antes de usar em ata.
          </p>
        </div>

        {r.estado === "carregando" && <p role="status">Carregando a transcrição…</p>}
        {r.estado === "erro" && <p role="status">Não foi possível carregar a transcrição desta sessão.</p>}
        {r.estado === "pronto" && r.gravacoes.length === 0 && (
          <p role="status" className="transc-vazia">
            Nenhuma gravação desta sessão foi transcrita ainda. A transcrição começa quando a gravação é vinculada à
            sessão, na tela Gravações.
          </p>
        )}
        {r.estado === "pronto" &&
          r.gravacoes.map((g, i) => <Gravacao key={g.ponteiro.id} g={g} n={r.gravacoes.length > 1 ? i + 1 : null} />)}
      </main>
    </>
  );
}

function Gravacao({ g, n }: { g: GravacaoTranscrita; n: number | null }) {
  const aviso = g.conteudo ? avisoDeAtencao(g.ponteiro, g.conteudo.trechos) : null;
  return (
    <section className="transc-gravacao" aria-label={n ? `Gravação ${n}` : "Gravação"}>
      {n && <h2>Gravação {n}</h2>}
      <p className={g.ponteiro.situacao === "falhou" ? "transc-situacao transc-falha" : "transc-situacao"}>
        {situacao(g.ponteiro)}
      </p>
      {g.indisponivel && (
        <p className="transc-falha" role="alert">
          {g.indisponivel}
        </p>
      )}
      {aviso && (
        <p className="transc-aviso" role="note">
          {aviso}
        </p>
      )}
      {g.conteudo && (
        <ol className="transc-falas">
          {blocosDeFala(g.conteudo.trechos).map((b, i) => (
            <li key={i} className="transc-fala">
              <p className="transc-quem">
                <span className={b.orador ? "transc-orador" : "transc-orador transc-sem-nome"}>
                  {b.orador ?? "Orador não identificado"}
                </span>
                <span className="transc-tempo">
                  {relogio(b.inicio)}–{relogio(b.fim)}
                </span>
              </p>
              <p className="transc-texto">{b.texto}</p>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 40 40" role="img" aria-label="O Plenário">
      <circle cx="20" cy="20" r="19" fill="#FFF7EA" stroke="#A6BFA2" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#2C5638" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#3F6E92" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#C0693F" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#CFA65C" />
    </svg>
  );
}
