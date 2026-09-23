"use client";

// FormularioModelo — aba "Modelos" (Onda B Slice 6, fatia de escrita). DOIS MODOS no mesmo componente
// (mesmo espírito de duas-fases de formulario-preenchimento.tsx, mas aqui são modos EXPLÍCITOS, não uma
// transição de fase pelo mesmo registro):
//   · `modo="novo"`: chave/nome/tipo-documento/corpo-template, todos editáveis (POST).
//   · `modo="editar"`: chave/tipo-documento em EXIBIÇÃO (wire/in.AtualizarModelo não os aceita — trocar a
//     identidade ou o tipo de um template já em uso mudaria retroativamente documentos já gerados por ele;
//     fora de escopo, sem pedido de cliente validado); nome/corpo-template editáveis + "Desativar" (PATCH
//     `ativo:false`, mesma disciplina de soft-delete do db: modelo não se apaga).
//
// CONTRATO DE MONTAGEM: mesmo de FormularioPreenchimento — o caller troca a `key` ao trocar de modelo
// (novo -> editar outro id), o estado interno só inicializa uma vez a partir das props.

import { useEffect, useRef, useState } from "react";
import { rotularTipoDocumento, TIPOS_DOCUMENTO } from "@/lib/expediente-vista";
import type { DocumentoModeloDetalheOut } from "@/lib/contrato-legislativo.gen";

export type ValoresNovoModelo = { chave: string; nome: string; tipoDocumento: string; corpoTemplate: string };
export type ValoresEditarModelo = { nome: string; corpoTemplate: string };

export function FormularioModelo({
  modo,
  modelo,
  aoCriar,
  aoSalvar,
  aoDesativar,
  aoCancelar,
  enviando,
  erro,
}: {
  modo: "novo" | "editar";
  modelo: DocumentoModeloDetalheOut | null;
  aoCriar: (valores: ValoresNovoModelo) => void;
  aoSalvar: (valores: ValoresEditarModelo) => void;
  aoDesativar: () => void;
  aoCancelar: () => void;
  enviando: boolean;
  erro: string | null;
}) {
  const [chave, setChave] = useState(modelo?.chave ?? "");
  const [nome, setNome] = useState(modelo?.nome ?? "");
  const [tipoDocumento, setTipoDocumento] = useState(modelo?.tipoDocumento ?? TIPOS_DOCUMENTO[0]);
  const [corpoTemplate, setCorpoTemplate] = useState(modelo?.corpoTemplate ?? "");
  const [erroValidacao, setErroValidacao] = useState<string | null>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);

  const erroExibido = erro ?? erroValidacao;

  useEffect(() => {
    if (erroExibido) erroRef.current?.focus();
  }, [erroExibido]);

  function aoSubmeter() {
    if (!nome.trim()) {
      setErroValidacao("Preencha o nome do modelo.");
      return;
    }
    if (!corpoTemplate.trim()) {
      setErroValidacao("Preencha o corpo do template.");
      return;
    }
    if (modo === "novo") {
      if (!chave.trim()) {
        setErroValidacao("Preencha a chave do modelo.");
        return;
      }
      setErroValidacao(null);
      aoCriar({ chave, nome, tipoDocumento, corpoTemplate });
    } else {
      setErroValidacao(null);
      aoSalvar({ nome, corpoTemplate });
    }
  }

  return (
    <section className="bloco" aria-labelledby="modelo-form-titulo">
      <div className="bloco-cabeca">
        <h2 id="modelo-form-titulo">{modo === "novo" ? "Novo modelo" : "Editar modelo"}</h2>
        {modelo && !modelo.ativo && <span className="modelo-tag-inativo">Inativo</span>}
      </div>
      <div className="bloco-corpo">
        {erroExibido && (
          <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
            {erroExibido}
          </p>
        )}

        <div className="campo">
          <label htmlFor="modelo-chave">Chave</label>
          {modo === "novo" ? (
            <input
              id="modelo-chave"
              className="entrada"
              type="text"
              value={chave}
              onChange={(e) => setChave(e.target.value)}
            />
          ) : (
            <input id="modelo-chave" className="entrada" type="text" value={chave} disabled readOnly />
          )}
        </div>

        <div className="campo">
          <label htmlFor="modelo-nome">Nome</label>
          <input
            id="modelo-nome"
            className="entrada"
            type="text"
            value={nome}
            onChange={(e) => setNome(e.target.value)}
          />
        </div>

        <div className="campo">
          <label htmlFor="modelo-tipo">Tipo de documento</label>
          {modo === "novo" ? (
            <select
              id="modelo-tipo"
              className="entrada"
              value={tipoDocumento}
              onChange={(e) => setTipoDocumento(e.target.value)}
            >
              {TIPOS_DOCUMENTO.map((t) => (
                <option key={t} value={t}>
                  {rotularTipoDocumento(t)}
                </option>
              ))}
            </select>
          ) : (
            <input
              id="modelo-tipo"
              className="entrada"
              type="text"
              value={rotularTipoDocumento(tipoDocumento)}
              disabled
              readOnly
            />
          )}
        </div>

        <div className="campo">
          <label htmlFor="modelo-corpo">Corpo do template</label>
          <textarea
            id="modelo-corpo"
            className="entrada"
            rows={8}
            value={corpoTemplate}
            onChange={(e) => setCorpoTemplate(e.target.value)}
            placeholder="Use {{campo}} para os placeholders que o servidor preenche ao gerar o documento."
          />
        </div>
      </div>

      <div className="modelo-form-acoes">
        <button type="button" className="btn btn-contorno" onClick={aoCancelar}>
          Cancelar
        </button>
        {modo === "editar" && modelo?.ativo && (
          <button type="button" className="btn btn-contorno" disabled={enviando} onClick={aoDesativar}>
            Desativar
          </button>
        )}
        <button type="button" className="btn btn-primaria" disabled={enviando} onClick={aoSubmeter}>
          {modo === "novo" ? "Criar modelo" : "Salvar"}
        </button>
      </div>
    </section>
  );
}
