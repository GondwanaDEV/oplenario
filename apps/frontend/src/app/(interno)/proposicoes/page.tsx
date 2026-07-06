"use client";

// Lista de proposições (Onda B Slice 1, §16 arquétipo lista/tabela filtrável) — assembly da rota
// /proposicoes. Une useAuth (App Shell) + useProposicoes (fetch autenticado + refetch por filtro) +
// derivarProposicoesVista (view-model puro) + AzulejoMini/descreverFaixa (assinatura de tramitação, já
// construídos na A2). "Exportar" e a barra de ações em massa da tela-fonte seguem FORA (sem dono ainda) —
// só "Nova proposição" (header) e "Editar" por linha (Slice 2, editor-proposicao) foram habilitados. A
// ação "abrir ficha" por linha também não existe ainda (ficha-materia é uma fatia posterior) — cada linha
// não é clicável fora da coluna Ações.

import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { useProposicoes, type FiltrosProposicoes } from "@/lib/use-proposicoes";
import { derivarProposicoesVista } from "@/lib/proposicoes-vista";
import { AzulejoMini } from "@/lib/charts/azulejo-mini";
import { descreverFaixa } from "@/lib/tramitacao-vista";
import { TopoInterno } from "../topo";
import "./proposicoes.css";

const FILTROS_INICIAIS: FiltrosProposicoes = {
  pagina: 1,
  tamanho: 20,
  ordenarPor: "atualizado_em",
  ordenarDir: "desc",
};

export default function PaginaProposicoes() {
  const { token } = useAuth();
  const [filtros, setFiltros] = useState<FiltrosProposicoes>(FILTROS_INICIAIS);
  const { dados, estado } = useProposicoes(token, filtros);
  const linhas = dados ? derivarProposicoesVista(dados.itens) : [];
  const totalPaginas = dados ? Math.max(1, Math.ceil(dados.total / dados.tamanhoPagina)) : 1;

  // Busca em texto livre: debounce de 300ms (review ecc:react-reviewer — sem isto, cada tecla digitada
  // disparava um refetch inteiro via useProposicoes). `buscaBruta` atualiza a cada tecla (input responsivo
  // ao olho); só depois de 300ms sem novas teclas ela empurra pro `filtros` real que dispara o fetch.
  // Select de espécie e paginação NÃO passam por aqui — só a digitação é rápida o bastante pra precisar.
  const [buscaBruta, setBuscaBruta] = useState("");
  useEffect(() => {
    const temporizador = setTimeout(() => {
      setFiltros((f) => ({ ...f, busca: buscaBruta || undefined, pagina: 1 }));
    }, 300);
    return () => clearTimeout(temporizador);
  }, [buscaBruta]);

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar as proposições</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }

  return (
    <>
      <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pagina-cab">
          <div>
            <span className="eyebrow">Acervo legislativo</span>
            <h1>Proposições</h1>
          </div>
          {estado === "pronto" && dados && (
            <span className="conta" aria-live="polite">
              <b>{linhas.length}</b> de {dados.total} matérias
            </span>
          )}
          <Link href={comToken("/editor-proposicao", token)} className="btn btn-primaria">
            Nova proposição
          </Link>
        </div>

        <form className="filtros" role="search" aria-label="Filtrar proposições" onSubmit={(e) => e.preventDefault()}>
          <div className="filtros-linha">
            <div className="busca">
              <label className="sr-only" htmlFor="busca">Buscar por número, ementa ou autor</label>
              <input
                id="busca"
                type="search"
                placeholder="Buscar por número, ementa ou autor…"
                value={buscaBruta}
                onChange={(e) => setBuscaBruta(e.target.value)}
              />
            </div>
            <span className="faceta">
              <label htmlFor="f-tipo">Espécie</label>
              <select
                id="f-tipo"
                disabled={estado === "carregando"}
                onChange={(e) => setFiltros((f) => ({ ...f, tipo: e.target.value || undefined, pagina: 1 }))}
              >
                <option value="">Todas</option>
                <option value="projeto_lei">Projeto de Lei</option>
                <option value="requerimento">Requerimento</option>
                <option value="mocao">Moção</option>
                <option value="indicacao">Indicação</option>
                <option value="projeto_resolucao">Projeto de Resolução</option>
                <option value="projeto_decreto_legislativo">Projeto de Decreto Leg.</option>
              </select>
            </span>
          </div>
        </form>

        {estado === "carregando" && <p role="status">Carregando…</p>}

        {estado === "pronto" && linhas.length === 0 && (
          <div className="vazio">
            <h2>Nenhuma matéria encontrada</h2>
            <p>Nenhuma proposição corresponde aos filtros atuais. Ajuste a busca ou limpe os filtros para ver o acervo completo.</p>
          </div>
        )}

        {estado === "pronto" && linhas.length > 0 && (
          <div className="tabela-wrap">
            <table className="tabela">
              <caption className="sr-only">
                Lista de proposições com número, espécie, ementa, autoria, situação e data de atualização.
              </caption>
              <thead>
                <tr>
                  <th scope="col">Nº / ano</th>
                  <th scope="col">Espécie</th>
                  <th scope="col">Ementa</th>
                  <th scope="col">Autoria</th>
                  <th scope="col">Situação</th>
                  <th scope="col">Atualizada</th>
                  <th scope="col">Ações</th>
                </tr>
              </thead>
              <tbody>
                {linhas.map((linha) => (
                  <tr key={linha.id}>
                    <td className="num">{linha.numero}</td>
                    <td className="especie">{linha.especie}</td>
                    <td className="ementa">{linha.ementa}</td>
                    <td className="autor">{linha.autor}</td>
                    <td>
                      <div className="sit">
                        <span className={`chip chip-${linha.situacao.categoria}`}>{linha.situacao.rotulo}</span>
                        <AzulejoMini
                          estagios={linha.situacao.estagios}
                          rotuloAria={descreverFaixa(linha.numero, linha.situacao.estagios)}
                        />
                      </div>
                    </td>
                    <td className="atualizada">{new Date(linha.atualizadoEm).toLocaleDateString("pt-BR")}</td>
                    <td>
                      <Link
                        href={comToken(`/editor-proposicao/${linha.id}`, token)}
                        aria-label={`Editar ${linha.numero}`}
                      >
                        Editar
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {dados && dados.total > 0 && (
          <nav className="paginacao" aria-label="Paginação">
            <span className="info">
              Página {dados.pagina} de {totalPaginas}
            </span>
            <div className="pag-nav">
              <button
                type="button"
                disabled={dados.pagina <= 1 || estado === "carregando"}
                onClick={() => setFiltros((f) => ({ ...f, pagina: f.pagina - 1 }))}
                aria-label="Página anterior"
              >
                ←
              </button>
              <button
                type="button"
                disabled={dados.pagina >= totalPaginas || estado === "carregando"}
                onClick={() => setFiltros((f) => ({ ...f, pagina: f.pagina + 1 }))}
                aria-label="Próxima página"
              >
                →
              </button>
            </div>
          </nav>
        )}
      </main>
    </>
  );
}
