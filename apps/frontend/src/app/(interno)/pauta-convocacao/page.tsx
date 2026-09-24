"use client";

// Pauta/convocação (Onda C Slice C2 → docs/23 Fatia 1, "Montar a pauta") — a pauta de uma sessão agendada,
// agora EDITÁVEL: incluir matéria ou item de texto, trocar a ordem dentro da fase e retirar (com a
// classificação exclusão / retirada a pedido do autor). A convocação ao lado continua DERIVADA (não
// persistida). A tela era só-leitura porque o GET da pauta não expunha `lock-version`; o conserto T2
// (9051787) passou a expor, e toda escrita vai com o CAS do item (409 → recarrega e avisa, nunca sobrescreve).
//
// GUARD DE PAPEL: as rotas consumidas (GET /paineis/sli/sessoes, GET /sessoes/:id[/pauta], e as escritas da
// pauta) são gated `exige-papel "secretario"` no backend (403 é a authz real). Este componente replica o
// guard client-side só por UX (mesmo padrão de GuardVereador em (vereador)/layout.tsx).

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useAuth, usePapeis } from "@/lib/auth";
import { useSliSessoes } from "@/lib/use-sli-sessoes";
import { useSessaoPauta } from "@/lib/use-sessao-pauta";
import { useProposicoes } from "@/lib/use-proposicoes";
import { useEditarPauta, type FasePauta, type NovoItemPauta, type ResultadoPauta, type TipoRetirada } from "@/lib/use-editar-pauta";
import {
  FASES_DO_RITO,
  agruparPautaPorFase,
  avisoCorteSessoes,
  derivarConvocacao,
  derivarProntasForaDaPauta,
  formatarTipoSessao,
  formatarTituloSessao,
  indexarProposicoesPorId,
  resolverTituloItem,
  selecionarSessaoAlvo,
  sessoesAgendadas,
  vizinhosNoGrupo,
  type PautaItemOut,
} from "@/lib/pauta-convocacao-vista";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import type { ProposicaoResumoOut } from "@/lib/contrato-legislativo.gen";
import { FormItemPauta } from "../../sessoes/[id]/form-item-pauta";
import { TopoInterno } from "../topo";
import "./pauta-convocacao.css";

export default function PaginaPautaConvocacao() {
  const { token } = useAuth();
  const { papeis, estado } = usePapeis();
  // Modo real: segura enquanto /eu não respondeu (evita piscar "Acesso restrito" antes da resposta — mesmo
  // racional de GuardVereador em (vereador)/layout.tsx).
  if (estado === "carregando") return null;
  if (!papeis.includes("secretario")) {
    return (
      <main className="acesso-restrito">
        <h1>Acesso restrito</h1>
        <p>Esta área é exclusiva da secretaria legislativa.</p>
      </main>
    );
  }
  return <ConteudoPautaConvocacao token={token} />;
}

type Aviso = { tom: "ok" | "erro"; texto: string } | null;

const ROTULO_TIPO_TEXTO: Record<string, string> = { leitura: "Leitura", comunicado: "Comunicado", homenagem: "Homenagem" };

function ConteudoPautaConvocacao({ token }: { token: string | null }) {
  const { sessoes, sessoesTotal, estado: estadoSessoes } = useSliSessoes(token);
  // `?sessao=<id>` (vindo da Central da Casa — "Montar a pauta" de uma sessão específica) pré-seleciona a sessão;
  // id desconhecido cai no padrão de `selecionarSessaoAlvo` (a próxima agendada), nunca numa tela vazia.
  const params = useSearchParams();
  const [escolhidaId, setEscolhidaId] = useState<string | null>(() => params?.get("sessao") ?? null);
  const alvo = selecionarSessaoAlvo(sessoes ?? [], escolhidaId);
  const { sessao, pauta, estado: estadoDetalhe, recarregar } = useSessaoPauta(token, alvo?.sessaoId ?? null);
  const { dados: proposicoesDados, estado: estadoProposicoes } = useProposicoes(token, {
    pagina: 1,
    tamanho: 100,
    ordenarPor: "atualizado_em",
    ordenarDir: "desc",
  });
  const edicao = useEditarPauta(alvo?.sessaoId ?? null, token);
  const [aviso, setAviso] = useState<Aviso>(null);
  const [incluindo, setIncluindo] = useState(false);

  if (estadoSessoes === "erro" || estadoDetalhe === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar a pauta</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }

  const agendadas = sessoesAgendadas(sessoes ?? []);
  // Fatia "truncamento-familia" sitio (a): sessoesTotal é o campo autoritativo do servidor — a comparação
  // nunca é uma dedução client-side (regra 4), e null (chamada ainda em voo) não deve acusar corte.
  const avisoCorte = sessoes && sessoesTotal != null ? avisoCorteSessoes(sessoes, sessoesTotal) : null;
  const grupos = agruparPautaPorFase(pauta);
  const proposicoesPorId = indexarProposicoesPorId(proposicoesDados?.itens ?? []);
  const rail = derivarProntasForaDaPauta(proposicoesDados?.itens ?? [], proposicoesDados?.total ?? 0, pauta);
  const convocacao = sessao ? derivarConvocacao(sessao, grupos) : null;
  const idsNaPauta = new Set((pauta?.itens ?? []).flatMap((i) => (i.proposicaoId ? [i.proposicaoId] : [])));

  /** Toda escrita passa aqui: recarrega a pauta (sucesso OU falha — num 409 a tela precisa do estado real) e
   * anuncia o resultado numa linha só (role=status/alert). */
  async function aplicar(escrita: Promise<ResultadoPauta>, sucesso: string): Promise<ResultadoPauta> {
    const r = await escrita;
    recarregar();
    setAviso(r.ok ? { tom: "ok", texto: sucesso } : { tom: "erro", texto: r.erro });
    return r;
  }

  async function incluirPeloFormulario(novo: NovoItemPauta): Promise<ResultadoPauta> {
    const r = await edicao.incluir(novo);
    if (r.ok) {
      setIncluindo(false);
      recarregar();
      setAviso({ tom: "ok", texto: "Item incluído na pauta." });
    } else if (r.conflito) {
      recarregar();
    }
    return r;
  }

  return (
    <>
      <TopoInterno area="Pauta" />
      <main className="envelope">
        <div className="pg-cab">
          <div>
            <span className="eyebrow">Montar pauta</span>
            <h1>
              {sessao
                ? formatarTituloSessao(sessao)
                : !alvo && estadoSessoes === "pronto"
                  ? "Nenhuma sessão agendada"
                  : "Carregando…"}
            </h1>
            <p className="leitura">
              Inclua as matérias e os itens de leitura, ajuste a ordem e retire o que não entra. A convocação ao lado
              acompanha a pauta.
            </p>
          </div>
          {agendadas.length > 1 && (
            <span className="faceta">
              <label htmlFor="sel-sessao">Sessão</label>
              <select id="sel-sessao" value={alvo?.sessaoId ?? ""} onChange={(e) => setEscolhidaId(e.target.value)}>
                {agendadas.map((s) => (
                  <option key={s.sessaoId} value={s.sessaoId}>
                    {s.agendadaPara ? formatarData(s.agendadaPara) : s.sessaoId}
                  </option>
                ))}
              </select>
            </span>
          )}
        </div>

        {avisoCorte && <p className="aviso-corte">{avisoCorte}</p>}

        {estadoSessoes === "carregando" && <p role="status">Carregando…</p>}

        {estadoSessoes === "pronto" && !alvo && (
          <p className="col-vazia">Nenhuma sessão em estado &quot;agendada&quot; no momento.</p>
        )}

        {alvo && (
          <div className="balcao">
            <section aria-label="Pauta da sessão">
              {sessao && (
                <div className="card">
                  <h2>Dados da sessão</h2>
                  <dl className="dados-sessao">
                    <dt>Tipo</dt>
                    <dd>{formatarTipoSessao(sessao.tipoSessao)}</dd>
                    {sessao.agendadaPara && (
                      <>
                        <dt>Data</dt>
                        <dd>{formatarData(sessao.agendadaPara)}</dd>
                        <dt>Início</dt>
                        <dd>{formatarHora(sessao.agendadaPara)}</dd>
                      </>
                    )}
                  </dl>
                </div>
              )}

              <div className="card">
                <h2>Pauta</h2>
                <p className="ajuda">
                  Use as setas para ordenar dentro de cada fase. Cada mudança é gravada na hora e fica no histórico da
                  pauta.
                </p>
                <p className={`aviso-pauta${aviso ? ` aviso-${aviso.tom}` : ""}`} role={aviso?.tom === "erro" ? "alert" : "status"}>
                  {aviso?.texto ?? ""}
                </p>
                {estadoDetalhe === "carregando" && <p role="status">Carregando…</p>}
                {estadoDetalhe === "pronto" &&
                  grupos.map((grupo) => (
                    <div className="grupo" key={grupo.chave}>
                      <div className="grupo-cab">
                        <h3>{grupo.titulo}</h3>
                        <span className="cont">
                          {grupo.itens.length} {grupo.itens.length === 1 ? "item" : "itens"}
                        </span>
                      </div>
                      {grupo.itens.length === 0 && <p className="col-vazia">Nenhum item.</p>}
                      {grupo.itens.map((item, i) => (
                        <LinhaPauta
                          key={item.id}
                          item={item}
                          posicao={i + 1}
                          vizinhos={vizinhosNoGrupo(grupo.itens, i)}
                          proposicoesPorId={proposicoesPorId}
                          enviando={edicao.enviando}
                          onMover={(vizinho, direcao) => aplicar(edicao.mover(item, vizinho, direcao), "Ordem atualizada.")}
                          onRetirar={(tipo, justificativa) =>
                            aplicar(edicao.retirar(item, tipo, justificativa), "Item retirado da pauta.")
                          }
                        />
                      ))}
                    </div>
                  ))}

                <div className="add-linha">
                  {incluindo ? (
                    <FormItemPauta
                      token={token}
                      faseInicial="ordem_do_dia"
                      idsNaPauta={idsNaPauta}
                      enviando={edicao.enviando}
                      onIncluir={incluirPeloFormulario}
                      onCancelar={() => setIncluindo(false)}
                    />
                  ) : (
                    <button className="add-mat" type="button" onClick={() => setIncluindo(true)} disabled={estadoDetalhe !== "pronto"}>
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                        <path d="M12 5v14M5 12h14" />
                      </svg>
                      Incluir item na pauta
                    </button>
                  )}
                </div>
              </div>
            </section>

            <aside className="rail" aria-label="Disponíveis e convocação">
              <div className="card disp">
                <h3>Prontas, fora da pauta</h3>
                {estadoProposicoes === "carregando" && <p role="status">Carregando…</p>}
                {estadoProposicoes === "erro" && <p className="col-vazia">Não foi possível carregar.</p>}
                {estadoProposicoes === "pronto" && rail.itens.length === 0 && (
                  <p className="col-vazia">Nenhuma matéria pronta fora da pauta.</p>
                )}
                {rail.itens.map((p) => (
                  <ItemDisponivel
                    key={p.id}
                    proposicao={p}
                    enviando={edicao.enviando}
                    onIncluir={(fase) =>
                      aplicar(
                        edicao.incluir({ fase, tipoItem: "proposicao", proposicaoId: p.id }),
                        `${formatarNumeroProposicao(p.tipo, p.sequencial, p.ano)} incluída na pauta.`,
                      )
                    }
                  />
                ))}
                {rail.truncado && (
                  <p className="rail-truncado">
                    Mostrando as {proposicoesDados?.itens.length ?? 0} matérias mais recentes de{" "}
                    {proposicoesDados?.total ?? 0}. Para outra matéria, use “Incluir item na pauta”.
                  </p>
                )}
              </div>

              {convocacao && (
                <div className="conv">
                  <h3>Convocação</h3>
                  <div className="edital-cab">{convocacao.tituloEdital}</div>
                  <dl>
                    <dt>Data</dt>
                    <dd>
                      <span className="mono">{convocacao.data}</span> · {convocacao.diaSemana}
                    </dd>
                    <dt>Início</dt>
                    <dd>
                      <span className="mono">{convocacao.hora}</span>
                    </dd>
                  </dl>
                  <p className="antec">
                    <span className="tag">[Regimento]</span> Deve ser publicada com a antecedência mínima
                    prevista no Regimento Interno.
                  </p>
                  <p className="dest">
                    {convocacao.contagemExpediente} no expediente · {convocacao.contagemOrdemDoDia} na ordem
                    do dia.
                  </p>
                </div>
              )}
            </aside>
          </div>
        )}
      </main>
    </>
  );
}

// ---------------------------------------------------------------- uma linha da pauta

interface LinhaPautaProps {
  item: PautaItemOut;
  posicao: number;
  vizinhos: { acima: PautaItemOut | null; abaixo: PautaItemOut | null };
  proposicoesPorId: Map<string, ProposicaoResumoOut>;
  enviando: boolean;
  onMover: (vizinho: PautaItemOut, direcao: "acima" | "abaixo") => Promise<ResultadoPauta>;
  onRetirar: (tipo: TipoRetirada, justificativa: string) => Promise<ResultadoPauta>;
}

function LinhaPauta({ item, posicao, vizinhos, proposicoesPorId, enviando, onMover, onRetirar }: LinhaPautaProps) {
  const [retirando, setRetirando] = useState(false);
  const [tipo, setTipo] = useState<TipoRetirada>("exclusao");
  const [justificativa, setJustificativa] = useState("");
  const titulo = resolverTituloItem(item, proposicoesPorId);
  const nome = titulo.numero ?? titulo.rotulo;
  const tipoTexto = ROTULO_TIPO_TEXTO[item.tipoItem];

  async function confirmarRetirada() {
    const r = await onRetirar(tipo, justificativa);
    if (r.ok) setRetirando(false);
  }

  return (
    <div className="pauta-item">
      <span className="ordem-n" aria-hidden="true">
        {posicao}
      </span>
      <span className="arrasta" role="group" aria-label={`Reordenar: ${nome}`}>
        <button
          type="button"
          aria-label={`Mover ${nome} para cima`}
          disabled={enviando || !vizinhos.acima}
          onClick={() => vizinhos.acima && void onMover(vizinhos.acima, "acima")}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" aria-hidden="true">
            <path d="M6 15l6-6 6 6" />
          </svg>
        </button>
        <button
          type="button"
          aria-label={`Mover ${nome} para baixo`}
          disabled={enviando || !vizinhos.abaixo}
          onClick={() => vizinhos.abaixo && void onMover(vizinhos.abaixo, "abaixo")}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" aria-hidden="true">
            <path d="M6 9l6 6 6-6" />
          </svg>
        </button>
      </span>
      <div className="pi-mid">
        {titulo.numero && <span className="num">{titulo.numero}</span>}
        {tipoTexto && <span className="num">{tipoTexto}</span>}
        <h4>{titulo.rotulo}</h4>
      </div>
      <button
        className="pi-rem"
        type="button"
        aria-label={`Retirar ${nome} da pauta`}
        aria-expanded={retirando}
        disabled={enviando}
        onClick={() => setRetirando((v) => !v)}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>

      {retirando && (
        <div className="pi-retirar">
          <fieldset>
            <legend>Por que sai da pauta</legend>
            <label>
              <input type="radio" name={`ret-${item.id}`} checked={tipo === "exclusao"} onChange={() => setTipo("exclusao")} />
              Exclusão
            </label>
            <label>
              <input
                type="radio"
                name={`ret-${item.id}`}
                checked={tipo === "retirada_pedido_autor"}
                onChange={() => setTipo("retirada_pedido_autor")}
              />
              Retirada a pedido do autor
            </label>
          </fieldset>
          <div className="campo">
            <label htmlFor={`just-${item.id}`}>Justificativa (opcional)</label>
            <textarea id={`just-${item.id}`} rows={2} maxLength={2000} value={justificativa} onChange={(e) => setJustificativa(e.target.value)} />
          </div>
          <div className="pi-retirar-acoes">
            <button type="button" className="btn btn-encerrar btn-mini" disabled={enviando} onClick={() => void confirmarRetirada()}>
              {enviando ? "Retirando…" : "Retirar da pauta"}
            </button>
            <button type="button" className="btn btn-fantasma btn-mini" disabled={enviando} onClick={() => setRetirando(false)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- matéria pronta, no rail

function ItemDisponivel({
  proposicao: p,
  enviando,
  onIncluir,
}: {
  proposicao: ProposicaoResumoOut;
  enviando: boolean;
  onIncluir: (fase: FasePauta) => Promise<ResultadoPauta>;
}) {
  const [escolhendo, setEscolhendo] = useState(false);
  const [fase, setFase] = useState<FasePauta>("ordem_do_dia");
  const numero = formatarNumeroProposicao(p.tipo, p.sequencial, p.ano);
  return (
    <div className="disp-item">
      <div className="di-linha">
        <div className="di-mid">
          <span className="num">{numero}</span>
          <p>{p.ementa}</p>
        </div>
        {!escolhendo && (
          <button className="disp-add" type="button" aria-label={`Incluir ${numero} na pauta`} disabled={enviando} onClick={() => setEscolhendo(true)}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </button>
        )}
      </div>
      {escolhendo && (
        <div className="di-fase">
          <label htmlFor={`fase-${p.id}`}>Em qual fase</label>
          <select id={`fase-${p.id}`} value={fase} onChange={(e) => setFase(e.target.value as FasePauta)}>
            {FASES_DO_RITO.map((f) => (
              <option key={f.fase} value={f.fase}>
                {f.titulo}
              </option>
            ))}
          </select>
          <div className="di-acoes">
            <button
              type="button"
              className="btn btn-primaria btn-mini"
              disabled={enviando}
              onClick={async () => {
                const r = await onIncluir(fase);
                if (r.ok) setEscolhendo(false);
              }}
            >
              Incluir
            </button>
            <button type="button" className="btn btn-fantasma btn-mini" disabled={enviando} onClick={() => setEscolhendo(false)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
