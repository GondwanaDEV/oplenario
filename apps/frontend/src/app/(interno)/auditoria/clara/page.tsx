"use client";

// Rota /auditoria/clara (interno) — as CONVERSAS COM A CLARA para o controle interno (ADR-0024, "Falta: a tela do
// auditor"). O `auditor` lê o histórico da Casa inteira (`GET /agente/historico?escopo=casa`), o de uma pessoa
// (`?pessoa=`) e busca na pergunta e na resposta (`?q=`); cada leitura dessas vai à trilha como `leitura_sensivel`, e a
// tela diz isso antes de qualquer clique. Abrir uma pergunta mostra a conversa inteira só para leitura, com o registro
// (quem, quando, modelo, fontes, se confere com o hash gravado) e, por pergunta, o link para a linha dela na trilha.
//
// Arquétipo: lista filtrável + ficha ao lado. Lado a lado quando a área de conteúdo passa de 860px (container query: a
// Clara aberta empurra o conteúdo, então o que conta é a largura que sobra, não a da janela); abaixo disso a conversa
// ocupa o lugar da lista, com "Voltar à lista".
//
// O guard de papel é só de tela: o servidor nega a quem não é auditor.

import { useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import { useAuth, usePapeis } from "@/lib/auth";
import { agruparPorDia, conversaDaInteracao, metaDoItem, type ConversaGuardada, type ItemHistorico } from "@/lib/assistente-vista";
import { lerConversaGuardada, lerHistorico, type RecorteDoHistorico } from "@/lib/use-assistente";
import { horaLocal } from "@/lib/calendario-vista";
import { comToken } from "@/lib/nav";
import { GAP_DO_HISTORICO, RegistroDaConversa, RespostaClara, linkDaTrilha } from "../../clara/clara";
import { TopoInterno } from "../../topo";
import "../auditoria.css";
import "./conversas-da-clara.css";

const SEM_NOME = "Pessoa sem nome no cadastro";

type Recorte = { pessoa: { id: string; nome: string } | null; q: string | null };

type Lista = {
  /** Para qual recorte esta lista vale: trocar o filtro volta a "carregando" por derivação. */
  chave: string;
  fase: "pronto" | "erro";
  itens: ItemHistorico[];
  mais: boolean;
  antes: string | null;
  mensagem?: string;
};

type Aberta = { id: string; conversaId: string } & (
  | { fase: "carregando" }
  | { fase: "erro"; mensagem: string }
  | { fase: "pronta"; conversa: ConversaGuardada }
);

function chaveDo(r: Recorte): string {
  return JSON.stringify([r.pessoa?.id ?? null, r.q ?? null]);
}

/** O auditor pede a Casa inteira, ou só uma pessoa (o servidor ignora a pessoa quando o pedido é da Casa). */
function paraApi(r: Recorte, antes?: string | null): RecorteDoHistorico {
  return { ...(r.pessoa ? { pessoa: r.pessoa.id } : { escopo: "casa" as const }), q: r.q, antes: antes ?? null };
}

function vazioDo(r: Recorte): string {
  if (r.pessoa && r.q) return `Nenhuma pergunta de ${r.pessoa.nome} com “${r.q}” na pergunta ou na resposta.`;
  if (r.pessoa) return `${r.pessoa.nome} ainda não fez nenhuma pergunta à Clara.`;
  if (r.q) return `Nenhuma pergunta com “${r.q}” na pergunta ou na resposta.`;
  return "Ninguém da Casa perguntou nada à Clara ainda.";
}

const Fechar = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden="true">
    <path d="M18 6 6 18M6 6l12 12" />
  </svg>
);

const Olho = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

const Cadeado = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <rect x="4" y="11" width="16" height="9" rx="2" />
    <path d="M8 11V8a4 4 0 0 1 8 0v3" />
  </svg>
);

function ConversasDaCasa({ token }: { token: string | null }) {
  const ids = useId();
  const [recorte, setRecorte] = useState<Recorte>({ pessoa: null, q: null });
  const [tentativa, setTentativa] = useState(0);
  const [lista, setLista] = useState<Lista | null>(null);
  const [maisEstado, setMaisEstado] = useState<"ocioso" | "carregando" | "erro">("ocioso");
  const [texto, setTexto] = useState("");
  const [avisoBusca, setAvisoBusca] = useState("");
  const [aberta, setAberta] = useState<Aberta | null>(null);
  // o foco segue quem navega: abrir leva ao título da conversa; voltar devolve à linha de onde se saiu
  const focar = useRef<{ alvo: "conversa" } | { alvo: "linha"; id: string } | null>(null);
  const tituloDaConversa = useRef<HTMLHeadingElement>(null);

  const chave = chaveDo(recorte);

  useEffect(() => {
    let vivo = true;
    const c = chaveDo(recorte);
    void lerHistorico(token, paraApi(recorte)).then((r) => {
      if (!vivo) return;
      setLista(
        typeof r === "string"
          ? { chave: c, fase: "erro", itens: [], mais: false, antes: null, mensagem: r }
          : { chave: c, fase: "pronto", itens: r.interacoes, mais: r.mais, antes: r.antes },
      );
    });
    return () => {
      vivo = false;
    };
  }, [recorte, token, tentativa]);

  useEffect(() => {
    const f = focar.current;
    if (!f) return;
    focar.current = null;
    if (f.alvo === "conversa") tituloDaConversa.current?.focus();
    else document.getElementById(`${ids}-abrir-${f.id}`)?.focus();
  }, [aberta, ids]);

  const atual = lista && lista.chave === chave ? lista : null;

  async function carregarMais() {
    if (!atual?.mais) return;
    setMaisEstado("carregando");
    const r = await lerHistorico(token, paraApi(recorte, atual.antes));
    if (typeof r === "string") return setMaisEstado("erro");
    setLista((l) => (l && l.chave === atual.chave ? { ...l, itens: [...l.itens, ...r.interacoes], mais: r.mais, antes: r.antes } : l));
    setMaisEstado("ocioso");
  }

  function mudarRecorte(r: Recorte) {
    setMaisEstado("ocioso");
    setRecorte(r);
  }

  function buscar(e: React.FormEvent) {
    e.preventDefault();
    const t = texto.trim();
    if (t.length === 1) return setAvisoBusca("Escreva pelo menos 2 letras para buscar.");
    setAvisoBusca("");
    const q = t ? t.slice(0, 100) : null;
    if (q !== recorte.q) mudarRecorte({ ...recorte, q });
  }

  async function abrir(it: ItemHistorico) {
    focar.current = { alvo: "conversa" };
    setAberta({ id: it.id, conversaId: it.conversaId, fase: "carregando" });
    const r = await lerConversaGuardada(token, it.conversaId);
    setAberta((a) =>
      a && a.id === it.id ? (typeof r === "string" ? { ...a, fase: "erro", mensagem: r } : { ...a, fase: "pronta", conversa: r }) : a,
    );
  }

  function voltar() {
    if (aberta) focar.current = { alvo: "linha", id: aberta.id };
    setAberta(null);
  }

  const grupos = atual ? agruparPorDia(atual.itens) : [];
  const nomeDaConversa = aberta?.fase === "pronta" ? aberta.conversa.nome ?? SEM_NOME : null;

  return (
    <>
      <div className="cc-aviso" role="note">
        <span className="cc-aviso-ic"><Olho /></span>
        <div>
          <b>Cada conversa que você abre aqui fica registrada na trilha de auditoria.</b>
          <span>A lista e cada busca também: a trilha guarda quem leu as conversas de quem, e quando.</span>
        </div>
      </div>

      <div className={`cc-corpo${aberta ? " tem-conversa" : ""}`}>
        <div className="cc-grade">
          <section className="cc-col-lista" aria-labelledby={`${ids}-lista`}>
            <h2 id={`${ids}-lista`} className="sr-only">Perguntas à Clara</h2>
            <form className="cc-busca" role="search" onSubmit={buscar}>
              <label htmlFor={`${ids}-q`}>Buscar nas conversas</label>
              <div className="cc-busca-linha">
                <input
                  id={`${ids}-q`}
                  type="search"
                  maxLength={100}
                  value={texto}
                  aria-describedby={avisoBusca ? `${ids}-q-aviso` : undefined}
                  aria-invalid={avisoBusca ? true : undefined}
                  onChange={(e) => {
                    setTexto(e.target.value);
                    if (avisoBusca) setAvisoBusca("");
                  }}
                />
                <button className="btn btn-primaria btn-mini" type="submit">Buscar</button>
              </div>
              <p id={`${ids}-q-aviso`} className="cc-busca-ajuda" role={avisoBusca ? "alert" : undefined}>
                {avisoBusca || "Na pergunta e na resposta."}
              </p>
            </form>

            {(recorte.pessoa || recorte.q) && (
              <div className="cc-filtros" aria-label="Filtros em uso" role="group">
                {recorte.pessoa && (
                  <button className="cc-chip" type="button" aria-label={`Tirar o filtro: só de ${recorte.pessoa.nome}`}
                    onClick={() => mudarRecorte({ ...recorte, pessoa: null })}>
                    Só de {recorte.pessoa.nome}
                    <Fechar />
                  </button>
                )}
                {recorte.q && (
                  <button className="cc-chip" type="button" aria-label={`Tirar a busca por “${recorte.q}”`}
                    onClick={() => {
                      setTexto("");
                      mudarRecorte({ ...recorte, q: null });
                    }}>
                    Com “{recorte.q}”
                    <Fechar />
                  </button>
                )}
              </div>
            )}

            {!atual && <p className="aud-aviso" role="status">Carregando as conversas…</p>}
            {atual?.fase === "erro" && (
              <div className="cc-erro" role="alert">
                <p>{atual.mensagem}</p>
                <button className="btn btn-contorno btn-mini" type="button" onClick={() => setTentativa((n) => n + 1)}>Tentar de novo</button>
              </div>
            )}
            {atual?.fase === "pronto" && atual.itens.length === 0 && <p className="aud-vazio" role="status">{vazioDo(recorte)}</p>}

            {grupos.map((g, gi) => (
              <div key={`${g.rotulo}-${gi}`} className="cc-dia">
                <h3 id={`${ids}-dia-${gi}`}>{g.rotulo}</h3>
                <ul className="cc-lista" aria-labelledby={`${ids}-dia-${gi}`}>
                  {g.itens.map((it) => {
                    const nome = it.nome ?? SEM_NOME;
                    const escolhida = aberta?.id === it.id;
                    return (
                      <li key={it.id} className={`cc-item${escolhida ? " escolhida" : ""}`}>
                        <div className="cc-item-cabe">
                          {it.identidadeId && !recorte.pessoa ? (
                            <button className="cc-pessoa" type="button" title={`Ver só as perguntas de ${nome}`}
                              aria-label={`${nome}: ver só as perguntas desta pessoa`}
                              onClick={() => mudarRecorte({ ...recorte, pessoa: { id: it.identidadeId!, nome } })}>
                              {nome}
                            </button>
                          ) : (
                            <span className="cc-pessoa-txt">{nome}</span>
                          )}
                          <span className={`cc-meta${it.desfecho === "resposta" ? "" : " sem-resposta"}`}>{metaDoItem(it)}</span>
                        </div>
                        <button id={`${ids}-abrir-${it.id}`} className="cc-abrir" type="button" aria-current={escolhida ? "true" : undefined}
                          onClick={() => void abrir(it)}>
                          <span className="cc-q">{it.pergunta}</span>
                          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                            <path d="M9 6l6 6-6 6" />
                          </svg>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}

            {atual?.fase === "pronto" && atual.mais && (
              <div className="aud-mais">
                <button type="button" className="btn btn-contorno btn-mini" onClick={() => void carregarMais()} disabled={maisEstado === "carregando"}>
                  {maisEstado === "carregando" ? "Carregando…" : "Ver conversas anteriores"}
                </button>
                {maisEstado === "erro" && <span role="alert"> Não foi possível carregar mais. Tente de novo.</span>}
              </div>
            )}
          </section>

          <section className="cc-col-conversa" aria-labelledby={`${ids}-conversa`}>
            {!aberta ? (
              <div className="cc-escolha">
                <h2 id={`${ids}-conversa`}>Nenhuma conversa aberta</h2>
                <p>Escolha uma pergunta na lista para ler a conversa inteira, com a resposta, as fontes e o modelo usado.</p>
              </div>
            ) : (
              <>
                <button className="btn btn-contorno btn-mini cc-voltar" type="button" onClick={voltar}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                    <path d="M15 6l-6 6 6 6" />
                  </svg>
                  Voltar à lista
                </button>
                <h2 id={`${ids}-conversa`} ref={tituloDaConversa} tabIndex={-1} className="cc-titulo-conversa">
                  {nomeDaConversa ? `Conversa de ${nomeDaConversa}` : "Conversa"}
                </h2>
                {aberta.fase === "carregando" && <p className="aud-aviso" role="status">Abrindo a conversa…</p>}
                {aberta.fase === "erro" && (
                  <div className="cc-erro" role="alert">
                    <p>{aberta.mensagem}</p>
                  </div>
                )}
                {aberta.fase === "pronta" && (
                  <div className="cc-conversa">
                    <RegistroDaConversa conversa={aberta.conversa} token={token} />
                    {aberta.conversa.interacoes.map((i) => (
                      <article key={i.id} className={`ast-turno cc-turno${i.id === aberta.id ? " escolhida" : ""}`}
                        aria-current={i.id === aberta.id ? "true" : undefined} aria-label={`Pergunta das ${horaLocal(i.ocorridoEm) ?? ""}`}>
                        <div className="ast-pergunta">
                          <span className="ast-quem">{nomeDaConversa} · {horaLocal(i.ocorridoEm)}</span>
                          {i.pergunta}
                        </div>
                        <RespostaClara conversa={conversaDaInteracao(i)} token={token} guardada />
                        <Link className="cc-na-trilha"
                          href={linkDaTrilha(i.id, token)}>
                          Ver esta pergunta na trilha
                        </Link>
                      </article>
                    ))}
                  </div>
                )}
              </>
            )}
          </section>
        </div>
      </div>
    </>
  );
}

export default function PaginaConversasDaClara() {
  const { token } = useAuth();
  const { papeis, estado } = usePapeis();
  const auditor = papeis.includes("auditor");
  return (
    <>
      <TopoInterno area="Auditoria" />
      {estado === "carregando" ? null : !auditor ? (
        <main id="conteudo" className="envelope aud cc">
          <div className="cc-restrito">
            <h1>Acesso restrito</h1>
            <p>
              {estado === "erro"
                ? "Não foi possível conferir o seu acesso agora. Tente de novo em instantes."
                : "Esta tela é do controle interno da Casa."}
            </p>
          </div>
        </main>
      ) : (
        <main id="conteudo" className="envelope aud cc">
          <div className="pg-cab">
            <Link className="cc-trilha" href={comToken("/auditoria", token)}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M15 6l-6 6 6 6" />
              </svg>
              Trilha de auditoria
            </Link>
            <span className="rotulo-secao">Registro de integridade</span>
            <h1>Conversas com a Clara</h1>
            <p className="sub">
              Cada pergunta feita à Clara fica guardada com a resposta, as fontes e o modelo usado. Nada aqui se edita nem
              se apaga, e a sua leitura também fica registrada na trilha de auditoria.
            </p>
          </div>
          <ConversasDaCasa token={token} />
          <div className="nota-imut">
            <Cadeado />
            <span>
              <b>Registro da Casa.</b> {GAP_DO_HISTORICO}
            </span>
          </div>
        </main>
      )}
    </>
  );
}
