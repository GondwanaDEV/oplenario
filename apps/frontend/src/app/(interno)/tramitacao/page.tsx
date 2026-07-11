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
import { derivarBoard, filtrarColunasPorBusca, type ColunaBoard } from "@/lib/tramitacao-board-vista";
import { formatarEspecieProposicao } from "@/lib/proposicoes-vista";
import { TopoInterno } from "../topo";
import "./tramitacao.css";

const TIPOS_CONHECIDOS = [
  "projeto_lei",
  "projeto_lei_complementar",
  "projeto_resolucao",
  "projeto_decreto_legislativo",
  "proposta_emenda_lom",
  "indicacao",
  "requerimento",
  "mocao",
];

function filtrarPorEspecie(colunas: ColunaBoard[], tipo: string): ColunaBoard[] {
  if (!tipo) return colunas;
  const especie = formatarEspecieProposicao(tipo);
  return colunas.map((c) => ({ ...c, itens: c.itens.filter((i) => i.especie === especie) }));
}

export default function PaginaTramitacao() {
  const { token } = useAuth();
  const { itens, estado } = useTramitacaoBoard(token);
  const [busca, setBusca] = useState("");
  const [tipoFiltro, setTipoFiltro] = useState("");

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar o quadro de tramitação</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }

  const colunasBrutas = itens ? derivarBoard(itens) : [];
  const colunas = filtrarPorEspecie(filtrarColunasPorBusca(colunasBrutas, busca), tipoFiltro);
  const totalItens = itens?.length ?? 0;

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
              {TIPOS_CONHECIDOS.map((t) => (
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
            {colunas.map((coluna) => (
              <section key={coluna.chave} className="coluna" aria-label={`${coluna.titulo} · ${coluna.itens.length} matérias`}>
                <div className="col-cabe">
                  <span className={`azulejo-col az-${coluna.azulejo}`} aria-hidden="true" />
                  <span className="tit">{coluna.titulo}</span>
                  <span className="cnt">{coluna.itens.length}</span>
                </div>
                <div className="col-corpo">
                  {coluna.itens.length === 0 && <p className="col-vazia">Nenhuma matéria.</p>}
                  {coluna.itens.map((item) => (
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
                </div>
              </section>
            ))}
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
