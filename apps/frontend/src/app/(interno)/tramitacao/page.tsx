"use client";

// Tramitação (Onda B Slice 4, §16 arquétipo cockpit) — assembly da rota /tramitacao. O quadro de estágios
// da tramitação: a visão do servidor de onde cada matéria está agora (tramitacao-board.html). Une
// useAuth (App Shell) + useTramitacaoBoard (fetch autenticado da MESMA rota já usada por useMesa como
// detalhe, aqui como chamada PRINCIPAL) + derivarBoard/filtrarColunasPorBusca (view-model puro).
//
// INTEGRIDADE DE DESIGN (herdada da tela-fonte): tramitação NÃO é kanban de arrastar — uma matéria avança
// por ATOS (despacho, parecer, votação), não por drag-and-drop. O quadro é um READ-MODEL de status;
// cartões clicáveis levam à ficha (onde o ato real é registrado). Por isso não há nenhum affordance de
// arrastar aqui.
//
// ESCOPO DIFERIDO (sem dado real no backend, ver comentário do prompt): filtro por legislatura/comissão,
// relator (o payload só tem autorTexto = o proponente, não o relator da comissão), selos de
// prazo/urgência/regime, "Exportar quadro", aba "Lista" (renderiza só o quadro).

import { useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { useTramitacaoBoard } from "@/lib/use-tramitacao-board";
import {
  derivarBoard,
  filtrarColunasPorBusca,
  filtrarColunasPorEspecie,
  paginarColuna,
} from "@/lib/tramitacao-board-vista";
import { formatarEspecieProposicao, TIPOS_PROPOSICAO } from "@/lib/proposicoes-vista";
import { TopoInterno } from "../topo";
import "./tramitacao.css";

export default function PaginaTramitacao() {
  const { token } = useAuth();
  const { itens, totaisPorEstado, estado } = useTramitacaoBoard(token);
  const [busca, setBusca] = useState("");
  const [tipoFiltro, setTipoFiltro] = useState("");
  const [colunasExpandidas, setColunasExpandidas] = useState<Record<string, boolean>>({});

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar o quadro de tramitação</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }

  // Fatia "truncamento-familia": `derivarBoard` só recebe `totaisPorEstado` quando o servidor já
  // respondeu (nunca antes — passar `undefined` durante o carregamento cairia no fallback de compat
  // itens.length, que é exatamente o número que corta no teto por-estado, e a Mesa precisa do real).
  const colunasBrutas = itens ? derivarBoard(itens, totaisPorEstado ?? undefined) : [];
  const colunasFiltradas = filtrarColunasPorEspecie(
    filtrarColunasPorBusca(colunasBrutas, busca),
    tipoFiltro,
    formatarEspecieProposicao,
  );
  // Total REAL da Casa (soma de totaisPorEstado, não itens.length): itens é a lista JÁ CORTADA no teto
  // por-estado (50) do servidor — somar seu tamanho sub-contaria justo quando algum estado estourou o
  // teto, o defeito que esta fatia fecha (era "matérias em curso" mentindo por baixo).
  const totalItens = colunasBrutas.reduce((soma, c) => soma + c.total, 0);

  return (
    <>
      <TopoInterno area="Tramitação" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pg-cab">
          <div>
            <span className="eyebrow">Visão da Casa</span>
            <h1>Tramitação</h1>
            <p className="leitura">
              Onde cada matéria está agora. <b>Mover uma matéria registra o ato correspondente</b> (despacho,
              parecer, votação) — não se arrasta etapa.
            </p>
          </div>
          <Link href={comToken("/editor-proposicao", token)} className="btn btn-primaria">
            Nova proposição
          </Link>
        </div>

        <div className="filtros">
          <span className="faceta">
            <label htmlFor="f-tipo">Espécie</label>
            <select id="f-tipo" value={tipoFiltro} onChange={(e) => setTipoFiltro(e.target.value)}>
              <option value="">Todas</option>
              {TIPOS_PROPOSICAO.map((t) => (
                <option key={t} value={t}>
                  {formatarEspecieProposicao(t)}
                </option>
              ))}
            </select>
          </span>
          <div className="busca">
            <label className="sr-only" htmlFor="busca">Buscar por ementa ou número</label>
            <input
              id="busca"
              type="search"
              placeholder="Buscar por ementa ou número…"
              value={busca}
              onChange={(e) => setBusca(e.target.value)}
            />
          </div>
        </div>

        {estado === "carregando" && <p role="status">Carregando…</p>}

        {estado === "pronto" && (
          <div className="board" role="region" aria-label="Quadro de tramitação por estágio (rolagem horizontal)" tabIndex={0}>
            {colunasFiltradas.map((coluna) => {
              const expandida = colunasExpandidas[coluna.chave] ?? false;
              const visivel = paginarColuna(coluna, expandida);
              const restantes = coluna.itens.length - visivel.itens.length;
              // Sem filtro ativo: mostra o total REAL da coluna (`coluna.total`, autoritativo — sobrevive
              // ao corte por-estado do servidor). Com filtro ativo, o servidor não sabe "quantas casariam
              // o filtro" — o que a tela pode mostrar honestamente é quantas das que chegaram bateram
              // (mesmo comportamento de antes desta fatia, sem regressão).
              const semFiltro = busca.trim() === "" && tipoFiltro === "";
              const contagemColuna = semFiltro ? coluna.total : coluna.itens.length;
              return (
                <section key={coluna.chave} className="coluna" aria-label={`${coluna.titulo} · ${contagemColuna} matérias`}>
                  <div className="col-cabe">
                    <span className={`azulejo-col az-${coluna.azulejo}`} aria-hidden="true" />
                    <h2 className="tit">{coluna.titulo}</h2>
                    <span className="cnt">{contagemColuna}</span>
                  </div>
                  <div className="col-corpo">
                    {coluna.itens.length === 0 && <p className="col-vazia">Nenhuma matéria.</p>}
                    {visivel.itens.map((item) => (
                      <article className="mat" key={item.proposicaoId}>
                        <div className="mat-topo">
                          <span className="mat-num">{item.numero}</span>
                        </div>
                        <h3>
                          <Link href={comToken(`/ficha-materia/${encodeURIComponent(item.proposicaoId)}`, token)}>
                            {item.ementa}
                          </Link>
                        </h3>
                        <div className="mat-pe">
                          <span className="selo selo-esp">{item.especie}</span>
                          <span className="mat-autor">{item.autor}</span>
                        </div>
                      </article>
                    ))}
                    {restantes > 0 && (
                      <button
                        type="button"
                        className="col-mais"
                        onClick={() => setColunasExpandidas((s) => ({ ...s, [coluna.chave]: true }))}
                      >
                        Mostrar mais {restantes} matérias
                      </button>
                    )}
                  </div>
                </section>
              );
            })}
          </div>
        )}

        {estado === "pronto" && (
          <p className="rodape-contagem" aria-live="polite">
            <b>{totalItens}</b> matérias em curso
          </p>
        )}
      </main>
    </>
  );
}
