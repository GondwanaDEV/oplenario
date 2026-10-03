"use client";

// A FICHA da prestação de contas (ADR-0021 Parte B) — porte de produto/design-system/o-plenario/telas/julgamento-contas.html
// (arquétipo cockpit/ficha). Herói = o parecer prévio do TCE; ao lado, "Como a Câmara decide" com o "N de M" que vem do
// `quorum` do servidor e o texto da CF art. 31 §2. Depois: os prazos (defesa e julgamento) com a data, a notificação e a
// defesa, os documentos, o PDL e "Incluir em pauta" — habilitado só quando o servidor diz `pautavel`; senão, o motivo
// em palavras (a pauta recusa de qualquer forma: bloqueio, não aviso). Julgada, a frase do resultado vem em destaque.
//
// As contas da MESA (`gestao_camara`) são só acompanhamento: processo e situação no TCE (editáveis pela secretaria, por
// PATCH) e documentos. Sem PDL, sem pauta, sem votação.
//
// O `[GAP]` do design ("valores e índices seguem o relatório do TCE — não cravados nesta tela") vira texto honesto:
// a tela não reproduz número nenhum da prestação; manda abrir o relatório.

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { comToken } from "@/lib/nav";
import {
  NOTA_VALORES,
  REFERENCIA_CF,
  TEXTO_CF_31_2,
  dataLegivel,
  fraseDoResultado,
  fraseDosPrecisos,
  heroiDoParecer,
  motivoEmPalavras,
  nDeM,
  prazosDaPrestacao,
  rotuloDocumento,
  rotuloEstado,
  rotuloParecer,
  textoComoDecide,
  tomDoEstado,
} from "@/lib/contas-vista";
import { tamanhoLegivel } from "@/lib/comunicacao-vista";
import {
  ROTAS_CONTAS,
  TAMANHO_MAXIMO_DO_DOCUMENTO,
  TIPOS_DOCUMENTO_UPLOAD,
  type DocumentoContas,
  type PrestacaoOut,
  type TipoDocumentoContas,
} from "@/lib/contrato-contas";
import { atualizarPrestacao, baixarDocumentoComToken, enviarDocumento, registrarNotificacao } from "@/lib/use-contas";
import { hojeLocal } from "../nova/form-nova-prestacao";

type Props = { p: PrestacaoOut; token: string | null; ehSecretaria: boolean; registrada: boolean; onMudou: () => void };

export function FichaPrestacao(props: Props) {
  return props.p.tipo === "gestao_camara" ? <FichaDaMesa {...props} /> : <FichaDoGoverno {...props} />;
}

// ---- contas de governo do Prefeito ----

function FichaDoGoverno({ p, token, ehSecretaria, registrada, onMudou }: Props) {
  const heroi = heroiDoParecer(p.parecerPrevio);
  const frase = fraseDoResultado(p);
  return (
    <>
      {registrada && p.proposicao && (
        <p className="cts-ok" role="status">
          Prestação registrada. O Projeto de Decreto Legislativo <b>{p.proposicao.rotulo}</b> foi protocolado e já tramita.{" "}
          <Link href={comToken(`/ficha-materia/${encodeURIComponent(p.proposicao.id)}`, token)}>Abrir o PDL</Link>
        </p>
      )}
      <header className="cts-cabeca">
        <span className="cts-crumb">Contas · Exercício {p.exercicio}</span>
        <h1>Julgamento das contas do Prefeito</h1>
        <p className="cts-sub">Exercício {p.exercicio}. A Câmara aprecia as contas com base no parecer prévio do Tribunal de Contas.</p>
        <p>
          <span className={`cts-chip cts-chip-${tomDoEstado(p.estado)}`}>{rotuloEstado(p.estado)}</span>
        </p>
      </header>

      {frase && (
        <section className="cts-resultado" aria-labelledby="cts-resultado-t">
          <h2 id="cts-resultado-t">Resultado do julgamento</h2>
          <p className="cts-frase">{frase}</p>
          {p.votacao && (
            <p className="cts-placar">
              Placar: {p.votacao.sim} pela rejeição (Sim) · {p.votacao.nao} contra (Não) · {p.votacao.abstencao}{" "}
              {p.votacao.abstencao === 1 ? "abstenção" : "abstenções"}
              {p.julgadaEm ? ` · julgadas em ${dataLegivel(p.julgadaEm)}` : ""}
            </p>
          )}
        </section>
      )}

      <div className="cts-grade">
        <div className="cts-coluna">
          <section className="cts-parecer" aria-labelledby="cts-parecer-t">
            <h2 className="cts-et" id="cts-parecer-t">Parecer prévio do TCE</h2>
            <div className="cts-res">
              <span className={`cts-ic${heroi.favoravel ? "" : " cts-ic-contra"}`} aria-hidden="true">
                {heroi.favoravel ? (
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"><path d="M5 12l5 5L20 6" /></svg>
                ) : (
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"><path d="M6 6l12 12M18 6L6 18" /></svg>
                )}
              </span>
              <div>
                <b>{heroi.titulo}</b>
                {heroi.complemento && <span>{heroi.complemento}</span>}
              </div>
            </div>
            <p className="cts-desc">{heroi.descricao}</p>
          </section>

          <ComoDecide p={p} />
          <Prazos p={p} />
          <NotificacaoEDefesa p={p} token={token} ehSecretaria={ehSecretaria} onMudou={onMudou} />
          <Documentos p={p} token={token} ehSecretaria={ehSecretaria} onMudou={onMudou} />
        </div>

        <aside className="cts-coluna">
          <section className="cts-card" aria-labelledby="cts-prestacao-t">
            <h2 id="cts-prestacao-t">A prestação</h2>
            <dl className="cts-dl">
              <dt>Exercício</dt>
              <dd className="mono">{p.exercicio}</dd>
              <dt>Responsável</dt>
              <dd>{p.responsavel}</dd>
              <dt>Recebida</dt>
              <dd className="mono">{dataLegivel(p.recebidaEm)}</dd>
              <dt>Processo no TCE</dt>
              <dd className="mono">{p.processoTce || "não informado"}</dd>
              <dt>Parecer do TCE</dt>
              <dd>{rotuloParecer(p.parecerPrevio) || "não informado"}</dd>
              <dt>Situação</dt>
              <dd>{rotuloEstado(p.estado)}</dd>
              <dt>PDL</dt>
              <dd>
                {p.proposicao ? (
                  <Link href={comToken(`/ficha-materia/${encodeURIComponent(p.proposicao.id)}`, token)}>{p.proposicao.rotulo}</Link>
                ) : (
                  "não protocolado"
                )}
              </dd>
            </dl>
            <p className="cts-nota-gap">
              <span className="cts-tag">[GAP]</span> {NOTA_VALORES}
            </p>
            <div className="cts-acoes">
              <RelatorioDoTce p={p} token={token} />
              <IncluirEmPauta p={p} token={token} ehSecretaria={ehSecretaria} />
            </div>
          </section>
          {ehSecretaria && <EditarTce p={p} token={token} onMudou={onMudou} comSituacao={false} />}
        </aside>
      </div>
    </>
  );
}

function ComoDecide({ p }: { p: PrestacaoOut }) {
  const nm = nDeM(p.quorum);
  const C = 2 * Math.PI * 35;
  return (
    <section className="cts-card" aria-labelledby="cts-decide-t">
      <h2 id="cts-decide-t">
        Como a Câmara decide
        <span className="cts-ref">{REFERENCIA_CF} — quórum qualificado</span>
      </h2>
      <div className="cts-quorum">
        {nm && (
          <span className="cts-anel" role="img" aria-label={nm.rotuloAcessivel}>
            <svg width="84" height="84" viewBox="0 0 84 84" aria-hidden="true">
              <circle className="cts-trilho" cx="42" cy="42" r="35" fill="none" strokeWidth="7" />
              <circle className="cts-arco" cx="42" cy="42" r="35" fill="none" strokeWidth="7" strokeDasharray={C.toFixed(1)} strokeDashoffset={(C * (1 - nm.fracao)).toFixed(1)} />
            </svg>
            <span className="cts-v" aria-hidden="true">
              <b>{nm.n}</b>
              <span>de {nm.m}</span>
            </span>
          </span>
        )}
        <p className="cts-tx">{textoComoDecide(p.parecerPrevio, p.quorum)}</p>
      </div>
      <p className="cts-pergunta">
        Na votação, a pergunta é: <b>“Rejeitar o parecer prévio do TCE?”</b> — Sim é rejeitar o parecer. Votação nominal.{" "}
        {fraseDosPrecisos(p.quorum)}
      </p>
      <blockquote className="cts-cf">
        <p>“{TEXTO_CF_31_2}”</p>
        <cite>Constituição Federal, art. 31, §2º</cite>
      </blockquote>
    </section>
  );
}

function Prazos({ p }: { p: PrestacaoOut }) {
  return (
    <section className="cts-card" aria-labelledby="cts-prazos-t">
      <h2 id="cts-prazos-t">Prazos</h2>
      <dl className="cts-prazos">
        {prazosDaPrestacao(p).map((l) => (
          <div key={l.rotulo}>
            <dt>{l.rotulo}</dt>
            <dd>
              <b>{l.valor}</b>
              {l.nota && <span>{l.nota}</span>}
            </dd>
          </div>
        ))}
      </dl>
      <p className="cts-ajuda">Os prazos seguem as regras desta Casa (padrões a conferir na Lei Orgânica), fixados no registro e na notificação.</p>
    </section>
  );
}

const MEIOS = ["Pessoalmente, com recibo", "Carta com aviso de recebimento (AR)", "Edital", "E-mail com confirmação"];

function NotificacaoEDefesa({ p, token, ehSecretaria, onMudou }: { p: PrestacaoOut; token: string | null; ehSecretaria: boolean; onMudou: () => void }) {
  const [data, setData] = useState("");
  const [meio, setMeio] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const defesas = p.documentos.filter((d) => d.tipo === "defesa");

  async function notificar(e: FormEvent) {
    e.preventDefault();
    setErro(null);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(data)) return setErro("Informe a data da notificação.");
    if (data > hojeLocal()) return setErro("A data da notificação não pode estar no futuro.");
    if (!meio.trim()) return setErro("Informe o meio da notificação.");
    setEnviando(true);
    const r = await registrarNotificacao(token, p.id, data, meio);
    setEnviando(false);
    if (!r.ok) return setErro(r.mensagem);
    setAviso("Notificação registrada. O prazo de defesa começou a correr.");
    onMudou();
  }

  return (
    <section className="cts-card" aria-labelledby="cts-defesa-t">
      <h2 id="cts-defesa-t">Notificação e defesa do responsável</h2>
      {aviso && <p className="cts-ok" role="status">{aviso}</p>}
      {p.notificadoEm ? (
        <p className="cts-texto">
          Notificado em <b>{dataLegivel(p.notificadoEm)}</b>
          {p.notificacaoMeio ? `, por ${p.notificacaoMeio}` : ""}.
          {p.prazoDefesaAte ? ` O prazo de defesa vai até ${dataLegivel(p.prazoDefesaAte)}.` : ""}
        </p>
      ) : (
        <p className="cts-texto">O responsável ainda não foi notificado. A pauta só aceita o PDL depois do prazo de defesa, que começa na notificação.</p>
      )}

      {!p.notificadoEm && ehSecretaria && (
        <form className="cts-form cts-form-linha" onSubmit={(e) => void notificar(e)} noValidate>
          <div className="cts-campo">
            <label htmlFor="cts-notif-data">Notificado em</label>
            <input id="cts-notif-data" type="date" max={hojeLocal()} value={data} onChange={(e) => setData(e.target.value)} />
          </div>
          <div className="cts-campo">
            <label htmlFor="cts-notif-meio">Meio</label>
            <input id="cts-notif-meio" list="cts-meios" maxLength={200} value={meio} onChange={(e) => setMeio(e.target.value)} />
            <datalist id="cts-meios">
              {MEIOS.map((m) => <option key={m} value={m} />)}
            </datalist>
          </div>
          <button type="submit" className="btn btn-primaria btn-mini" disabled={enviando}>
            {enviando ? "Registrando…" : "Registrar notificação"}
          </button>
        </form>
      )}

      {p.defesaJuntadaEm ? (
        <p className="cts-texto">
          Defesa juntada em <b>{dataLegivel(p.defesaJuntadaEm)}</b>
          {defesas.length > 0 ? ` (${defesas.map((d) => d.nome).join(", ")})` : ""}.
        </p>
      ) : p.notificadoEm ? (
        <p className="cts-texto">A defesa escrita ainda não foi juntada.</p>
      ) : null}

      {p.notificadoEm && ehSecretaria && p.estado !== "julgada" && (
        <EnviarArquivo
          token={token}
          prestacaoId={p.id}
          tipoFixo="defesa"
          rotulo={p.defesaJuntadaEm ? "Juntar outro arquivo da defesa" : "Arquivo da defesa escrita"}
          botao="Juntar a defesa"
          ok="Defesa juntada. A matéria pode ir à pauta."
          onEnviado={onMudou}
        />
      )}
      {erro && <p className="cts-erro" role="alert">{erro}</p>}
    </section>
  );
}

function Documentos({ p, token, ehSecretaria, onMudou }: { p: PrestacaoOut; token: string | null; ehSecretaria: boolean; onMudou: () => void }) {
  return (
    <section className="cts-card" aria-labelledby="cts-docs-t">
      <h2 id="cts-docs-t">Documentos</h2>
      {p.documentos.length === 0 ? (
        <p className="cts-texto">Nenhum documento juntado ainda.</p>
      ) : (
        <ul className="cts-docs" aria-label="Documentos da prestação">
          {p.documentos.map((d) => (
            <li key={d.id}>
              <span className="cts-doc-tipo">{rotuloDocumento(d.tipo)}</span>
              <span className="cts-doc-nome">{d.nome}</span>
              <span className="cts-doc-info">{[tamanhoLegivel(d.tamanhoBytes), d.criadoEm ? dataLegivel(d.criadoEm) : ""].filter(Boolean).join(" · ")}</span>
              <Baixar token={token} prestacaoId={p.id} doc={d} />
            </li>
          ))}
        </ul>
      )}
      {ehSecretaria && <EnviarArquivo token={token} prestacaoId={p.id} rotulo="Arquivo" botao="Juntar documento" ok="Documento juntado." onEnviado={onMudou} />}
    </section>
  );
}

function Baixar({ token, prestacaoId, doc, rotulo = "Baixar", classe = "btn btn-contorno btn-mini" }: { token: string | null; prestacaoId: string; doc: Pick<DocumentoContas, "id" | "nome">; rotulo?: string; classe?: string }) {
  const [erro, setErro] = useState<string | null>(null);
  return (
    <>
      {token ? (
        // modo dev: o token viaja só no header, então baixa pelos bytes (use-contas.ts)
        <button type="button" className={classe} aria-label={`${rotulo}: ${doc.nome}`} onClick={async () => {
          setErro(null);
          const r = await baixarDocumentoComToken(token, prestacaoId, doc);
          if (!r.ok) setErro(r.mensagem);
        }}>
          {rotulo}
        </button>
      ) : (
        <a className={classe} href={ROTAS_CONTAS.documento(prestacaoId, doc.id)} download={doc.nome} aria-label={`${rotulo}: ${doc.nome}`}>
          {rotulo}
        </a>
      )}
      {erro && <span className="cts-erro" role="alert">{erro}</span>}
    </>
  );
}

function EnviarArquivo({ token, prestacaoId, tipoFixo, rotulo, botao, ok, onEnviado }: {
  token: string | null; prestacaoId: string; tipoFixo?: TipoDocumentoContas; rotulo: string; botao: string; ok: string; onEnviado: () => void;
}) {
  const [tipo, setTipo] = useState<TipoDocumentoContas | "">(tipoFixo ?? "");
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [chaveInput, setChaveInput] = useState(0);
  const id = `cts-arq-${tipoFixo ?? "doc"}`;

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro(null);
    setAviso(null);
    if (!tipo) return setErro("Escolha o tipo do documento.");
    if (!arquivo) return setErro("Escolha o arquivo.");
    if (arquivo.size > TAMANHO_MAXIMO_DO_DOCUMENTO) return setErro("O arquivo passa de 10 MB, o limite por documento.");
    setEnviando(true);
    const r = await enviarDocumento(token, prestacaoId, tipo, arquivo);
    setEnviando(false);
    if (!r.ok) return setErro(r.mensagem);
    setAviso(ok);
    setArquivo(null);
    setChaveInput((n) => n + 1);
    if (!tipoFixo) setTipo("");
    onEnviado();
  }

  return (
    <form className="cts-form cts-form-linha" onSubmit={(e) => void enviar(e)} noValidate>
      {!tipoFixo && (
        <div className="cts-campo">
          <label htmlFor={`${id}-tipo`}>Tipo do documento</label>
          <select id={`${id}-tipo`} value={tipo} onChange={(e) => setTipo(e.target.value as TipoDocumentoContas)}>
            <option value="">— escolha —</option>
            {TIPOS_DOCUMENTO_UPLOAD.map((t) => <option key={t} value={t}>{rotuloDocumento(t)}</option>)}
          </select>
        </div>
      )}
      <div className="cts-campo">
        <label htmlFor={id}>{rotulo} (até 10 MB)</label>
        <input key={chaveInput} id={id} type="file" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} />
      </div>
      <button type="submit" className="btn btn-contorno btn-mini" disabled={enviando}>
        {enviando ? "Enviando…" : botao}
      </button>
      {aviso && <p className="cts-ok" role="status">{aviso}</p>}
      {erro && <p className="cts-erro" role="alert">{erro}</p>}
    </form>
  );
}

function RelatorioDoTce({ p, token }: { p: PrestacaoOut; token: string | null }) {
  const rel = p.documentos.find((d) => d.tipo === "relatorio_tce");
  if (!rel) {
    return (
      <span className="cts-falta">O relatório do TCE ainda não foi juntado.</span>
    );
  }
  return <Baixar token={token} prestacaoId={p.id} doc={rel} rotulo="Abrir o relatório do TCE" classe="btn btn-contorno btn-mini" />;
}

function IncluirEmPauta({ p, token, ehSecretaria }: { p: PrestacaoOut; token: string | null; ehSecretaria: boolean }) {
  if (p.estado === "julgada") return null;
  const motivo = p.pautavel ? null : motivoEmPalavras(p.motivoNaoPautavel, p);
  if (!ehSecretaria) {
    return <p className="cts-texto">{p.pautavel ? "O PDL já pode ir à pauta." : motivo}</p>;
  }
  if (p.pautavel) {
    return (
      <>
        <Link className="btn btn-primaria btn-mini" href={comToken("/pauta-convocacao", token)}>
          Incluir em pauta
        </Link>
        <p className="cts-ajuda">Na montagem da pauta, busque o {p.proposicao?.rotulo ?? "PDL"} e inclua-o na Ordem do Dia.</p>
      </>
    );
  }
  return (
    <>
      <button type="button" className="btn btn-primaria btn-mini" disabled aria-describedby="cts-motivo-pauta">
        Incluir em pauta
      </button>
      <p className="cts-motivo" id="cts-motivo-pauta">{motivo}</p>
    </>
  );
}

// ---- edição do processo / situação no TCE (PATCH) ----

function EditarTce({ p, token, onMudou, comSituacao }: { p: PrestacaoOut; token: string | null; onMudou: () => void; comSituacao: boolean }) {
  const [processo, setProcesso] = useState(p.processoTce ?? "");
  const [situacao, setSituacao] = useState(p.situacaoTce ?? "");
  const [erro, setErro] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setErro(null);
    setAviso(null);
    setEnviando(true);
    const r = await atualizarPrestacao(token, p.id, comSituacao ? { processoTce: processo, situacaoTce: situacao } : { processoTce: processo });
    setEnviando(false);
    if (!r.ok) return setErro(r.mensagem);
    setAviso("Atualizado.");
    onMudou();
  }

  return (
    <section className="cts-card" aria-labelledby="cts-tce-t">
      <h2 id="cts-tce-t">{comSituacao ? "Acompanhamento no TCE" : "Processo no TCE"}</h2>
      <form className="cts-form" onSubmit={(e) => void salvar(e)} noValidate>
        <div className="cts-campo">
          <label htmlFor="cts-tce-processo">Número do processo no TCE</label>
          <input id="cts-tce-processo" maxLength={100} value={processo} onChange={(e) => setProcesso(e.target.value)} />
        </div>
        {comSituacao && (
          <div className="cts-campo">
            <label htmlFor="cts-tce-situacao">Situação no TCE</label>
            <input id="cts-tce-situacao" maxLength={200} placeholder="Ex.: em instrução, julgada regular" value={situacao} onChange={(e) => setSituacao(e.target.value)} />
          </div>
        )}
        <button type="submit" className="btn btn-contorno btn-mini" disabled={enviando}>
          {enviando ? "Salvando…" : "Salvar"}
        </button>
        {aviso && <p className="cts-ok" role="status">{aviso}</p>}
        {erro && <p className="cts-erro" role="alert">{erro}</p>}
      </form>
    </section>
  );
}

// ---- contas de gestão da Câmara (Mesa) ----

function FichaDaMesa({ p, token, ehSecretaria, onMudou }: Props) {
  return (
    <>
      <header className="cts-cabeca">
        <span className="cts-crumb">Contas · Exercício {p.exercicio}</span>
        <h1>Contas de gestão da Câmara</h1>
        <p className="cts-sub">
          Exercício {p.exercicio}. As contas da Mesa são julgadas pelo Tribunal de Contas: aqui a Câmara acompanha o processo,
          sem Projeto de Decreto Legislativo e sem votação em plenário.
        </p>
        <p>
          <span className={`cts-chip cts-chip-${tomDoEstado(p.estado)}`}>{rotuloEstado(p.estado)}</span>
        </p>
      </header>
      <div className="cts-grade">
        <div className="cts-coluna">
          <section className="cts-card" aria-labelledby="cts-mesa-t">
            <h2 id="cts-mesa-t">Onde está o processo no TCE</h2>
            <p className="cts-frase">{p.situacaoTce || "Ainda não informada."}</p>
          </section>
          <Documentos p={p} token={token} ehSecretaria={ehSecretaria} onMudou={onMudou} />
        </div>
        <aside className="cts-coluna">
          <section className="cts-card" aria-labelledby="cts-prestacao-t">
            <h2 id="cts-prestacao-t">A prestação</h2>
            <dl className="cts-dl">
              <dt>Exercício</dt>
              <dd className="mono">{p.exercicio}</dd>
              <dt>Responsável</dt>
              <dd>{p.responsavel}</dd>
              <dt>Recebida</dt>
              <dd className="mono">{dataLegivel(p.recebidaEm)}</dd>
              <dt>Processo no TCE</dt>
              <dd className="mono">{p.processoTce || "não informado"}</dd>
            </dl>
            <p className="cts-nota-gap">
              <span className="cts-tag">[GAP]</span> {NOTA_VALORES}
            </p>
          </section>
          {ehSecretaria && <EditarTce p={p} token={token} onMudou={onMudou} comSituacao />}
        </aside>
      </div>
    </>
  );
}
