"use client";

// Ficha tecnica + ilha-papel do editor-proposicao (Onda B Slice 2) — porte de
// produto/design-system/o-plenario/telas/editor-proposicao.html, SEM o rail .copiloto (Track IA e'
// satelite) e SEM editor estruturado por artigo/inciso (textarea markdown com a convencao leve
// `## Art. Nº`, arquitetura/22-4-dados-legislativo.md eixo B, + helpers de insercao de snippet).
// Uma acao primaria so' (rotulo variavel: "Protocolar" na criacao, "Salvar alterações" na edicao) — nao
// existe "Salvar rascunho" batendo no backend (spec §2: criar = protocolar! imediato).

import { useEffect, useRef, useState } from "react";
import { ESPECIES_PROPOSICAO } from "@/lib/proposicoes-vista";
import { useVereadores } from "@/lib/use-vereadores";
import type { VereadorLinhaOut } from "@/lib/contrato-cadastros.gen";
import "./formulario-proposicao.css";

// A lista de espécies é a MESMA do filtro da lista de proposições e do quadro (uma fonte só).
const ESPECIES = ESPECIES_PROPOSICAO;

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
  /** Só com `autorTipo` "vereador": o cadastro do vereador (o backend confere que é desta Casa). */
  autorId?: string;
  autorTexto?: string;
  objetoIndicacao?: string;
  tipoRequerimento?: string;
  categoriaMocao?: string;
  texto?: string;
};

function nomeDoVereador(v: VereadorLinhaOut): string {
  return v.nomeParlamentar ?? v.nome;
}

// Autoria por vereador: escolhe no cadastro (em exercício) em vez de digitar o nome. O id é o que liga a
// matéria ao perfil público do vereador; o nome vai junto em `autorTexto` porque o backend exige os dois na
// mesma escrita. Quem já está ligado e saiu do exercício (licença, fim de mandato) continua na lista para
// não perder o vínculo ao editar. Sem a lista, cai no nome por texto, avisando que não haverá vínculo.
function SeletorVereadorAutor({
  token,
  autorId,
  autorTexto,
  aoEscolher,
  aoDigitar,
}: {
  token: string | null;
  autorId?: string;
  autorTexto?: string;
  aoEscolher: (v: { id: string; nome: string } | null) => void;
  aoDigitar: (texto: string) => void;
}) {
  const { dados, estado } = useVereadores(token);

  if (estado === "erro") {
    return (
      <div className="ficha-campo">
        <label htmlFor="f-autor-texto">Nome do autor</label>
        <input id="f-autor-texto" type="text" value={autorTexto ?? ""} onChange={(e) => aoDigitar(e.target.value)} />
        <p role="alert" className="ficha-dica">
          Não foi possível carregar os vereadores agora. O nome fica só como texto, sem ligação com o cadastro.
        </p>
      </div>
    );
  }

  const opcoes = dados.filter((v) => v.estadoMandato === "vigente" || v.id === autorId);
  const semVinculo = !autorId && !!autorTexto;
  return (
    <div className="ficha-campo">
      <label htmlFor="f-autor-vereador">Vereador autor</label>
      <select
        id="f-autor-vereador"
        value={autorId ?? ""}
        required={!autorTexto}
        disabled={estado === "carregando"}
        onChange={(e) => {
          const escolhido = dados.find((v) => v.id === e.target.value);
          aoEscolher(escolhido ? { id: escolhido.id, nome: nomeDoVereador(escolhido) } : null);
        }}
      >
        <option value="">{estado === "carregando" ? "Carregando…" : "Escolha o vereador…"}</option>
        {opcoes.map((v) => (
          <option key={v.id} value={v.id}>
            {nomeDoVereador(v)}
          </option>
        ))}
      </select>
      {semVinculo && (
        <p className="ficha-dica">
          Autoria atual: {autorTexto}, sem ligação com o cadastro. Escolha o vereador para ligar.
        </p>
      )}
    </div>
  );
}

const VAZIO: ValoresFormulario = { tipo: "projeto_lei", ano: new Date().getFullYear(), ementa: "" };

export function FormularioProposicao({
  valorInicial,
  aoSubmeter,
  enviando,
  erro,
  rotuloAcaoPrimaria,
  bloquearIdentidade = false,
  token = null,
}: {
  /** Credencial do modo dev; sem ela vale a sessão por cookie. Só a lista de vereadores usa. */
  token?: string | null;
  valorInicial?: ValoresFormulario;
  aoSubmeter: (valores: ValoresFormulario) => void;
  enviando: boolean;
  erro: string | null;
  rotuloAcaoPrimaria: string;
  bloquearIdentidade?: boolean;
}) {
  const [valores, setValores] = useState<ValoresFormulario>(valorInicial ?? VAZIO);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);

  // Ao surgir um erro, leva o foco (e a rolagem) ao alerta no topo do form — o botao de submit fica no
  // rodape, muito abaixo do alerta, entao sem isto um usuario de teclado nao percebe o erro que apareceu.
  useEffect(() => {
    if (erro) erroRef.current?.focus();
  }, [erro]);

  function inserirNoCursor(snippet: string) {
    const el = textareaRef.current;
    if (!el) return;
    el.focus();
    // execCommand('insertText') preserva a posicao do cursor E o historico de undo nativo do textarea
    // (um setValores cru resetaria o caret p/ o fim e seria invisivel ao Ctrl+Z). Dispara um evento
    // 'input' que o onChange controlado captura, mantendo o estado React em sincronia.
    let ok = false;
    try {
      ok = document.execCommand("insertText", false, snippet);
    } catch {
      ok = false;
    }
    if (!ok) {
      // fallback (ambiente sem execCommand, ex.: jsdom): insere via estado, aceitando perda de cursor/undo.
      const inicio = el.selectionStart ?? valores.texto?.length ?? 0;
      const fim = el.selectionEnd ?? inicio;
      const atual = valores.texto ?? "";
      setValores((v) => ({ ...v, texto: atual.slice(0, inicio) + snippet + atual.slice(fim) }));
    }
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
        <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
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
            onChange={(e) =>
              // Trocar a especie zera os campos condicionais da especie anterior — senao um
              // objetoIndicacao/tipoRequerimento/categoriaMocao preenchido e depois escondido viajaria
              // no corpo (corpoKebab so' descarta undefined, nao valor obsoleto-mas-irrelevante).
              setValores((v) => ({
                ...v,
                tipo: e.target.value,
                objetoIndicacao: undefined,
                tipoRequerimento: undefined,
                categoriaMocao: undefined,
              }))
            }
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
            min={1900}
            max={2200}
            required
            value={valores.ano}
            disabled={bloquearIdentidade}
            onChange={(e) => {
              // Number("") === 0 — sem o guard, esvaziar o campo submeteria ano:0 silenciosamente.
              const n = Number(e.target.value);
              setValores((v) => ({ ...v, ano: Number.isNaN(n) ? v.ano : n }));
            }}
          />
        </div>
        <div className="ficha-campo">
          <label htmlFor="f-autor-tipo">Autor</label>
          <select
            id="f-autor-tipo"
            value={valores.autorTipo ?? ""}
            onChange={(e) =>
              setValores((v) => {
                const novo = e.target.value || undefined;
                // o id só vale para "vereador", e o nome de um vereador não pode sobrar numa autoria de outro
                // tipo (a autoria é pública). Entre tipos que são só texto, o que foi digitado fica.
                const cruzaVereador = (v.autorTipo === "vereador") !== (novo === "vereador");
                return {
                  ...v,
                  autorTipo: novo,
                  autorId: undefined,
                  autorTexto: cruzaVereador ? undefined : v.autorTexto,
                };
              })
            }
          >
            <option value="">Não informado</option>
            {AUTOR_TIPOS.map((a) => (
              <option key={a.valor} value={a.valor}>
                {a.rotulo}
              </option>
            ))}
          </select>
        </div>
        {valores.autorTipo === "vereador" && (
          <SeletorVereadorAutor
            token={token}
            autorId={valores.autorId}
            autorTexto={valores.autorTexto}
            aoEscolher={(escolhido) =>
              setValores((v) => ({ ...v, autorId: escolhido?.id, autorTexto: escolhido?.nome }))
            }
            aoDigitar={(texto) => setValores((v) => ({ ...v, autorTexto: texto }))}
          />
        )}
        {valores.autorTipo && valores.autorTipo !== "vereador" && (
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
              required
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
              required
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
              required
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
          required
          value={valores.ementa}
          onChange={(e) => setValores((v) => ({ ...v, ementa: e.target.value }))}
        />
      </div>

      <div className="ferramentas" role="group" aria-label="Ferramentas de redação">
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
