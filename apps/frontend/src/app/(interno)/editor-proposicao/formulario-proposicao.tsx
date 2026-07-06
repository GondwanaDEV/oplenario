"use client";

// Ficha tecnica + ilha-papel do editor-proposicao (Onda B Slice 2) — porte de
// produto/design-system/o-plenario/telas/editor-proposicao.html, SEM o rail .copiloto (Track IA e'
// satelite) e SEM editor estruturado por artigo/inciso (textarea markdown com a convencao leve
// `## Art. Nº`, arquitetura/22-4-dados-legislativo.md eixo B, + helpers de insercao de snippet).
// Uma acao primaria so' (rotulo variavel: "Protocolar" na criacao, "Salvar alterações" na edicao) — nao
// existe "Salvar rascunho" batendo no backend (spec §2: criar = protocolar! imediato).

import { useRef, useState } from "react";
import "./formulario-proposicao.css";

const ESPECIES = [
  { valor: "projeto_lei", rotulo: "Projeto de Lei" },
  { valor: "projeto_lei_complementar", rotulo: "Projeto de Lei Complementar" },
  { valor: "projeto_resolucao", rotulo: "Projeto de Resolução" },
  { valor: "projeto_decreto_legislativo", rotulo: "Decreto Legislativo" },
  { valor: "proposta_emenda_lom", rotulo: "Emenda à LOM" },
  { valor: "requerimento", rotulo: "Requerimento" },
  { valor: "indicacao", rotulo: "Indicação" },
  { valor: "mocao", rotulo: "Moção" },
];

const AUTOR_TIPOS = [
  { valor: "vereador", rotulo: "Vereador" },
  { valor: "mesa", rotulo: "Mesa Diretora" },
  { valor: "comissao", rotulo: "Comissão" },
  { valor: "executivo", rotulo: "Poder Executivo" },
  { valor: "cidadao", rotulo: "Iniciativa popular" },
];

export type ValoresFormulario = {
  tipo: string;
  ano: number;
  ementa: string;
  autorTipo?: string;
  autorTexto?: string;
  objetoIndicacao?: string;
  tipoRequerimento?: string;
  categoriaMocao?: string;
  texto?: string;
};

const VAZIO: ValoresFormulario = { tipo: "projeto_lei", ano: new Date().getFullYear(), ementa: "" };

export function FormularioProposicao({
  valorInicial,
  aoSubmeter,
  enviando,
  erro,
  rotuloAcaoPrimaria,
  bloquearIdentidade = false,
}: {
  valorInicial?: ValoresFormulario;
  aoSubmeter: (valores: ValoresFormulario) => void;
  enviando: boolean;
  erro: string | null;
  rotuloAcaoPrimaria: string;
  bloquearIdentidade?: boolean;
}) {
  const [valores, setValores] = useState<ValoresFormulario>(valorInicial ?? VAZIO);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  function inserirNoCursor(snippet: string) {
    const el = textareaRef.current;
    if (!el) return;
    const inicio = el.selectionStart ?? valores.texto?.length ?? 0;
    const fim = el.selectionEnd ?? inicio;
    const atual = valores.texto ?? "";
    const novo = atual.slice(0, inicio) + snippet + atual.slice(fim);
    setValores((v) => ({ ...v, texto: novo }));
  }

  return (
    <form
      className="formulario-proposicao"
      onSubmit={(e) => {
        e.preventDefault();
        aoSubmeter(valores);
      }}
    >
      {erro && (
        <p role="alert" className="form-erro">
          {erro}
        </p>
      )}

      <div className="doc-ficha" role="group" aria-label="Ficha técnica da proposição">
        <div className="ficha-campo">
          <label htmlFor="f-especie">Espécie</label>
          <select
            id="f-especie"
            value={valores.tipo}
            disabled={bloquearIdentidade}
            onChange={(e) => setValores((v) => ({ ...v, tipo: e.target.value }))}
          >
            {ESPECIES.map((e) => (
              <option key={e.valor} value={e.valor}>
                {e.rotulo}
              </option>
            ))}
          </select>
        </div>
        <div className="ficha-campo">
          <label htmlFor="f-ano">Ano</label>
          <input
            id="f-ano"
            type="number"
            value={valores.ano}
            disabled={bloquearIdentidade}
            onChange={(e) => setValores((v) => ({ ...v, ano: Number(e.target.value) }))}
          />
        </div>
        <div className="ficha-campo">
          <label htmlFor="f-autor-tipo">Autor</label>
          <select
            id="f-autor-tipo"
            value={valores.autorTipo ?? ""}
            onChange={(e) => setValores((v) => ({ ...v, autorTipo: e.target.value || undefined }))}
          >
            <option value="">Não informado</option>
            {AUTOR_TIPOS.map((a) => (
              <option key={a.valor} value={a.valor}>
                {a.rotulo}
              </option>
            ))}
          </select>
        </div>
        {valores.autorTipo && (
          <div className="ficha-campo">
            <label htmlFor="f-autor-texto">Nome do autor</label>
            <input
              id="f-autor-texto"
              type="text"
              value={valores.autorTexto ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, autorTexto: e.target.value }))}
            />
          </div>
        )}
        {valores.tipo === "indicacao" && (
          <div className="ficha-campo">
            <label htmlFor="f-objeto-indicacao">Objeto da indicação</label>
            <input
              id="f-objeto-indicacao"
              type="text"
              value={valores.objetoIndicacao ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, objetoIndicacao: e.target.value }))}
            />
          </div>
        )}
        {valores.tipo === "requerimento" && (
          <div className="ficha-campo">
            <label htmlFor="f-tipo-requerimento">Tipo do requerimento</label>
            <input
              id="f-tipo-requerimento"
              type="text"
              value={valores.tipoRequerimento ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, tipoRequerimento: e.target.value }))}
            />
          </div>
        )}
        {valores.tipo === "mocao" && (
          <div className="ficha-campo">
            <label htmlFor="f-categoria-mocao">Categoria da moção</label>
            <input
              id="f-categoria-mocao"
              type="text"
              value={valores.categoriaMocao ?? ""}
              onChange={(e) => setValores((v) => ({ ...v, categoriaMocao: e.target.value }))}
            />
          </div>
        )}
      </div>

      <div className="campo-ementa">
        <label htmlFor="f-ementa">Ementa</label>
        <textarea
          id="f-ementa"
          rows={2}
          value={valores.ementa}
          onChange={(e) => setValores((v) => ({ ...v, ementa: e.target.value }))}
        />
      </div>

      <div className="ferramentas" role="toolbar" aria-label="Ferramentas de redação">
        <button type="button" onClick={() => inserirNoCursor("\n\nArt. Nº ")}>
          Inserir artigo
        </button>
        <button type="button" onClick={() => inserirNoCursor("\nI — ")}>
          Inciso
        </button>
        <button type="button" onClick={() => inserirNoCursor("\n§ ")}>
          § Parágrafo
        </button>
      </div>

      <div className="campo-texto">
        <label htmlFor="f-texto">Texto da proposição</label>
        <textarea
          id="f-texto"
          ref={textareaRef}
          rows={16}
          placeholder="## Art. 1º ..."
          value={valores.texto ?? ""}
          onChange={(e) => setValores((v) => ({ ...v, texto: e.target.value }))}
        />
      </div>

      <div className="comando">
        <button type="submit" className="btn btn-primaria" disabled={enviando}>
          {rotuloAcaoPrimaria}
        </button>
      </div>
    </form>
  );
}
