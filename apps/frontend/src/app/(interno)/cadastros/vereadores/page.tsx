"use client";

// Cadastro de Vereadores (Task 9) — rota /cadastros/vereadores, arquétipo master-detail (porte de
// produto/design-system/o-plenario/telas/cadastro-vereadores.html). Une useAuth (App Shell) +
// useVereadores/useVereadorFicha (Task 8, fetch autenticado por hook, cada um degrada de forma
// independente) + avatar/estadoChip/filtrar/selecaoInicial (Task 7, view-model puro) + o padrão de
// keyboard nav roving-tabindex de ficha-materia-tabs.tsx (ArrowUp/ArrowDown movem E selecionam de imediato
// — modelo "auto-activate", igual ao das abas; Enter/Espaço ativam nativamente porque cada linha é um
// <button>, sem handler extra).
//
// Seleção via ?v=<id>: `useSearchParams` lê, `router.replace` (NUNCA `push` — sem spam de histórico a cada
// clique) escreve. `efetivoId` é a ÚNICA fonte de verdade da seleção (memo de `selecionadoManual ?? o que
// selecaoInicial() decidiria pela URL/1ª linha) — o efeito que sincroniza a URL e o hook da ficha consomem
// o MESMO valor, nunca duas contas separadas que possam divergir. Trocar o texto da busca NUNCA muda
// `efetivoId` (ele deriva da lista completa, não da lista filtrada) — a ficha aberta sobrevive a um filtro
// que a esconda da lista visível.
//
// Escrita (Novo vereador / Editar cadastro / Registrar licença) é OUTRA fatia (cadastro é somente-leitura
// aqui) — os 3 CTAs ficam `disabled`/`aria-disabled`, com um único <EmBreve> explicando por quê (mesma
// disciplina de AcoesCard, ficha-materia/acoes-card.tsx). Proposições/presença por vereador também não têm
// backend nesta fatia — nunca um número fabricado nos stat tiles: "—" + nota "Em breve"; só Comissões usa
// contagem REAL (ficha.comissoes.length).

import { useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useVereadores } from "@/lib/use-vereadores";
import { useVereadorFicha } from "@/lib/use-vereador-ficha";
import { avatar, estadoChip, filtrar, selecaoInicial } from "@/lib/cadastro-vereadores-vista";
import { formatarData } from "@/lib/formatar-data";
import { EmBreve } from "@/lib/em-breve";
import type { MandatoVigenteOut } from "@/lib/contrato-cadastros.gen";
import { TopoInterno } from "../../topo";
import "./cadastro-vereadores.css";

function nomeExibicao(p: { nome: string; nomeParlamentar?: string | null }): string {
  return p.nomeParlamentar ?? p.nome;
}

function faixaLegislatura(m: MandatoVigenteOut): string {
  const { legislaturaAnoInicio: ini, legislaturaAnoFim: fim, legislaturaNumero: num } = m;
  if (ini == null || fim == null) return "—";
  return num != null ? `${num}ª (${ini}–${fim})` : `${ini}–${fim}`;
}

function hrefComSelecao(id: string, token: string | null): string {
  const params = new URLSearchParams();
  params.set("v", id);
  if (token) params.set("token", token);
  return `/cadastros/vereadores?${params.toString()}`;
}

export default function PaginaVereadores() {
  const { token } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const vDaUrl = searchParams.get("v");

  const { dados: linhas, estado: estadoLista } = useVereadores(token);
  const [busca, setBusca] = useState("");
  const linhasFiltradas = filtrar(linhas, busca);

  // seleção: `selecionadoManual` só existe depois de um clique/teclado explícito do usuário; até lá, a
  // seleção efetiva vem de `selecaoInicial` (URL deep-link válida OU 1ª linha), calculada assim que a
  // lista estiver pronta.
  const [selecionadoManual, setSelecionadoManual] = useState<string | null>(null);
  const efetivoId = selecionadoManual ?? (estadoLista === "pronto" ? selecaoInicial(linhas, vDaUrl) : null);

  // sincroniza a URL com a seleção efetiva — side effect de navegação, não setState (a seleção em si é
  // sempre derivada, nunca guardada duas vezes). Só dispara quando a URL realmente diverge do efetivo,
  // evitando um replace() de no-op a cada render.
  useEffect(() => {
    if (estadoLista !== "pronto") return;
    if (efetivoId && efetivoId !== vDaUrl) {
      router.replace(hrefComSelecao(efetivoId, token));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [estadoLista, efetivoId, vDaUrl, token]);

  const { dados: ficha, estado: estadoFicha } = useVereadorFicha(token, efetivoId);

  const indiceSelecionado = linhasFiltradas.findIndex((l) => l.id === efetivoId);
  const indiceAtivo = indiceSelecionado === -1 ? 0 : indiceSelecionado;
  const botoesRef = useRef<(HTMLButtonElement | null)[]>([]);

  function selecionarIndice(i: number) {
    const linha = linhasFiltradas[i];
    if (!linha) return;
    setSelecionadoManual(linha.id);
    botoesRef.current[i]?.focus();
  }

  function aoTeclar(e: React.KeyboardEvent<HTMLButtonElement>, i: number) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      selecionarIndice(Math.min(i + 1, linhasFiltradas.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      selecionarIndice(Math.max(i - 1, 0));
    } else if (e.key === "Home") {
      e.preventDefault();
      selecionarIndice(0);
    } else if (e.key === "End") {
      e.preventDefault();
      selecionarIndice(linhasFiltradas.length - 1);
    }
  }

  if (estadoLista === "erro") {
    return (
      <>
        <TopoInterno area="Vereadores" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
        <main className="tela-estado">
          <h1>Não foi possível carregar os vereadores</h1>
          <p>Tente novamente em instantes.</p>
        </main>
      </>
    );
  }

  return (
    <>
      <TopoInterno area="Vereadores" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pg-cab">
          <div>
            <span className="eyebrow">Cadastros estruturais</span>
            <h1>Vereadores</h1>
            {estadoLista === "pronto" && (
              <p className="sub" aria-live="polite">
                {linhas.length} {linhas.length === 1 ? "vereador cadastrado" : "vereadores cadastrados"}.
              </p>
            )}
          </div>
          <button
            className="btn btn-primaria btn-mini"
            type="button"
            disabled
            aria-disabled="true"
            title="Cadastro de vereadores é somente leitura nesta fatia."
          >
            Novo vereador
          </button>
        </div>

        <div className="md">
          {/* MASTER: lista */}
          <div className="lista-card">
            <div className="busca">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <circle cx="11" cy="11" r="7" />
                <path d="m21 21-4.3-4.3" />
              </svg>
              <label className="sr-only" htmlFor="busca-vereador">Buscar vereador ou partido</label>
              <input
                id="busca-vereador"
                type="search"
                placeholder="Buscar vereador ou partido…"
                value={busca}
                onChange={(e) => setBusca(e.target.value)}
              />
            </div>

            {estadoLista === "carregando" && <p role="status" className="secao-vazia">Carregando vereadores…</p>}

            {estadoLista === "pronto" && linhas.length === 0 && (
              <p className="secao-vazia">Nenhum vereador cadastrado ainda.</p>
            )}

            {estadoLista === "pronto" && linhas.length > 0 && linhasFiltradas.length === 0 && (
              <p className="secao-vazia">Nenhum vereador corresponde à busca.</p>
            )}

            {estadoLista === "pronto" && linhasFiltradas.length > 0 && (
              <div className="v-rows" role="listbox" aria-label="Vereadores">
                {linhasFiltradas.map((linha, i) => {
                  const av = avatar(nomeExibicao(linha), linha.id);
                  const chip = estadoChip(linha.estadoMandato);
                  const selecionada = linha.id === efetivoId;
                  return (
                    <button
                      key={linha.id}
                      ref={(el) => {
                        botoesRef.current[i] = el;
                      }}
                      role="option"
                      className="v-row"
                      aria-selected={selecionada}
                      aria-current={selecionada ? "true" : undefined}
                      tabIndex={i === indiceAtivo ? 0 : -1}
                      onClick={() => selecionarIndice(i)}
                      onKeyDown={(e) => aoTeclar(e, i)}
                      type="button"
                    >
                      <span className="v-av" style={{ background: av.cor }} aria-hidden="true">
                        {av.iniciais}
                      </span>
                      <span className="v-mid">
                        <b>{nomeExibicao(linha)}</b>
                        <span>
                          {linha.partido ?? "Sem partido"} · {linha.cargoMesa ?? "Vereador(a)"}
                        </span>
                      </span>
                      {chip.tom === "licenca" && <span className="v-lic">Licença</span>}
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          {/* DETAIL: ficha */}
          {efetivoId === null ? (
            estadoLista === "pronto" ? null : (
              <div className="ficha">
                <p role="status" className="secao-vazia">Carregando…</p>
              </div>
            )
          ) : estadoFicha === "erro" ? (
            <div className="ficha">
              <p role="alert" className="secao-erro">Não foi possível carregar a ficha deste vereador.</p>
            </div>
          ) : estadoFicha === "carregando" || !ficha ? (
            <div className="ficha">
              <p role="status" className="secao-vazia">Carregando ficha…</p>
            </div>
          ) : (
            <div className="ficha" aria-label={`Ficha de ${nomeExibicao(ficha)}`}>
              <div className="ficha-topo">
                <span
                  className="big-av"
                  style={{ background: avatar(nomeExibicao(ficha), ficha.id).cor }}
                  aria-hidden="true"
                >
                  {avatar(nomeExibicao(ficha), ficha.id).iniciais}
                </span>
                <div className="qa">
                  <h2>{nomeExibicao(ficha)}</h2>
                  <p className="cargo">
                    {ficha.mandato ? (
                      <>
                        {ficha.mandato.partido ?? "Sem partido"} · {ficha.mandato.cargoMesa ?? "Vereador(a)"}
                      </>
                    ) : (
                      "Sem mandato vigente"
                    )}
                  </p>
                </div>
                {(() => {
                  const chip = estadoChip(ficha.mandato?.estado);
                  return <span className={`chip chip-${chip.tom === "ativo" ? "ok" : chip.tom === "licenca" ? "alerta" : "neutro"}`}>{chip.rotulo}</span>;
                })()}
              </div>

              <div className="ficha-grid">
                <div className="stat stat-em-breve">
                  <b aria-hidden="true">—</b>
                  <span>proposições</span>
                  <span className="stat-nota">Em breve</span>
                </div>
                <div className="stat">
                  <b>{ficha.comissoes.length}</b>
                  <span>comissões</span>
                </div>
                <div className="stat stat-em-breve">
                  <b aria-hidden="true">—</b>
                  <span>presença</span>
                  <span className="stat-nota">Em breve</span>
                </div>
              </div>

              <div className="ficha-corpo">
                {ficha.mandato && (
                  <div className="bloco">
                    <h3>Mandato</h3>
                    <dl className="dl">
                      <dt>Legislatura</dt>
                      <dd className="mono">{faixaLegislatura(ficha.mandato)}</dd>
                      <dt>Posse</dt>
                      <dd className="mono">{formatarData(ficha.mandato.posse)}</dd>
                      <dt>Filiação</dt>
                      <dd>{ficha.mandato.partido ?? "Sem partido"}</dd>
                      <dt>Cargo na Mesa</dt>
                      <dd>{ficha.mandato.cargoMesa ?? "Sem cargo na Mesa"}</dd>
                    </dl>
                  </div>
                )}

                <div className="bloco">
                  <h3>Comissões</h3>
                  {ficha.comissoes.length === 0 ? (
                    <p className="tag-vazio">Sem comissões atribuídas.</p>
                  ) : (
                    <div className="tags">
                      {ficha.comissoes.map((c, i) => (
                        <span key={`${c.nome}-${i}`} className={`tag${c.cargo === "presidente" ? " pres" : ""}`}>
                          {c.nome}
                          {c.cargo ? ` · ${c.cargo}` : ""}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                <div className="bloco">
                  <h3>Contato institucional</h3>
                  <EmBreve
                    titulo="Contato institucional"
                    motivo="Gabinete, e-mail institucional e telefone ainda não têm cadastro estruturado nesta fatia — chegam quando o módulo de contato institucional for construído."
                  />
                </div>

                <div className="ficha-acoes">
                  <button
                    className="btn btn-contorno btn-mini"
                    type="button"
                    disabled
                    aria-disabled="true"
                    aria-describedby="vereador-acoes-em-breve"
                  >
                    Ver proposições
                  </button>
                  <button
                    className="btn btn-contorno btn-mini"
                    type="button"
                    disabled
                    aria-disabled="true"
                    aria-describedby="vereador-acoes-em-breve"
                  >
                    Editar cadastro
                  </button>
                  <button
                    className="btn btn-fantasma btn-mini"
                    type="button"
                    disabled
                    aria-disabled="true"
                    aria-describedby="vereador-acoes-em-breve"
                  >
                    Registrar licença
                  </button>
                </div>
                <div id="vereador-acoes-em-breve">
                  <EmBreve
                    titulo="Ações do cadastro"
                    motivo="Editar cadastro, registrar licença e ver as proposições do vereador ainda não têm o backend fiado nesta fatia — o cadastro é somente leitura por enquanto."
                  />
                </div>
              </div>
            </div>
          )}
        </div>
      </main>
    </>
  );
}
