"use client";

// A Clara, a assistente da Casa, num painel retrátil que acompanha toda tela interna (prancha
// produto/design-system/o-plenario/telas/assistente-da-casa.html; ADR-0024 para o histórico).
//
// Três tamanhos do mesmo componente:
//   recolhido -> só o botão "Pergunte à Clara" no canto (Ctrl + / abre e fecha);
//   aberto    -> janela de 400px no canto, abaixo do cabeçalho, até 680px de altura; EMPURRA o conteúdo (não cobre);
//   expandido -> ocupa a área abaixo do cabeçalho, com o histórico ao lado.
// No celular (até 760px): aberto = folha que sobe até 88% da altura, com véu; expandido = tela cheia. Nos dois, o resto
// da página fica `inert` (o foco não sai da folha) e Esc ou o véu recolhem.
//
// O histórico é registro da Casa: cada pergunta fica guardada no core com a resposta, as fontes e o modelo, e não se
// apaga. A conversa guardada abre só para leitura, com o registro (quem, quando, modelo, fontes, integridade). Quem mais
// lê e por quanto tempo é `[GAP]` jurídico — a tela diz.

import { useCallback, useEffect, useId, useRef, useState, useSyncExternalStore, type RefObject } from "react";
import Link from "next/link";
import {
  agruparPorDia,
  comRotuloDoPasso,
  conversaDaInteracao,
  metaDoItem,
  rotuloDoPasso,
  sinalDeIndisponivel,
  type Conversa,
  type ConversaGuardada,
  type ItemHistorico,
} from "@/lib/assistente-vista";
import { avisoDoRascunho, paragrafosDoRascunho, rotuloDaCitacao } from "@/lib/rascunho-ata-vista";
import { diaLocal, horaLocal } from "@/lib/calendario-vista";
import { lerConversaGuardada, lerHistorico, useAssistente } from "@/lib/use-assistente";
import { comToken } from "@/lib/nav";
import { ReportarErroIa } from "@/lib/reportar-erro-ia";
import { useDicaAtual } from "./dica";
import "./clara.css";

export type Tamanho = "recolhido" | "aberto" | "expandido";
type Vista = "conversa" | "historico" | "guardada";

export const SUGESTOES_CLARA_SECRETARIA = [
  "Qual a situação do PL 11/2026?",
  "O que vai ser votado na próxima sessão?",
  "Qual o quórum para derrubar um veto?",
];

export const SUGESTOES_CLARA_VEREADOR = [
  "Qual a situação do PL 11/2026?",
  "O que vai ser votado na próxima sessão?",
  "Protocole um requerimento de informação à Secretaria de Obras sobre a reforma da praça do Centro",
];

export const AVISO_DE_REGISTRO =
  "Fica guardado no histórico da Casa: a pergunta, a resposta, as fontes e o modelo usado. A trilha de auditoria registra que você perguntou.";

export const GAP_DO_HISTORICO =
  "Nenhuma conversa se apaga: elas fazem parte do registro da Casa. Quem mais pode lê-las e por quanto tempo ficam guardadas ainda está em definição.";

const CELULAR = "(max-width: 760px)";

function assinarCelular(avisar: () => void): () => void {
  const mq = typeof window !== "undefined" ? window.matchMedia?.(CELULAR) : undefined;
  mq?.addEventListener?.("change", avisar);
  return () => mq?.removeEventListener?.("change", avisar);
}

function useCelular(): boolean {
  return useSyncExternalStore(
    assinarCelular,
    () => Boolean(window.matchMedia?.(CELULAR).matches),
    () => false,
  );
}

const Faisca = ({ tamanho = 17 }: { tamanho?: number }) => (
  <svg width={tamanho} height={tamanho} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <path d="M12 3l1.8 4.6L18 9l-4.2 1.4L12 15l-1.8-4.6L6 9l4.2-1.4z" />
  </svg>
);

const Cadeado = ({ tamanho = 14 }: { tamanho?: number }) => (
  <svg width={tamanho} height={tamanho} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <rect x="4" y="10" width="16" height="11" rx="2" />
    <path d="M8 10V7a4 4 0 0 1 8 0v3" />
  </svg>
);

/** Uma resposta da Clara: o que consultou, o texto com as citações, de onde veio, as propostas e o "Reportar erro".
 *  `guardada`: lida do histórico — a frase de indisponível é a do registro, não a do momento. */
export function RespostaClara({ conversa, token, guardada = false }: { conversa: Conversa; token: string | null; guardada?: boolean }) {
  const base = useId();
  const { passos, resposta, indisponivel } = conversa;
  const propostas = conversa.propostas ?? [];
  const citacoes = resposta ? resposta.citacoes.map((c) => comRotuloDoPasso(c, passos)) : [];
  const aviso = resposta ? avisoDoRascunho(resposta.incerteza, resposta.contaminado ? ["conteudo_de_terceiro"] : []) : null;
  const sinal = indisponivel && !guardada ? sinalDeIndisponivel(indisponivel, Boolean(conversa.interacaoId)) : null;
  const ancora = (n: number) => `${base}-fonte-${n}`;
  return (
    <div className="ast-resposta">
      {sinal && (
        <div className="sinal sinal-atencao" role="status">
          <div className="sinal-corpo">
            <p className="sinal-titulo">{sinal.titulo}</p>
            <p className="sinal-ctx">{sinal.texto}</p>
          </div>
        </div>
      )}
      {indisponivel && guardada && <p className="ast-incerteza">{indisponivel}</p>}
      {(resposta || propostas.length > 0) && (
        <span className="selo-ia">
          <Faisca tamanho={14} />
          Resposta gerada por IA
        </span>
      )}
      {passos.length > 0 && (
        <ul className="ast-passos" aria-label="O que a Clara consultou">
          {passos.map((p, i) => (
            <li key={i} className={p.ok ? undefined : "falha"}>{rotuloDoPasso(p)}</li>
          ))}
        </ul>
      )}
      {propostas.map((p) => (
        <div key={p.id} className="ast-proposta">
          <p className="aviso">Proposta: nada foi feito ainda</p>
          <b>{p.titulo}</b>
          <Link className="btn btn-primaria btn-mini" href={comToken(`/propostas/${p.id}`, token)}>
            {p.ritual === "assinatura" ? "Revisar e assinar" : "Revisar e confirmar"}
          </Link>
        </div>
      ))}
      {resposta && (
        <>
          {paragrafosDoRascunho(resposta.texto, citacoes, resposta.paragrafosSemFonte).map((p, i) => (
            <p key={i} className={p.semFonte ? "ast-sem-fonte" : undefined}>
              {p.semFonte && <span className="ast-selo-sem-fonte">Sem fonte — confira</span>}
              {p.partes.map((x, j) =>
                x.tipo === "citacao" ? (
                  <a key={j} href={`#${ancora(x.n ?? 0)}`} className={x.citacao?.status === "conferida" ? "cit" : "cit cit-falha"}
                    aria-label={`Fonte ${x.n}`} title={rotuloDaCitacao(x.citacao, "o que o sistema devolveu")}>
                    {x.n}
                  </a>
                ) : (
                  <span key={j}>{x.texto}</span>
                ),
              )}
            </p>
          ))}
          {aviso && <p className="ast-incerteza" role="note">{aviso}</p>}
          {citacoes.length > 0 && (
            <ol className="ast-fontes" aria-label="Fontes">
              {citacoes.map((c, i) => (
                <li key={i} id={ancora(i + 1)} className={c.status === "conferida" ? undefined : "cit-falha"}>
                  <b>{rotuloDaCitacao(c, "o que o sistema devolveu")}</b>
                  {c.trecho && <span> — “{c.trecho}”</span>}
                </li>
              ))}
            </ol>
          )}
          <div className="ast-confianca">
            <span>Confira as fontes antes de usar a resposta.</span>
            {resposta.execucaoIa && <ReportarErroIa execucaoId={resposta.execucaoIa} token={token} />}
          </div>
        </>
      )}
      {!resposta && propostas.length > 0 && <p className="ast-confianca">Você lê, ajusta e decide.</p>}
    </div>
  );
}

/** O histórico carregado até aqui. `fechado` = ainda não lido (ou velho: uma resposta nova chegou depois). */
type EstadoHistorico = {
  fase: "fechado" | "carregando" | "pronto" | "erro";
  itens: ItemHistorico[];
  mais: boolean;
  antes: string | null;
  /** A busca em curso (nil = todas as conversas). */
  q: string | null;
  mensagem?: string;
};

const HISTORICO_FECHADO: EstadoHistorico = { fase: "fechado", itens: [], mais: false, antes: null, q: null };

/** A linha desta pergunta na trilha de auditoria (cada pergunta à Clara tem a sua: `interacao_assistente` + o id). */
export function linkDaTrilha(interacaoId: string, token: string | null): string {
  return comToken(`/auditoria?recurso-tipo=interacao_assistente&recurso-id=${encodeURIComponent(interacaoId)}`, token);
}

function ListaDoHistorico({
  estado,
  atual,
  idBase,
  aoAbrir,
  aoNova,
  aoMais,
  aoBuscar,
}: {
  estado: EstadoHistorico;
  atual: string | null;
  idBase: string;
  aoAbrir: (conversaId: string) => void;
  aoNova: () => void;
  aoMais: () => void;
  /** Busca na pergunta e na resposta; nil limpa a busca. */
  aoBuscar: (q: string | null) => void;
}) {
  const itens = estado.itens;
  const grupos = agruparPorDia(itens);
  const [termo, setTermo] = useState(estado.q ?? "");
  const curto = termo.trim().length > 0 && termo.trim().length < 2;
  return (
    <div className="ast-hist">
      <form
        className="busca"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          const t = termo.trim();
          if (t.length >= 2) aoBuscar(t);
          else if (t.length === 0) aoBuscar(null);
        }}
      >
        <label htmlFor={`${idBase}-busca`}>Buscar nas suas conversas</label>
        <div className="linha-busca">
          <input id={`${idBase}-busca`} type="search" value={termo} maxLength={100} onChange={(e) => setTermo(e.target.value)}
            aria-describedby={curto ? `${idBase}-busca-dica` : undefined} />
          <button className="btn btn-contorno btn-mini" type="submit">Buscar</button>
        </div>
        {curto && <p className="ast-nota" id={`${idBase}-busca-dica`}>Escreva pelo menos 2 letras.</p>}
      </form>
      {estado.q ? (
        <p className="ast-nota ast-busca-ativa">
          <span>Conversas com “{estado.q}”</span>
          <button className="ast-limpar" type="button" onClick={() => { setTermo(""); aoBuscar(null); }}>Limpar busca</button>
        </p>
      ) : (
        <button className="btn btn-contorno" type="button" onClick={aoNova}>Nova conversa</button>
      )}
      {estado.fase === "carregando" && itens.length === 0 && <p className="ast-nota" role="status">Abrindo o histórico…</p>}
      {estado.fase === "pronto" && itens.length === 0 && (
        <p className="ast-nota" role="status">
          {estado.q ? `Nenhuma conversa com “${estado.q}”.` : "Nenhuma conversa ainda. As suas perguntas à Clara aparecem aqui."}
        </p>
      )}
      {grupos.map((g, gi) => (
        <div key={`${g.rotulo}-${gi}`}>
          <h3 id={`${idBase}-dia-${gi}`}>{g.rotulo}</h3>
          <ul aria-labelledby={`${idBase}-dia-${gi}`}>
            {g.itens.map((it) => (
              <li key={it.id}>
                <button type="button" aria-current={it.conversaId === atual ? "true" : undefined} onClick={() => aoAbrir(it.conversaId)}>
                  <span className="q">{it.pergunta}</span>
                  <span className="meta">{metaDoItem(it)}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}
      {estado.fase === "erro" && (
        <div className="ast-nota" role="status">
          <p>{estado.mensagem}</p>
          <button className="btn btn-contorno btn-mini" type="button" onClick={aoMais}>Tentar de novo</button>
        </div>
      )}
      {estado.fase === "pronto" && estado.mais && (
        <button className="btn btn-contorno btn-mini" type="button" onClick={aoMais}>Ver conversas anteriores</button>
      )}
      {estado.fase === "carregando" && itens.length > 0 && <p className="ast-nota" role="status">Abrindo…</p>}
      <p className="ast-gap">{GAP_DO_HISTORICO}</p>
    </div>
  );
}

function quando(c: ConversaGuardada): string {
  const primeira = c.interacoes[0]?.ocorridoEm;
  const ultima = c.interacoes[c.interacoes.length - 1]?.ocorridoEm;
  if (!primeira) return "";
  const dia = diaLocal(primeira);
  const data = dia ? `${dia.slice(8, 10)}/${dia.slice(5, 7)}/${dia.slice(0, 4)}` : "";
  const h1 = horaLocal(primeira) ?? "";
  const h2 = ultima && ultima !== primeira ? horaLocal(ultima) : null;
  return `${data} ${h2 && h2 !== h1 ? `${h1}–${h2}` : h1}`.trim();
}

/** O bloco "Registro desta conversa": o que o histórico guardou e se confere com o hash gravado. */
export function RegistroDaConversa({ conversa, token }: { conversa: ConversaGuardada; token: string | null }) {
  const modelos = [...new Set(conversa.interacoes.map((i) => i.modelo).filter(Boolean))];
  const citacoes = conversa.interacoes.flatMap((i) => i.resposta?.citacoes ?? []);
  const conferidas = citacoes.filter((c) => c.status === "conferida").length;
  const integra = conversa.interacoes.every((i) => i.integra);
  const perguntas = conversa.interacoes.length;
  return (
    <div className="ast-registro" role="group" aria-label="Registro desta conversa">
      <p>
        <Cadeado />
        Registro desta conversa (somente leitura)
      </p>
      <dl>
        <dt>Quem perguntou</dt>
        <dd>{conversa.nome ?? "Pessoa sem nome no cadastro"}</dd>
        <dt>Quando</dt>
        <dd>{quando(conversa)}</dd>
        <dt>Perguntas</dt>
        <dd>{perguntas === 1 ? "1 pergunta" : `${perguntas} perguntas`}</dd>
        <dt>Modelo</dt>
        <dd>{modelos.length > 0 ? modelos.join(", ") : "Nenhum (sem resposta)"}</dd>
        <dt>Fontes</dt>
        <dd>{citacoes.length === 0 ? "Nenhuma citação" : `${conferidas} de ${citacoes.length} citações conferidas`}</dd>
        <dt>Integridade</dt>
        <dd className={integra ? undefined : "ast-quebra"}>
          {integra ? "Confere com o que foi gravado" : "NÃO confere com o que foi gravado — avise o controle interno"}
        </dd>
        <dt>Trilha</dt>
        <dd>
          {perguntas === 1 ? (
            <Link href={linkDaTrilha(conversa.interacoes[0].id, token)}>Ver na trilha de auditoria</Link>
          ) : (
            "Uma linha por pergunta: o link fica em cada uma"
          )}
        </dd>
      </dl>
    </div>
  );
}

type EstadoGuardada = { fase: "carregando"; id: string } | { fase: "erro"; id: string; mensagem: string } | { fase: "pronta"; conversa: ConversaGuardada };

export function Clara({
  token,
  publico,
  moldura,
  tamanhoInicial = "recolhido",
}: {
  token: string | null;
  publico?: "secretaria" | "vereador";
  /** A área da página que a Clara empurra; no celular ela fica `inert` enquanto a folha está aberta. */
  moldura?: RefObject<HTMLElement | null>;
  tamanhoInicial?: Tamanho;
}) {
  const ids = useId();
  const dica = useDicaAtual();
  const [tamanho, setTamanho] = useState<Tamanho>(tamanhoInicial);
  const [vista, setVista] = useState<Vista>("conversa");
  const [pergunta, setPergunta] = useState("");
  const [historico, setHistorico] = useState<EstadoHistorico>(HISTORICO_FECHADO);
  const [guardada, setGuardada] = useState<EstadoGuardada | null>(null);
  const [aviso, setAviso] = useState("");
  const { turnos, ocupado, perguntar, novaConversa } = useAssistente(token, publico);
  const celular = useCelular();
  const lancador = useRef<HTMLButtonElement>(null);
  const campo = useRef<HTMLTextAreaElement>(null);
  const painel = useRef<HTMLElement>(null);
  const corpo = useRef<HTMLDivElement>(null);
  const focarAoAbrir = useRef(false);

  const mudarTamanho = useCallback((t: Tamanho, focar = true) => {
    focarAoAbrir.current = focar;
    setTamanho(t);
  }, []);

  // O tamanho vai para o <html>: é o que o CSS usa para empurrar a página.
  useEffect(() => {
    document.documentElement.dataset.clara = tamanho;
  }, [tamanho]);

  // A altura do cabeçalho (o expandido começa abaixo dele) e a da barra de comando fixa no rodapé (`.comando` do
  // chassi: o botão da Clara sobe acima dela, para não cobrir a ação principal da tela). Os dois são da PÁGINA, que
  // troca a cada navegação e muda de altura quando a fonte chega ou a linha quebra: mede de novo a cada mudança no
  // DOM da página (uma vez por quadro) e no resize.
  useEffect(() => {
    const raiz = document.documentElement;
    let quadro = 0;
    const medir = () => {
      quadro = 0;
      // o cabeçalho das telas internas (.topo) ou do app do vereador (.app-topo)
      const topo = document.querySelector<HTMLElement>(".topo, .app-topo");
      raiz.style.setProperty("--topo-altura", `${topo?.offsetHeight ?? 0}px`);
      // as barras fixas no rodapé: a de comando das telas internas (.comando) e as abas do app do vereador (.tabbar);
      // do topo da mais alta até o fim da janela (não o offsetHeight: a barra pode não encostar no rodapé)
      let altura = 0;
      for (const barra of document.querySelectorAll<HTMLElement>(".comando, .tabbar")) {
        const r = barra.getBoundingClientRect();
        if (r.height > 0) altura = Math.max(altura, Math.round(window.innerHeight - r.top));
      }
      raiz.style.setProperty("--comando-altura", `${altura}px`);
    };
    const agendar = () => {
      if (!quadro) quadro = requestAnimationFrame(medir);
    };
    medir();
    const observador = typeof MutationObserver !== "undefined" ? new MutationObserver(agendar) : null;
    observador?.observe(moldura?.current ?? document.body, { childList: true, subtree: true });
    window.addEventListener("resize", agendar);
    return () => {
      if (quadro) cancelAnimationFrame(quadro);
      observador?.disconnect();
      window.removeEventListener("resize", agendar);
      raiz.style.removeProperty("--topo-altura");
      raiz.style.removeProperty("--comando-altura");
    };
  }, [moldura]);
  useEffect(
    () => () => {
      delete document.documentElement.dataset.clara;
    },
    [],
  );

  // Foco: abrir leva ao campo; recolher devolve ao botão.
  useEffect(() => {
    if (!focarAoAbrir.current) return;
    focarAoAbrir.current = false;
    if (tamanho === "recolhido") lancador.current?.focus();
    else if (vista === "conversa") campo.current?.focus();
    else painel.current?.querySelector<HTMLElement>(".ast-corpo button, .ast-corpo a")?.focus();
  }, [tamanho, vista]);

  // No celular a folha é modal: o resto da página fica inerte.
  useEffect(() => {
    const m = moldura?.current;
    if (!m) return;
    m.toggleAttribute("inert", celular && tamanho !== "recolhido");
    return () => m.removeAttribute("inert");
  }, [moldura, celular, tamanho]);

  // Ctrl + / abre e recolhe de qualquer lugar; Esc recolhe quando o foco está na Clara (ou na folha do celular).
  useEffect(() => {
    const tecla = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === "/") {
        e.preventDefault();
        mudarTamanho(tamanho === "recolhido" ? "aberto" : "recolhido");
      } else if (e.key === "Escape" && tamanho !== "recolhido" && !e.defaultPrevented) {
        if (celular || painel.current?.contains(document.activeElement)) mudarTamanho("recolhido");
      }
    };
    document.addEventListener("keydown", tecla);
    return () => document.removeEventListener("keydown", tecla);
  }, [tamanho, celular, mudarTamanho]);

  // `continuar`: a próxima página (depois do último item carregado); sem ele, do começo. `q`: a busca (undefined =
  // mantém a atual; nil = todas).
  const carregarHistorico = useCallback(
    async (continuar = false, q?: string | null) => {
      const base: EstadoHistorico = continuar ? historico : { ...HISTORICO_FECHADO, q: q === undefined ? historico.q : q };
      setHistorico({ ...base, fase: "carregando" });
      const r = await lerHistorico(token, { antes: base.antes, q: base.q });
      if (typeof r === "string") setHistorico({ ...base, fase: "erro", mensagem: r });
      else setHistorico({ fase: "pronto", itens: [...base.itens, ...r.interacoes], mais: r.mais, antes: r.antes, q: base.q });
    },
    [token, historico],
  );

  // O histórico é lido quando aparece: ao abrir a vista de histórico e ao expandir no computador (fica ao lado).
  function garantirHistorico() {
    if (historico.fase === "fechado") void carregarHistorico();
  }

  // A conversa nova rola para o fim.
  useEffect(() => {
    if (vista === "conversa") corpo.current?.scrollTo?.({ top: corpo.current.scrollHeight });
  }, [turnos, vista]);

  function enviar(texto: string) {
    const q = texto.trim();
    if (q.length < 2 || ocupado) return;
    setPergunta("");
    if (campo.current) campo.current.style.height = "";
    setVista("conversa");
    // Resposta nova: o histórico já carregado fica velho. Se está à vista (expandido, ao lado), relê; senão, na próxima
    // vez que for visto.
    const aoLado = tamanho === "expandido" && !celular;
    void perguntar(q).then(() => {
      setAviso("A Clara respondeu.");
      if (aoLado) void carregarHistorico();
      else setHistorico(HISTORICO_FECHADO);
    });
  }

  async function abrirGuardada(conversaId: string) {
    setGuardada({ fase: "carregando", id: conversaId });
    setVista("guardada");
    setAviso("Abrindo a conversa guardada.");
    const r = await lerConversaGuardada(token, conversaId);
    setGuardada(typeof r === "string" ? { fase: "erro", id: conversaId, mensagem: r } : { fase: "pronta", conversa: r });
  }

  function comecarNova() {
    novaConversa();
    setGuardada(null);
    setVista("conversa");
    focarAoAbrir.current = true;
    setTamanho((t) => (t === "recolhido" ? "aberto" : t));
    campo.current?.focus();
  }

  /** Põe o começo da pergunta no campo e o cursor no fim; a pessoa termina e envia. */
  function comecarPergunta(inicio: string) {
    setGuardada(null);
    setVista("conversa");
    setPergunta(inicio);
    requestAnimationFrame(() => {
      const c = campo.current;
      if (!c) return;
      c.focus();
      c.setSelectionRange(inicio.length, inicio.length);
    });
  }

  function alternarHistorico() {
    const v = vista === "historico" ? "conversa" : "historico";
    setVista(v);
    setAviso(v === "historico" ? "Histórico de conversas aberto" : "");
    if (v === "historico") garantirHistorico();
  }

  const expandido = tamanho === "expandido";
  const lateral = expandido && !celular;
  const sugestoes = publico === "vereador" ? SUGESTOES_CLARA_VEREADOR : SUGESTOES_CLARA_SECRETARIA;
  const conversaAtual = guardada?.fase === "pronta" ? guardada.conversa.conversaId : guardada?.id ?? null;
  const vistaNoCorpo: Vista = lateral && vista === "historico" ? "conversa" : vista;
  const lista = (
    <ListaDoHistorico
      estado={historico}
      atual={vistaNoCorpo === "guardada" ? conversaAtual : null}
      idBase={`${ids}-${lateral ? "lat" : "corpo"}`}
      aoAbrir={(id) => void abrirGuardada(id)}
      aoNova={comecarNova}
      aoMais={() => void carregarHistorico(true)}
      aoBuscar={(q) => void carregarHistorico(false, q)}
    />
  );

  return (
    <>
      <button
        ref={lancador}
        className="ast-lancador"
        type="button"
        aria-controls={`${ids}-painel`}
        aria-expanded={tamanho !== "recolhido"}
        aria-label="Pergunte à Clara"
        title="Pergunte à Clara (Ctrl + /)"
        onClick={() => mudarTamanho("aberto")}
      >
        <span className="glifo-ia" aria-hidden="true">
          <Faisca />
        </span>
        <span>
          <span className="rotulo-longo">Pergunte à </span>Clara
        </span>
        <kbd aria-hidden="true">Ctrl /</kbd>
      </button>

      {celular && tamanho !== "recolhido" && (
        <button className="ast-veu" type="button" tabIndex={-1} aria-hidden="true" onClick={() => mudarTamanho("recolhido")} />
      )}

      <aside ref={painel} id={`${ids}-painel`} className="ast" aria-labelledby={`${ids}-titulo`} hidden={tamanho === "recolhido"}>
        <div className="ast-cabe">
          <span className="glifo-ia" aria-hidden="true">
            <Faisca />
          </span>
          <h2 className="titulo" id={`${ids}-titulo`}>Clara</h2>
          {!lateral && (
            <button className="ast-icone" type="button" aria-pressed={vista === "historico"} aria-label="Histórico de conversas"
              title="Histórico de conversas" onClick={alternarHistorico}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
                <path d="M3 3v5h5M12 7v5l3 2" />
              </svg>
            </button>
          )}
          <button className="ast-icone" type="button" aria-label={expandido ? "Reduzir a Clara para a janela" : "Expandir a Clara"}
            title={expandido ? "Reduzir" : "Expandir"} onClick={() => {
              mudarTamanho(expandido ? "aberto" : "expandido");
              if (!expandido && !celular) garantirHistorico();
            }}>
            {expandido ? (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7" />
              </svg>
            ) : (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
              </svg>
            )}
          </button>
          <button className="ast-icone" type="button" aria-label="Recolher a Clara" title="Recolher (Esc)" onClick={() => mudarTamanho("recolhido")}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </button>
          <p className="ast-sub">Assistente da Casa. Consulta o sistema com o seu acesso e não faz nada por você.</p>
        </div>

        {/* a dica da tela: só começa a pergunta — a Clara consulta o sistema, a tela nunca é fonte */}
        {dica && !lateral && (
          <div className="ast-contexto">
            <span>
              Nesta tela: <b>{dica.rotulo}</b>
            </span>
            <button className="btn btn-contorno btn-mini" type="button" onClick={() => comecarPergunta(dica.inicio)}>
              {dica.acao}
            </button>
          </div>
        )}

        {lateral && (
          <nav className="ast-lateral" aria-label="Suas conversas">
            {lista}
          </nav>
        )}

        <div ref={corpo} className="ast-corpo">
          {vistaNoCorpo === "historico" && lista}

          {vistaNoCorpo === "guardada" && guardada && (
            <>
              <button className="btn btn-contorno btn-mini ast-voltar" type="button" onClick={() => {
                setVista(lateral ? "conversa" : "historico");
                if (!lateral) garantirHistorico();
              }}>
                {lateral ? "Voltar à conversa atual" : "Voltar ao histórico"}
              </button>
              {guardada.fase === "carregando" && <p className="ast-nota" role="status">Abrindo a conversa guardada…</p>}
              {guardada.fase === "erro" && <p className="ast-nota" role="status">{guardada.mensagem}</p>}
              {guardada.fase === "pronta" && (
                <>
                  <RegistroDaConversa conversa={guardada.conversa} token={token} />
                  {guardada.conversa.interacoes.map((i) => (
                    <div key={i.id} className="ast-turno">
                      <div className="ast-pergunta">
                        <span className="ast-quem">
                          {guardada.conversa.nome ?? "Quem perguntou"} · {horaLocal(i.ocorridoEm)}
                        </span>
                        {i.pergunta}
                      </div>
                      <RespostaClara conversa={conversaDaInteracao(i)} token={token} guardada />
                      {guardada.conversa.interacoes.length > 1 && (
                        <Link className="ast-na-trilha" href={linkDaTrilha(i.id, token)}>Ver esta pergunta na trilha</Link>
                      )}
                    </div>
                  ))}
                </>
              )}
            </>
          )}

          {vistaNoCorpo === "conversa" && turnos.length === 0 && (
            <div className="ast-vazio">
              <h2>Pergunte o que precisar saber da Casa</h2>
              <p>Situação de matérias, pauta das sessões, pareceres, a Lei Orgânica e o Regimento. Cada resposta diz de onde veio.</p>
              <ul className="ast-sugestoes" aria-label="Sugestões">
                {sugestoes.map((s) => (
                  <li key={s}>
                    <button type="button" onClick={() => enviar(s)}>{s}</button>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {vistaNoCorpo === "conversa" &&
            turnos.map((t, i) => (
              <div key={i} className="ast-turno">
                <div className="ast-pergunta">
                  <span className="ast-quem">Você</span>
                  {t.pergunta}
                </div>
                {t.fase === "respondendo" && <p className="ast-pensando" role="status">Consultando o sistema…</p>}
                {t.fase === "erro" && (
                  <div className="sinal sinal-atencao" role="status">
                    <div className="sinal-corpo">
                      <p className="sinal-ctx">{t.mensagem}</p>
                    </div>
                  </div>
                )}
                {t.fase === "pronto" && <RespostaClara conversa={t.conversa} token={token} />}
              </div>
            ))}
        </div>

        {vistaNoCorpo === "conversa" && (
          <form
            className="ast-campo"
            onSubmit={(e) => {
              e.preventDefault();
              enviar(pergunta);
            }}
          >
            <label htmlFor={`${ids}-pergunta`}>Sua pergunta</label>
            <div className="linha-envio">
              <textarea
                ref={campo}
                id={`${ids}-pergunta`}
                rows={1}
                maxLength={1000}
                value={pergunta}
                onChange={(e) => {
                  setPergunta(e.target.value);
                  e.target.style.height = "";
                  e.target.style.height = `${e.target.scrollHeight}px`;
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    enviar(pergunta);
                  }
                }}
              />
              <button className="btn btn-primaria" type="submit" disabled={ocupado || pergunta.trim().length < 2}>Perguntar</button>
            </div>
            <p className="ast-registro-aviso">
              <Cadeado tamanho={12} />
              {AVISO_DE_REGISTRO}
            </p>
          </form>
        )}
        <p className="sr-only" aria-live="polite">{aviso}</p>
      </aside>
    </>
  );
}
