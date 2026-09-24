"use client";

// Comando da Mesa — o painel que CONDUZ o ciclo de vida da sessão (§22.6 eixo C). GAP fechado: a máquina de
// estados da sessão (agendar → abrir → suspender/reabrir → encerrar → arquivar) só existia via API
// (POST /sessoes/:id/transicao); nenhuma tela a dirigia. O telão (`/plenario`) LÊ ao vivo; este console
// ESCREVE. Mesma família de rota-por-token de `/chamada` e `/folha` (operado ao vivo pela secretaria; a
// authz real é do servidor — papel `secretario`, 403/409 fail-closed — este `GuardSecretaria`-livre segue o
// idioma de chamada/folha).
//
// Só orquestração + apresentação: QUAIS transições são possíveis vem de `conducao-sessao-vista.ts` (puro);
// o IO e o CAS de lock-version vêm de `use-conducao-sessao.ts`. Nada de grafo nem de fetch aqui.

import { useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { AuthProvider, useAuth } from "@/lib/auth";
import { useTema } from "@/lib/tema";
import { useConducaoSessao } from "@/lib/use-conducao-sessao";
import { usePauta } from "@/lib/use-pauta";
import { derivarConducaoSessao, type AtoConducao, type SituacaoSessao } from "@/lib/conducao-sessao-vista";
import { nomeTipoSessao, nomeFase } from "@/lib/rotulos-sessao";
import type { SessaoOut } from "@/lib/contrato-sessoes.gen";
import { PainelVotacao } from "./painel-votacao";
import { PainelTribuna } from "./painel-tribuna";
import { BotaoModoTv } from "../botao-modo-tv";
import "./conduzir.css";

/** "2026-05-21T14:03:00Z" -> "21/05 às 14h03" (fuso do navegador — leitura humana, nunca comparação). */
function quando(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const dd = String(d.getDate()).padStart(2, "0");
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const hh = String(d.getHours()).padStart(2, "0");
  const mi = String(d.getMinutes()).padStart(2, "0");
  return `${dd}/${mm} às ${hh}h${mi}`;
}

const CHIP_SITUACAO: Record<SituacaoSessao, string> = {
  agendada: "chip-neutro",
  viva: "chip-ok",
  suspensa: "chip-alerta",
  terminal: "chip-risco",
};

export default function PaginaConduzir() {
  const params = useParams<{ id: string }>();
  const search = useSearchParams();
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <ConteudoConduzir id={params.id} />
    </AuthProvider>
  );
}

function ConteudoConduzir({ id }: { id: string }) {
  const { token } = useAuth();
  const { sessao, estado, erro, transicionar, recarregar } = useConducaoSessao(id, token);

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível abrir o comando da Mesa</h1>
        <p>{erro ?? "Erro desconhecido."}</p>
      </main>
    );
  }
  if (!sessao) {
    return (
      <main className="tela-estado">
        <h1>Carregando a sessão…</h1>
        <p>Buscando o estado atual para conduzir.</p>
      </main>
    );
  }
  return <Comando sessao={sessao} token={token} transicionar={transicionar} recarregar={recarregar} />;
}

interface ComandoProps {
  sessao: SessaoOut;
  token: string | null;
  transicionar: ReturnType<typeof useConducaoSessao>["transicionar"];
  recarregar: ReturnType<typeof useConducaoSessao>["recarregar"];
}

function Comando({ sessao, token, transicionar, recarregar }: ComandoProps) {
  const { tema, alternar } = useTema();
  const { pauta, estado: estadoPauta } = usePauta(sessao.id, token, sessao.estado);
  const vista = derivarConducaoSessao(sessao);

  const [aberto, setAberto] = useState<AtoConducao["para"] | null>(null);
  const [motivo, setMotivo] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erroAcao, setErroAcao] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  function abrir(ato: AtoConducao) {
    setErroAcao(null);
    setAviso(null);
    setMotivo("");
    // Atos diretos (sem motivo e sem confirmação) disparam na hora; os demais expandem o painel do ato.
    if (!ato.exigeMotivo && !ato.confirmacao) {
      void disparar(ato);
      return;
    }
    setAberto(ato.para);
  }

  function fechar() {
    setAberto(null);
    setMotivo("");
    setErroAcao(null);
  }

  async function disparar(ato: AtoConducao) {
    if (ato.exigeMotivo && !motivo.trim()) {
      setErroAcao("Descreva o motivo — o campo não pode ficar em branco.");
      return;
    }
    setEnviando(true);
    setErroAcao(null);
    const r = await transicionar(ato.para, ato.exigeMotivo ? motivo : undefined);
    setEnviando(false);
    if (r.ok) {
      setAberto(null);
      setMotivo("");
      setAviso(`Pronto — sessão ${rotuloDoAlvo(ato.para)}.`);
    } else {
      setErroAcao(r.erro);
    }
  }

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
            <span className="tipo">Comando da Mesa</span>
            <span className="quando">
              Sessão {nomeTipoSessao(sessao.tipoSessao)} nº {sessao.numeroSequencial}
            </span>
          </div>
          <div className="topo-dir">
            <BotaoModoTv sessaoId={sessao.id} token={token} />
            <button
              className="tema-btn"
              type="button"
              aria-pressed={tema === "escuro"}
              onClick={alternar}
              title="Alternar tema claro / escuro"
            >
              {tema === "escuro" ? "☾" : "☀"}
              <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
            </button>
          </div>
        </div>
      </header>

      <main className="envelope conduzir">
        <section className="bloco estado-atual" aria-labelledby="estado-titulo">
          <div className="bloco-cabeca">
            <div>
              <p className="eyebrow">Estado da sessão</p>
              <h1 id="estado-titulo">
                Sessão {nomeTipoSessao(sessao.tipoSessao)} nº {sessao.numeroSequencial}
              </h1>
            </div>
            <span className={`chip selo-estado ${CHIP_SITUACAO[vista.situacao]}`} aria-live="polite">
              {vista.rotuloEstado}
            </span>
          </div>
          <ul className="marcos">
            {quando(sessao.agendadaPara) && (
              <li>
                <span className="rot">Agendada para</span>
                <b>{quando(sessao.agendadaPara)}</b>
              </li>
            )}
            {quando(sessao.abertaEm) && (
              <li>
                <span className="rot">Aberta em</span>
                <b>{quando(sessao.abertaEm)}</b>
              </li>
            )}
            {quando(sessao.encerradaEm) && (
              <li>
                <span className="rot">Encerrada em</span>
                <b>{quando(sessao.encerradaEm)}</b>
              </li>
            )}
            {sessao.motivoNaoRealizada && (
              <li>
                <span className="rot">Motivo</span>
                <b>{sessao.motivoNaoRealizada}</b>
              </li>
            )}
          </ul>
        </section>

        <section className="bloco atos" aria-labelledby="atos-titulo">
          <div className="bloco-cabeca">
            <h2 id="atos-titulo">O que a Mesa pode fazer agora</h2>
          </div>
          <div className="bloco-corpo">
            {aviso && (
              <p role="status" className="aviso-ok">
                {aviso}
              </p>
            )}
            {vista.terminal || vista.atos.length === 0 ? (
              <p className="nota-terminal">
                <span>
                  Esta sessão está <b>{vista.rotuloEstado.toLowerCase()}</b> — não há mais atos de condução a
                  partir daqui. O histórico segue disponível para leitura.
                </span>
              </p>
            ) : (
              <ul className="lista-atos">
                {vista.atos.map((ato) => (
                  <li key={ato.para} className={aberto === ato.para ? "ato aberto" : "ato"}>
                    <div className="ato-linha">
                      <div className="ato-texto">
                        <b>{ato.rotulo}</b>
                        <span>{ato.descricao}</span>
                      </div>
                      <button
                        type="button"
                        className={`btn ${tomBtn(ato.tom)}`}
                        disabled={enviando}
                        aria-expanded={ato.exigeMotivo || ato.confirmacao ? aberto === ato.para : undefined}
                        onClick={() => (aberto === ato.para ? fechar() : abrir(ato))}
                      >
                        {ato.rotulo}
                      </button>
                    </div>

                    {aberto === ato.para && (
                      <div className="ato-detalhe">
                        {ato.exigeMotivo && (
                          <div className="campo">
                            <label htmlFor={`motivo-${ato.para}`}>Motivo</label>
                            <textarea
                              id={`motivo-${ato.para}`}
                              value={motivo}
                              onChange={(e) => setMotivo(e.target.value)}
                              rows={2}
                              maxLength={2048}
                              aria-invalid={erroAcao ? true : undefined}
                            />
                          </div>
                        )}
                        {ato.confirmacao && !ato.exigeMotivo && <p className="confirmar-txt">{ato.confirmacao}</p>}
                        {erroAcao && (
                          <p role="alert" className="erro-inline">
                            {erroAcao}
                          </p>
                        )}
                        <div className="ato-acoes">
                          <button
                            type="button"
                            className={`btn ${tomBtn(ato.tom)}`}
                            disabled={enviando}
                            onClick={() => disparar(ato)}
                          >
                            {enviando ? "Enviando…" : `Confirmar: ${ato.rotulo}`}
                          </button>
                          <button
                            type="button"
                            className="btn btn-fantasma"
                            disabled={enviando}
                            onClick={fechar}
                          >
                            Cancelar
                          </button>
                        </div>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
            <div className="rodape-atos">
              <button type="button" className="btn btn-fantasma btn-mini" onClick={() => void recarregar()}>
                Atualizar estado
              </button>
              <p className="nota-mesa">
                <span>
                  Cada transição é registrada com o seu nome e a hora. Se outra pessoa mudar o estado enquanto
                  esta tela está aberta, o disparo é recusado e a tela recarrega o estado real.
                </span>
              </p>
            </div>
          </div>
        </section>

        {sessao.estado === "aberta" && (
          <PainelVotacao sessaoId={sessao.id} token={token} sessaoEstado={sessao.estado} />
        )}

        {(sessao.estado === "aberta" || sessao.estado === "suspensa") && (
          <PainelTribuna sessaoId={sessao.id} token={token} />
        )}

        {estadoPauta === "ok" && pauta && pauta.itens.length > 0 && (
          <section className="bloco pauta-resumo" aria-labelledby="pauta-titulo">
            <div className="bloco-cabeca">
              <h2 id="pauta-titulo">Pauta desta sessão</h2>
              <span className="eyebrow">{pauta.itens.length} item(ns)</span>
            </div>
            <div className="bloco-corpo">
              <ResumoFases itens={pauta.itens} />
              <p className="nota-mesa">
                <span>
                  A ordem dos trabalhos segue a pauta. A abertura e o encerramento das votações item a item têm
                  a sua própria tela.
                </span>
              </p>
            </div>
          </section>
        )}
      </main>
    </>
  );
}

/** Agrupa a pauta por FASE do rito, contando itens. Usa só `item.fase` — chave de uma palavra, imune à
 * camelização — para não depender do resto do PautaItemOut (cujos campos multi-palavra o `usePauta` não
 * cameliza). Contexto, não operação: se a pauta falhar, a seção some sem derrubar o comando. */
function ResumoFases({ itens }: { itens: { fase: string }[] }) {
  const porFase = new Map<string, number>();
  for (const it of itens) porFase.set(it.fase, (porFase.get(it.fase) ?? 0) + 1);
  return (
    <ul className="fases">
      {[...porFase.entries()].map(([fase, n]) => (
        <li key={fase}>
          <span className="fase-nome">{nomeFase(fase)}</span>
          <span className="fase-qtd">{n}</span>
        </li>
      ))}
    </ul>
  );
}

function tomBtn(tom: AtoConducao["tom"]): string {
  if (tom === "primaria") return "btn-primaria";
  if (tom === "perigo") return "btn-encerrar"; // classe canônica do chassi p/ ação-telha (encerrar/negar)
  return "btn-contorno";
}

function rotuloDoAlvo(para: AtoConducao["para"]): string {
  const m: Record<AtoConducao["para"], string> = {
    aberta: "aberta",
    agendada: "agendada",
    suspensa: "suspensa",
    encerrada: "encerrada",
    nao_realizada: "marcada como não realizada",
    arquivada: "arquivada",
  };
  return m[para];
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
