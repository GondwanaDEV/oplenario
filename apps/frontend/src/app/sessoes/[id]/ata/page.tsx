"use client";

// A ata da sessão — Faixa A / A.6a da Track IA. Irmã de `/folha` e `/transcricao` em `app/sessoes/[id]/` (sem layout
// de grupo: o cabeçalho é desenhado aqui, mesma disciplina das irmãs).
//
// A ata é artefato LEGAL do core (§22.3.4): esta fatia é o caminho da Casa — a secretaria redige ou cola a ata e
// publica. Publicar CONGELA o texto (hash SHA-256); corrigir depois é RETIFICAR (nova versão com motivo, a anterior
// fica guardada). Por isso a publicação tem dois passos: revisar e confirmar.
//
// A.6b — o RASCUNHO DA IA: a secretaria pede, a IA redige em segundo plano (a tela acompanha), e a revisão mostra
// cada citação conferida contra a transcrição, os parágrafos sem fonte e os pontos a confirmar. "Usar este rascunho"
// leva o texto LIMPO ao mesmo editor; publicar grava a origem "gerada_automaticamente" com o rascunho de origem. Os
// pontos [confirmar: …] bloqueiam a publicação até serem resolvidos. IA fora: R-IA-1, e o caminho manual segue.

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { AuthProvider, useAuth } from "@/lib/auth";
import { useTema } from "@/lib/tema";
import { useAta } from "@/lib/use-ata";
import { faltaParaPublicar, linhaDaVersao, origemDaRedacao, semAta } from "@/lib/ata-vista";
import { avisoDoRascunho, paragrafosDoRascunho, rotuloDaCitacao, situacaoDoRascunho } from "@/lib/rascunho-ata-vista";
import { formatarHash } from "@/lib/folha-vista";
import type { AtaRascunhoConteudoOut, AtaRascunhoOut, AtaSessaoOut, AtaVersaoOut } from "@/lib/contrato-sessoes.gen";
import "./ata.css";

export default function PaginaAta() {
  const params = useParams<{ id: string }>();
  const search = useSearchParams();
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <ConteudoAta id={params.id} />
    </AuthProvider>
  );
}

type Modo = "ler" | "editar" | "confirmar" | "revisar";

export function ConteudoAta({ id }: { id: string }) {
  const { token } = useAuth();
  const r = useAta(id, token);
  const { tema, alternar } = useTema();
  // O aviso de sucesso vive AQUI: publicar relê a ata e a versão nova remonta <Ata> (key) — ali ele se perderia.
  const [aviso, setAviso] = useState<string | null>(null);

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
            <span className="tipo">Ata</span>
          </div>
          <div className="topo-dir">
            <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
              {tema === "escuro" ? "☾" : "☀"}
              <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
            </button>
          </div>
        </div>
      </header>

      <main className="envelope ata">
        <div className="ata-cabeca">
          <h1>Ata da sessão</h1>
          <p className="ata-sub">
            A ata publicada é o registro oficial da sessão e não muda. Para corrigir, publique uma retificação com o
            motivo — a versão anterior continua guardada.
          </p>
        </div>

        {r.estado === "carregando" && <p role="status">Carregando a ata…</p>}
        {r.estado === "erro" && <p role="status">Não foi possível carregar a ata desta sessão.</p>}
        {r.estado === "pronto" && (
          <Ata key={r.ata.atual?.versao.id ?? "sem"} id={id} ata={r.ata} publicar={r.publicar} enviando={r.enviando}
            aviso={aviso} setAviso={setAviso} pedirRascunho={r.pedirRascunho} lerRascunho={r.lerRascunho} />
        )}
      </main>
    </>
  );
}

type Hook = ReturnType<typeof useAta>;

function Ata({ id, ata, publicar, enviando, aviso, setAviso, pedirRascunho, lerRascunho }: {
  id: string;
  ata: AtaSessaoOut;
  publicar: Hook["publicar"];
  enviando: boolean;
  aviso: string | null;
  setAviso: (a: string | null) => void;
  pedirRascunho: Hook["pedirRascunho"];
  lerRascunho: Hook["lerRascunho"];
}) {
  const atual = ata.atual ?? null;
  const [modo, setModo] = useState<Modo>("ler");
  const [texto, setTexto] = useState(atual?.texto ?? "");
  const [motivo, setMotivo] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  // o editor aberto a partir do rascunho da IA publica com a origem e o rascunho de origem
  const [rascunhoId, setRascunhoId] = useState<string | null>(null);
  const retificando = atual !== null;
  const falta = faltaParaPublicar({ texto, motivo, retificando });
  const bloqueio = semAta(ata);
  const transcricao = `/sessoes/${encodeURIComponent(id)}/transcricao`;

  async function confirmar() {
    setErro(null);
    try {
      const recibo = await publicar({
        texto,
        motivoRetificacao: retificando ? motivo.trim() : undefined,
        rascunhoId: rascunhoId ?? undefined,
      });
      setAviso(`Ata publicada — versão ${recibo.versao}.`);
      setModo("ler");
    } catch (e) {
      setErro(e instanceof Error ? e.message : "Não foi possível publicar a ata agora.");
      setModo("editar");
    }
  }

  if (bloqueio) return <p role="status" className="ata-vazia">{bloqueio}</p>;

  if (modo === "revisar" && ata.rascunho?.rascunhoId) {
    return (
      <RevisaoDoRascunho
        rascunhoId={ata.rascunho.rascunhoId}
        lerRascunho={lerRascunho}
        voltar={() => setModo("ler")}
        usar={(c) => {
          setTexto(c.textoLimpo);
          setRascunhoId(c.rascunhoId);
          setErro(null);
          setModo("editar");
        }}
      />
    );
  }

  if (modo !== "ler") {
    const proxima = (atual?.versao.versao ?? 0) + 1;
    return (
      <section className="ata-editor" aria-label={retificando ? "Retificar a ata" : "Redigir a ata"}>
        <h2>
          {retificando ? `Retificar a ata (versão ${proxima})` : rascunhoId ? "Revisar o rascunho e publicar" : "Redigir a ata"}
        </h2>
        {rascunhoId ? (
          <p className="ata-dica">
            Texto do rascunho da IA, já sem as marcas de citação. Corrija o que for preciso e resolva cada{" "}
            <b>[confirmar: …]</b> — a ata publicada registra que partiu do rascunho.
          </p>
        ) : (
          <p className="ata-dica">
            Redija ou cole a ata aprovada pela Casa. A <Link href={transcricao}>transcrição da sessão</Link> ajuda a
            conferir quem falou e o que foi dito.
          </p>
        )}
        <div className="campo">
          <label htmlFor="ata-texto">Texto da ata</label>
          <textarea id="ata-texto" value={texto} readOnly={modo === "confirmar"} rows={16}
            onChange={(e) => setTexto(e.target.value)} />
        </div>
        {retificando && (
          <div className="campo">
            <label htmlFor="ata-motivo">Motivo da retificação</label>
            <input id="ata-motivo" value={motivo} readOnly={modo === "confirmar"} maxLength={2000}
              onChange={(e) => setMotivo(e.target.value)} placeholder="Ex.: o nome de um vereador estava errado" />
          </div>
        )}
        {erro && <p className="ata-erro" role="alert">{erro}</p>}
        {modo === "editar" ? (
          <div className="ata-acoes">
            {falta && <p className="ata-falta">{falta}</p>}
            <button type="button" className="btn btn-primaria" disabled={falta !== null} onClick={() => { setErro(null); setModo("confirmar"); }}>
              Revisar para publicar
            </button>
            <button type="button" className="btn btn-fantasma" onClick={() => { setModo("ler"); setErro(null); setTexto(atual?.texto ?? ""); setMotivo(""); setRascunhoId(null); }}>
              Cancelar
            </button>
          </div>
        ) : (
          <div className="ata-confirmacao" role="group" aria-label="Confirmar a publicação">
            <p>
              <b>Publicar a versão {proxima}?</b> Depois de publicada, a ata não muda: uma correção futura será outra
              retificação, com motivo.
            </p>
            <div className="ata-acoes">
              <button type="button" className="btn btn-primaria" disabled={enviando} onClick={confirmar}>
                {enviando ? "Publicando…" : "Publicar a ata"}
              </button>
              <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => setModo("editar")}>
                Voltar a editar
              </button>
            </div>
          </div>
        )}
      </section>
    );
  }

  return (
    <>
      {aviso && <p className="ata-ok" role="status">{aviso}</p>}
      {atual ? (
        <section className="ata-vigente" aria-label="Ata vigente">
          <MetaVersao v={atual.versao} />
          <article className="papel ata-texto">{atual.texto}</article>
          <div className="ata-acoes">
            <button type="button" className="btn btn-contorno" onClick={() => { setAviso(null); setModo("editar"); }}>
              Retificar a ata
            </button>
            <Link className="btn btn-fantasma" href={transcricao}>Ver a transcrição</Link>
          </div>
        </section>
      ) : (
        <section className="ata-vigente">
          <p className="ata-vazia">A ata desta sessão ainda não foi publicada.</p>
          <div className="ata-acoes">
            <button type="button" className="btn btn-primaria" onClick={() => { setAviso(null); setModo("editar"); }}>Redigir a ata</button>
            <Link className="btn btn-fantasma" href={transcricao}>Ver a transcrição</Link>
          </div>
          <PainelRascunho rascunho={ata.rascunho ?? null} pedir={pedirRascunho} revisar={() => { setAviso(null); setModo("revisar"); }} />
        </section>
      )}
      {ata.versoes.length > 1 && (
        <section className="ata-historico" aria-label="Versões publicadas">
          <h2>Versões publicadas</h2>
          <ol>
            {ata.versoes.map((v) => (
              <li key={v.id}>
                <MetaVersao v={v} />
              </li>
            ))}
          </ol>
        </section>
      )}
    </>
  );
}

function MetaVersao({ v }: { v: AtaVersaoOut }) {
  const h = formatarHash(v.conteudoSha256);
  return (
    <div className="ata-meta">
      <p className="ata-linha">{linhaDaVersao(v)}</p>
      <p className="ata-origem">{origemDaRedacao(v)}</p>
      {v.motivoRetificacao && <p className="ata-motivo">Motivo da retificação: {v.motivoRetificacao}</p>}
      <p className="ata-hash" title="Impressão digital do texto publicado: qualquer alteração muda este código">
        {h.algoritmo ? `${h.algoritmo.toUpperCase()} ` : ""}
        <span>{h.digest}</span>
      </p>
    </div>
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

function PainelRascunho({ rascunho, pedir, revisar }: {
  rascunho: AtaRascunhoOut | null;
  pedir: Hook["pedirRascunho"];
  revisar: () => void;
}) {
  const s = situacaoDoRascunho(rascunho);
  const [pedindo, setPedindo] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  async function pedirAgora() {
    setPedindo(true);
    setErro(null);
    try {
      await pedir();
    } catch (e) {
      setErro(e instanceof Error ? e.message : "Não foi possível pedir o rascunho agora.");
    } finally {
      setPedindo(false);
    }
  }
  const redigindo = rascunho?.situacao === "solicitado";
  return (
    <section className={redigindo ? "ata-ia ata-ia-redigindo" : "ata-ia"} aria-label="Rascunho pela IA" aria-live="polite">
      <h2>{s.titulo}</h2>
      <p>{s.detalhe}</p>
      {erro && <p className="ata-erro" role="alert">{erro}</p>}
      <div className="ata-acoes">
        {s.revisar && (
          <button type="button" className="btn btn-primaria" onClick={revisar}>Revisar o rascunho</button>
        )}
        {s.podePedir && (
          <button type="button" className={s.revisar ? "btn btn-fantasma" : "btn btn-contorno"} disabled={pedindo} onClick={pedirAgora}>
            {pedindo ? "Pedindo…" : rascunho ? "Pedir um novo rascunho" : "Pedir rascunho à IA"}
          </button>
        )}
      </div>
    </section>
  );
}

function RevisaoDoRascunho({ rascunhoId, lerRascunho, voltar, usar }: {
  rascunhoId: string;
  lerRascunho: Hook["lerRascunho"];
  voltar: () => void;
  usar: (c: AtaRascunhoConteudoOut) => void;
}) {
  const [conteudo, setConteudo] = useState<AtaRascunhoConteudoOut | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  useEffect(() => {
    let vivo = true;
    lerRascunho(rascunhoId).then(
      (c) => vivo && setConteudo(c),
      (e) => vivo && setErro(e instanceof Error ? e.message : "Não foi possível abrir o rascunho agora."),
    );
    return () => {
      vivo = false;
    };
  }, [rascunhoId, lerRascunho]);

  if (erro)
    return (
      <section className="ata-revisao" aria-label="Revisão do rascunho">
        <p className="ata-erro" role="alert">{erro}</p>
        <div className="ata-acoes">
          <button type="button" className="btn btn-contorno" onClick={voltar}>Voltar</button>
        </div>
      </section>
    );
  if (!conteudo) return <p role="status">Abrindo o rascunho…</p>;

  const aviso = avisoDoRascunho(conteudo.incerteza.nivel, conteudo.incerteza.motivos);
  const paragrafos = paragrafosDoRascunho(conteudo.texto, conteudo.citacoes, conteudo.paragrafosSemFonte);
  return (
    <section className="ata-revisao" aria-label="Revisão do rascunho">
      <h2>Rascunho da IA</h2>
      <p className="ata-dica">
        Cada número remete ao trecho da transcrição que sustenta a frase. Um rascunho é um ponto de partida: nada é
        publicado sem a sua revisão.
      </p>
      {aviso && <p className="ata-aviso" role="note">{aviso}</p>}
      <article className="papel ata-texto ata-rascunho">
        {paragrafos.map((p, i) => (
          <p key={i} className={p.semFonte ? "ata-sem-fonte" : undefined}>
            {p.semFonte && <span className="ata-selo">sem fonte — confira</span>}
            {p.partes.map((x, j) =>
              x.tipo === "texto" ? (
                <span key={j}>{x.texto}</span>
              ) : x.tipo === "confirmar" ? (
                <mark key={j} className="ata-confirmar">[confirmar: {x.texto}]</mark>
              ) : (
                <sup key={j} className={x.citacao?.status === "conferida" ? "ata-cita" : "ata-cita ata-cita-falha"}
                  title={rotuloDaCitacao(x.citacao)}>
                  {x.n}
                </sup>
              ),
            )}
          </p>
        ))}
      </article>
      {conteudo.citacoes.length > 0 && (
        <details className="ata-fontes">
          <summary>Fontes citadas ({conteudo.citacoes.length})</summary>
          <ol>
            {conteudo.citacoes.map((c, i) => (
              <li key={i} className={c.status === "conferida" ? undefined : "ata-cita-falha"}>
                <b>{rotuloDaCitacao(c)}</b>
                {c.trecho && <span> — “{c.trecho}”</span>}
              </li>
            ))}
          </ol>
        </details>
      )}
      <div className="ata-acoes">
        <button type="button" className="btn btn-primaria" onClick={() => usar(conteudo)}>Usar este rascunho</button>
        <button type="button" className="btn btn-fantasma" onClick={voltar}>Voltar</button>
      </div>
    </section>
  );
}
