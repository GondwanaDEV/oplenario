"use client";

// Rota /auditoria (interno) — a TRILHA DE AUDITORIA da Casa (ADR-0017; porte de telas/trilha-auditoria.html). Sem
// guard de papel: o servidor decide o escopo pelo papel (auditor = a Casa inteira; admin_ente = os atos de acesso e os
// próprios; qualquer pessoa = a própria trilha) e a tela só diz qual recorte é. O lacre de integridade e a exportação
// são do auditor — aparecem só quando o escopo é a Casa inteira (o servidor também os nega aos outros).
//
// Desvios do design e por quê: lib/trilha-auditoria-vista.ts.

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import {
  ATORES, CLASSES, FILTRO_INICIAL, OBJETOS, PERIODOS, canal, explicacaoDoEscopo, lacre, numero, objeto, quando, quem,
  rotuloDoEscopo, seloCurto, verbo, type Filtro, type RegistroTrilha,
} from "@/lib/trilha-auditoria-vista";
import { exportarTrilha, useIntegridade, useTrilhaAuditoria } from "@/lib/use-trilha-auditoria";
import { TopoInterno } from "../topo";
import "./auditoria.css";

const CORES = ["var(--jade)", "var(--cobalto)", "#6E5A8A", "var(--telha-fundo)", "#5B7A3A"];
function cor(chave: string): string {
  let h = 0;
  for (const c of chave) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return CORES[h % CORES.length];
}

function Cadeado({ tamanho = 13 }: { tamanho?: number }) {
  return (
    <svg width={tamanho} height={tamanho} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <rect x="4" y="11" width="16" height="9" rx="2" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

function Evento({ r }: { r: RegistroTrilha }) {
  const [aberto, setAberto] = useState(false);
  const q = quando(r.em);
  const a = quem(r.ator);
  const v = verbo(r);
  const o = objeto(r);
  return (
    <li className="ev">
      <div className="quando"><b>{q.data}</b><span>{q.hora}</span></div>
      <div className="ator">
        <span className="av" style={{ background: cor(a.nome) }} aria-hidden="true">{a.iniciais}</span>
        <span className="nm"><b>{a.nome}</b><span>{a.papel}</span></span>
      </div>
      <span className={`acao a-${v.tom}`}>{v.rotulo}</span>
      <div className="obj"><b>{o.titulo}</b>{o.detalhe && <span>{o.detalhe}</span>}</div>
      <div className="origem">
        {r.ip ?? "IP não guardado"}
        <br />
        <span className="selo">selo {seloCurto(r.selo)}</span>
      </div>
      <button
        className="mais"
        type="button"
        aria-expanded={aberto}
        aria-label={`${aberto ? "Recolher" : "Ver"} detalhes do evento nº ${r.seq}`}
        onClick={() => setAberto((x) => !x)}
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"
          style={aberto ? { transform: "rotate(180deg)" } : undefined}>
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      {aberto && (
        <dl className="ev-det">
          <div><dt>Ação</dt><dd className="mono">{r.acao}</dd></div>
          <div><dt>Decisão</dt><dd>{r.decisao === "permitido" ? "Permitido" : r.decisao === "negado" ? "Negado pela política" : "Não concluído"}</dd></div>
          <div><dt>Campos alterados</dt><dd>{r.campos.length ? r.campos.join(", ") : "—"}</dd></div>
          <div><dt>Canal</dt><dd>{canal(r.canal)}</dd></div>
          <p className="selo-row">
            <Cadeado />
            <span>
              evento nº {numero(r.seq)} · selo <span className="selo">{seloCurto(r.selo)}</span>
              {r.seloAnterior ? <> · encadeado a <span className="selo">{seloCurto(r.seloAnterior)}</span></> : " · o primeiro da cadeia"}
            </span>
          </p>
        </dl>
      )}
    </li>
  );
}

export default function PaginaAuditoria() {
  const { token } = useAuth();
  const [agora] = useState(() => new Date());
  const [filtro, setFiltro] = useState<Filtro>(FILTRO_INICIAL);
  const [exportando, setExportando] = useState<"ocioso" | "enviando" | "erro">("ocioso");
  const { trilha, estado, carregarMais, maisEstado } = useTrilhaAuditoria(filtro, token, agora);
  const daCasa = trilha?.escopo === "casa";
  const integridade = useIntegridade(daCasa, token);
  const l = lacre(integridade.dados, integridade.estado);
  const periodo = PERIODOS.find((p) => p.valor === filtro.periodo)!.rotulo;

  function mudar<K extends keyof Filtro>(k: K, v: Filtro[K]) {
    setFiltro((f) => ({ ...f, [k]: v }));
  }

  async function exportar() {
    setExportando("enviando");
    try {
      await exportarTrilha(filtro, token, agora);
      setExportando("ocioso");
    } catch {
      setExportando("erro");
    }
  }

  return (
    <>
      <TopoInterno area="Auditoria" />
      <main id="conteudo" className="envelope aud">
        <div className="pg-cab">
          <span className="rotulo-secao">Registro de integridade</span>
          <h1>Trilha de auditoria</h1>
          <p className="sub">
            Cada ato no sistema fica registrado — quem fez, o quê, quando e de onde. A trilha é somente-leitura e
            encadeada: não se edita nem se apaga.
          </p>
        </div>

        {daCasa && (
          <div className={`lacre${l.quebrada ? " quebrada" : ""}`} role="status" aria-label="Estado da cadeia de integridade">
            <span className="selo-ic" aria-hidden="true"><Cadeado tamanho={22} /></span>
            <div className="lc-t"><b>{l.titulo}</b><span>{l.texto}</span></div>
            <div className="lc-cadeia">
              <b>{numero(integridade.dados?.total ?? trilha?.totalDaCasa ?? 0)}</b>
              <span>eventos selados</span>
            </div>
          </div>
        )}
        {trilha && explicacaoDoEscopo(trilha.escopo) && <p className="aud-escopo">{explicacaoDoEscopo(trilha.escopo)}</p>}

        <form className="filtros" aria-label="Filtrar a trilha" onSubmit={(e) => e.preventDefault()}>
          <div className="campo">
            <label htmlFor="f-per">Período</label>
            <select id="f-per" value={filtro.periodo} onChange={(e) => mudar("periodo", e.target.value as Filtro["periodo"])}>
              {PERIODOS.map((p) => <option key={p.valor} value={p.valor}>{p.rotulo}</option>)}
            </select>
          </div>
          <div className="campo">
            <label htmlFor="f-ator">Ator</label>
            <select id="f-ator" value={filtro.ator} onChange={(e) => mudar("ator", e.target.value)}>
              {ATORES.map((p) => <option key={p.valor} value={p.valor}>{p.rotulo}</option>)}
            </select>
          </div>
          <div className="campo">
            <label htmlFor="f-classe">Registro</label>
            <select id="f-classe" value={filtro.classe} onChange={(e) => mudar("classe", e.target.value)}>
              {CLASSES.map((p) => <option key={p.valor} value={p.valor}>{p.rotulo}</option>)}
            </select>
          </div>
          <div className="campo">
            <label htmlFor="f-obj">Objeto</label>
            <select id="f-obj" value={filtro.objeto} onChange={(e) => mudar("objeto", e.target.value)}>
              {OBJETOS.map((p) => <option key={p.valor} value={p.valor}>{p.rotulo}</option>)}
            </select>
          </div>
          {daCasa && (
            <button className="btn btn-primaria btn-mini espaco" type="button" onClick={exportar} disabled={exportando === "enviando"}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M12 3v12M8 11l4 4 4-4M4 21h16" />
              </svg>
              {exportando === "enviando" ? "Exportando…" : "Exportar trilha"}
            </button>
          )}
        </form>
        {exportando === "erro" && <p role="alert" className="aud-aviso">Não foi possível exportar agora. Tente de novo em instantes.</p>}

        {estado === "carregando" && <p className="aud-aviso">Carregando a trilha…</p>}
        {estado === "sem-sessao" && <p role="alert" className="aud-aviso">A sua sessão expirou. Entre de novo para ver a trilha.</p>}
        {estado === "invalido" && <p role="alert" className="aud-aviso">Esse filtro não é aceito. Volte aos filtros padrão.</p>}
        {estado === "erro" && <p role="alert" className="aud-aviso">Não foi possível carregar a trilha. Tente de novo em instantes.</p>}

        {estado === "pronto" && trilha && (
          <>
            <div className="resumo-linha">
              <span><b>{numero(trilha.total)}</b> {trilha.total === 1 ? "evento" : "eventos"} em <b>{periodo}</b></span>
              <span aria-hidden="true">·</span>
              <span>{rotuloDoEscopo(trilha.escopo)}</span>
              <span aria-hidden="true">·</span>
              <span className="imut"><Cadeado />somente-leitura</span>
            </div>
            {trilha.registros.length === 0 ? (
              <p className="aud-vazio" role="status">Nenhum evento neste recorte.</p>
            ) : (
              <ul className="trilha" aria-label="Eventos de auditoria">
                {trilha.registros.map((r) => <Evento key={r.seq} r={r} />)}
              </ul>
            )}
            {trilha.proximo && (
              <div className="aud-mais">
                <button type="button" className="btn btn-contorno btn-mini" onClick={carregarMais} disabled={maisEstado === "carregando"}>
                  {maisEstado === "carregando" ? "Carregando…" : "Carregar eventos anteriores"}
                </button>
                {maisEstado === "erro" && <span role="alert"> Não foi possível carregar mais.</span>}
              </div>
            )}

            {trilha.operacao && (
              <section className="aud-operacao" aria-labelledby="aud-op-titulo">
                <h2 id="aud-op-titulo">A Operação da plataforma nesta Casa</h2>
                <p className="sub">
                  O que o operador do O Plenário fez nesta Casa. Fica na corrente selada da própria Operação — outra
                  esfera, que a Casa não escreve e o operador não reescreve.
                </p>
                {trilha.operacao.length === 0 ? (
                  <p className="aud-vazio">Nenhuma atuação da Operação nesta Casa.</p>
                ) : (
                  <ul className="trilha" aria-label="Atuação da Operação">
                    {trilha.operacao.map((a) => {
                      const q = quando(a.em);
                      return (
                        <li className="ev ev-op" key={a.selo}>
                          <div className="quando"><b>{q.data}</b><span>{q.hora}</span></div>
                          <div className="ator">
                            <span className="av" style={{ background: "#6E5A8A" }} aria-hidden="true">OP</span>
                            <span className="nm"><b>{a.operador ?? "A plataforma"}</b><span>Operação · O Plenário</span></span>
                          </div>
                          <div className="obj"><b>{a.acao.replace(/-/g, " ")}</b></div>
                          <div className="origem"><span className="selo">selo {seloCurto(a.selo)}</span></div>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>
            )}
          </>
        )}

        <div className="nota-imut">
          <Cadeado tamanho={18} />
          <span>
            <b>Por que isto importa.</b> A trilha é append-only: registros não podem ser editados nem apagados, nem por
            administradores. Cada evento é selado e encadeado ao anterior, e o selo de cada dia é publicado no portal da
            Casa — qualquer adulteração quebraria a sequência e ficaria evidente. A trilha guarda quem, o quê e quando,
            nunca o conteúdo; o IP completo fica guardado por 6 meses e aparece aqui truncado.
          </span>
        </div>
      </main>
    </>
  );
}
