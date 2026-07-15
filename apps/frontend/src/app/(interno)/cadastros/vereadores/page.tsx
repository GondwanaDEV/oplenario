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
// Escrita (Novo vereador / Editar cadastro / Registrar mandato / Registrar licença — Task 9) já tem
// backend fiado nesta fatia: os 4 forms (novo-vereador-form.tsx e companhia) abrem como painel INLINE (não
// modal — evita o carry de focus-trap da C4) abaixo das ações que os disparam. "Ver proposições" segue
// deferido (backend de proposições por vereador não existe ainda) com seu próprio <EmBreve>. Proposições/
// presença por vereador também não têm backend nesta fatia — nunca um número fabricado nos stat tiles: "—"
// e nota "Em breve"; só Comissões usa contagem REAL (ficha.comissoes.length).

import { useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth, usePapeis } from "@/lib/auth";
import { useVereadores } from "@/lib/use-vereadores";
import { useVereadorFicha } from "@/lib/use-vereador-ficha";
import { useLegislaturaVigente } from "@/lib/use-legislatura-vigente";
import { avatar, estadoChip, filtrar, selecaoInicial } from "@/lib/cadastro-vereadores-vista";
import { formatarData } from "@/lib/formatar-data";
import { EmBreve } from "@/lib/em-breve";
import { NovoVereadorForm } from "./novo-vereador-form";
import { EditarVereadorForm } from "./editar-vereador-form";
import { RegistrarMandatoForm } from "./registrar-mandato-form";
import { RegistrarLicencaForm } from "./registrar-licenca-form";
import { ConcederAcessoForm } from "./conceder-acesso-form";
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
  // `admin_ente` (Task 11) — guarda só o botão/painel "Conceder acesso" (ligar identidade + abrir a
  // porta), não a página inteira: o resto do cadastro (criar/editar/mandato/licença) segue aberto a
  // `secretario`, o papel que já governa as outras rotas desta página no backend. Enquanto `estado` não é
  // "pronto" (modo real aguardando GET /eu), o botão fica ESCONDIDO, não desabilitado — evita mostrar e
  // depois sumir a ação de um admin_ente real (flash), mesmo racional de pauta-convocacao/page.tsx.
  const { papeis, estado: estadoPapeis } = usePapeis();
  const podeConcederAcesso = estadoPapeis === "pronto" && papeis.includes("admin_ente");
  const router = useRouter();
  const searchParams = useSearchParams();
  const vDaUrl = searchParams.get("v");

  // `versao` (Task 9) é um token de refetch puro: a página o incrementa depois de uma escrita bem-sucedida
  // (criar/editar vereador, registrar mandato/licença) pra forçar useVereadores/useVereadorFicha a se
  // refazerem — nenhum dos dois hooks tem um jeito próprio de "refetch", então isto entra nas deps deles.
  const [versao, setVersao] = useState(0);
  const [painel, setPainel] = useState<null | "novo" | "editar" | "mandato" | "licenca" | "acesso">(null);

  const { dados: linhas, estado: estadoLista } = useVereadores(token, versao);
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
    // eslint-disable-next-line react-hooks/exhaustive-deps -- router é estável (useRouter, Next App Router); demais deps são o gatilho real
  }, [estadoLista, efetivoId, vDaUrl, token]);

  const { dados: ficha, estado: estadoFicha } = useVereadorFicha(token, efetivoId, versao);
  const { dados: legislaturaVigente } = useLegislaturaVigente(token);

  // troca de vereador selecionado fecha qualquer painel de edição/mandato/licença aberto (evita deixar um
  // form stale apontando pro vereador anterior) — o painel "novo" fica de fora: ele não depende de
  // `efetivoId` nenhum (ainda não existe vereador ao criar) e sobrevive à seleção automática do 1º da lista
  // que acontece enquanto o painel de criação está aberto. Reset DURANTE O RENDER (não dentro de um
  // useEffect) — mesmo idioma de use-vereador-ficha.ts, exigido por eslint-plugin-react-hooks v7
  // `set-state-in-effect`.
  const [efetivoIdAnterior, setEfetivoIdAnterior] = useState(efetivoId);
  if (efetivoId !== efetivoIdAnterior) {
    setEfetivoIdAnterior(efetivoId);
    setPainel((p) => (p === "novo" ? p : null));
  }

  function aoConcluir(id: string) {
    setPainel(null);
    setSelecionadoManual(id); // seleciona o vereador afetado
    setVersao((v) => v + 1); // dispara refetch de lista + ficha
  }

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
            onClick={() => setPainel("novo")}
          >
            Novo vereador
          </button>
        </div>

        {painel === "novo" && (
          <div className="painel-cad">
            <NovoVereadorForm token={token} onSucesso={aoConcluir} onCancelar={() => setPainel(null)} />
          </div>
        )}

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
                {(() => {
                  const av = avatar(nomeExibicao(ficha), ficha.id);
                  return (
                    <span className="big-av" style={{ background: av.cor }} aria-hidden="true">
                      {av.iniciais}
                    </span>
                  );
                })()}
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
                    aria-describedby="vereador-proposicoes-em-breve"
                  >
                    Ver proposições
                  </button>
                  <button
                    className="btn btn-contorno btn-mini"
                    type="button"
                    onClick={() => setPainel("editar")}
                  >
                    Editar cadastro
                  </button>
                  <button
                    className="btn btn-contorno btn-mini"
                    type="button"
                    onClick={() => setPainel("mandato")}
                  >
                    Registrar mandato
                  </button>
                  <button
                    className="btn btn-fantasma btn-mini"
                    type="button"
                    disabled={ficha.mandato?.estado !== "vigente"}
                    title={ficha.mandato?.estado !== "vigente" ? "Requer um mandato vigente." : undefined}
                    onClick={() => setPainel("licenca")}
                  >
                    Registrar licença
                  </button>
                  {podeConcederAcesso && (
                    <button
                      className="btn btn-contorno btn-mini"
                      type="button"
                      onClick={() => setPainel("acesso")}
                    >
                      Conceder acesso
                    </button>
                  )}
                </div>
                <div id="vereador-proposicoes-em-breve">
                  <EmBreve
                    titulo="Ver proposições"
                    motivo="Proposições por vereador ainda não têm o backend fiado nesta fatia."
                  />
                </div>

                {painel === "editar" && (
                  <div className="painel-cad">
                    <EditarVereadorForm
                      token={token}
                      vereadorId={ficha.id}
                      inicial={{ nome: ficha.nome, nomeParlamentar: ficha.nomeParlamentar }}
                      onSucesso={aoConcluir}
                      onCancelar={() => setPainel(null)}
                    />
                  </div>
                )}
                {painel === "mandato" && (
                  <div className="painel-cad">
                    <RegistrarMandatoForm
                      token={token}
                      vereadorId={ficha.id}
                      legislatura={legislaturaVigente}
                      onSucesso={aoConcluir}
                      onCancelar={() => setPainel(null)}
                    />
                  </div>
                )}
                {painel === "licenca" && (
                  <div className="painel-cad">
                    <RegistrarLicencaForm
                      token={token}
                      vereadorId={ficha.id}
                      onSucesso={aoConcluir}
                      onCancelar={() => setPainel(null)}
                    />
                  </div>
                )}
                {painel === "acesso" && podeConcederAcesso && (
                  <div className="painel-cad">
                    <ConcederAcessoForm
                      token={token}
                      vereadorId={ficha.id}
                      nome={nomeExibicao(ficha)}
                      onSucesso={aoConcluir}
                      onCancelar={() => setPainel(null)}
                    />
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </main>
    </>
  );
}
