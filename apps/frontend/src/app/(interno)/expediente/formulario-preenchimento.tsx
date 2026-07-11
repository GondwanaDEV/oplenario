"use client";

// FormularioPreenchimento — passo 2 de 3 da aba "Gerar documento" (Onda B Slice 6) + a barra de ações fixa
// (reusa `.comando` de chassi.css, mesmo padrão de formulario-parecer.tsx). DUAS FASES no mesmo componente:
//
//   · `documento === null` (composição, antes do POST): Assunto + linhas dinâmicas de "Dados" (o mapa
//     chave->valor que preenche os {{placeholder}} do modelo, expediente-vista.ts/paresParaMapaDados) +
//     "Gerar documento". DESVIO do mockup: "Destinatário"/"Vínculo à matéria" do mockup NÃO têm campo
//     próprio no wire (GerarDocumento só aceita modelo-id/assunto/dados) — em vez de fingir esses campos,
//     o formulário genérico de "Dados" cobre o mesmo propósito real (o servidor digita o que o modelo
//     precisar) sem inventar uma resolução de cadastro que não existe.
//   · `documento` existente (edição, rascunho): Assunto + Corpo (já com o merge aplicado pelo servidor,
//     livre pra revisão) + "Salvar rascunho"/"Protocolar e numerar". `bloqueado` (estado terminal =
//     "emitido") desabilita campos e remove os botões de escrita — mesma disciplina de bloqueado em
//     formulario-parecer.tsx. "Gerar PDF" fica SEMPRE desabilitado (rota inexistente nesta fatia).
//
// CONTRATO DE MONTAGEM: o caller deve trocar a `key` do componente ao transicionar de fase (`documento` de
// null pra um id real) — o estado interno (`assunto`/`corpo`/`dados`) só é inicializado UMA VEZ a partir das
// props (mesmo padrão de FormularioParecer), então uma transição de fase precisa de uma montagem NOVA, não
// de um re-sync de estado dentro do componente já montado.

import { useEffect, useRef, useState } from "react";
import { documentoEhTerminal, rotularEstadoDocumento } from "@/lib/expediente-vista";
import type { DocumentoOut } from "@/lib/contrato-legislativo.gen";
import "./formulario-preenchimento.css";

export type ValoresGeracao = { assunto: string; dados: Record<string, string> };
export type ValoresEdicao = { assunto: string; corpo: string };

type ParDados = { chave: string; valor: string };

export function FormularioPreenchimento({
  documento,
  modeloSelecionadoId,
  aoGerar,
  aoSalvarRascunho,
  aoProtocolar,
  enviandoGeracao,
  enviandoRascunho,
  enviandoProtocolo,
  erro,
  mensagemStatus,
}: {
  documento: DocumentoOut | null;
  modeloSelecionadoId: string | null;
  aoGerar: (valores: ValoresGeracao) => void;
  aoSalvarRascunho: (valores: ValoresEdicao) => void;
  aoProtocolar: () => void;
  enviandoGeracao: boolean;
  enviandoRascunho: boolean;
  enviandoProtocolo: boolean;
  erro: string | null;
  mensagemStatus: string | null;
}) {
  const [assunto, setAssunto] = useState(documento?.assunto ?? "");
  const [corpo, setCorpo] = useState(documento?.corpo ?? "");
  const [dadosPares, setDadosPares] = useState<ParDados[]>([{ chave: "", valor: "" }]);
  const [erroValidacao, setErroValidacao] = useState<string | null>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);
  const barraRef = useRef<HTMLDivElement>(null);

  const erroExibido = erro ?? erroValidacao;

  useEffect(() => {
    if (erroExibido) erroRef.current?.focus();
  }, [erroExibido]);

  // Mesmo ajuste de padding-bottom de formulario-parecer.tsx — a barra .comando é `position: fixed`, então
  // sem isto o fim do formulário (ou o Livro do Protocolo Geral, mais abaixo na página) fica escondido atrás
  // dela.
  useEffect(() => {
    const barra = barraRef.current;
    const main = document.querySelector("main");
    if (!barra || !main) return;
    function ajustar() {
      (main as HTMLElement).style.paddingBottom = `${(barra as HTMLDivElement).offsetHeight + 28}px`;
    }
    ajustar();
    const observador = new ResizeObserver(ajustar);
    observador.observe(barra);
    return () => observador.disconnect();
  }, []);

  function aoMudarPar(i: number, campo: "chave" | "valor", valor: string) {
    setDadosPares((pares) => pares.map((p, idx) => (idx === i ? { ...p, [campo]: valor } : p)));
  }

  function aoAdicionarPar() {
    setDadosPares((pares) => [...pares, { chave: "", valor: "" }]);
  }

  function aoRemoverPar(i: number) {
    setDadosPares((pares) => pares.filter((_, idx) => idx !== i));
  }

  function aoClicarGerar() {
    if (!assunto.trim()) {
      setErroValidacao("Preencha o assunto antes de gerar o documento.");
      return;
    }
    setErroValidacao(null);
    const dados = Object.fromEntries(
      dadosPares.map(({ chave, valor }) => [chave.trim(), valor]).filter(([chave]) => (chave as string).length > 0),
    );
    aoGerar({ assunto, dados });
  }

  function aoClicarSalvarRascunho() {
    setErroValidacao(null);
    aoSalvarRascunho({ assunto, corpo });
  }

  const bloqueado = documento ? documentoEhTerminal(documento.estado) : false;

  return (
    <>
      {documento === null ? (
        <section className="bloco" aria-labelledby="preench-titulo">
          <div className="bloco-cabeca">
            <h2 id="preench-titulo">Preenchimento</h2>
            <span className="passo">passo 2 de 3</span>
          </div>
          <div className="bloco-corpo">
            {erroExibido && (
              <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
                {erroExibido}
              </p>
            )}
            <div className="campo">
              <label htmlFor="assunto">Assunto</label>
              <input
                id="assunto"
                className="entrada"
                type="text"
                value={assunto}
                onChange={(e) => setAssunto(e.target.value)}
              />
            </div>

            <fieldset className="dados-lista">
              <legend>Dados para o modelo (preenche os campos {"{{ }}"} do template)</legend>
              {dadosPares.map((par, i) => (
                <div className="dados-linha" key={i}>
                  <div className="campo">
                    <label htmlFor={`dados-chave-${i}`}>Campo {i + 1}</label>
                    <input
                      id={`dados-chave-${i}`}
                      className="entrada"
                      type="text"
                      value={par.chave}
                      onChange={(e) => aoMudarPar(i, "chave", e.target.value)}
                    />
                  </div>
                  <div className="campo">
                    <label htmlFor={`dados-valor-${i}`}>Valor {i + 1}</label>
                    <input
                      id={`dados-valor-${i}`}
                      className="entrada"
                      type="text"
                      value={par.valor}
                      onChange={(e) => aoMudarPar(i, "valor", e.target.value)}
                    />
                  </div>
                  {dadosPares.length > 1 && (
                    <button
                      type="button"
                      className="dados-remover"
                      aria-label={`Remover linha ${i + 1} de dados`}
                      onClick={() => aoRemoverPar(i)}
                    >
                      ✕
                    </button>
                  )}
                </div>
              ))}
              <button type="button" className="ia-link" onClick={aoAdicionarPar}>
                + Adicionar campo
              </button>
            </fieldset>
          </div>
        </section>
      ) : (
        <section className="bloco" aria-labelledby="preench-titulo">
          <div className="bloco-cabeca">
            <h2 id="preench-titulo">Preenchimento</h2>
            <span className="passo">{rotularEstadoDocumento(documento.estado)}</span>
          </div>
          <div className="bloco-corpo">
            {erroExibido && (
              <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
                {erroExibido}
              </p>
            )}
            <div className="campo">
              <label htmlFor="assunto">Assunto</label>
              <input
                id="assunto"
                className="entrada"
                type="text"
                value={assunto}
                disabled={bloqueado}
                onChange={(e) => setAssunto(e.target.value)}
              />
            </div>
            <div className="campo">
              <label htmlFor="corpo">Corpo do documento</label>
              <textarea
                id="corpo"
                className="entrada"
                rows={6}
                value={corpo}
                disabled={bloqueado}
                onChange={(e) => setCorpo(e.target.value)}
              />
            </div>
          </div>
        </section>
      )}

      <div className="comando" role="region" aria-label="Ações do documento" ref={barraRef}>
        <div className="envelope comando-grade">
          <div className="comando-ctx">
            <b>{documento ? rotularEstadoDocumento(documento.estado) : "Novo documento"}</b>
            <span>
              {assunto || "sem assunto"}
              {mensagemStatus ? ` — ${mensagemStatus}` : ""}
            </span>
          </div>
          <div className="comando-acoes">
            {documento === null ? (
              <button
                type="button"
                className="btn btn-primaria"
                disabled={enviandoGeracao || !modeloSelecionadoId}
                onClick={aoClicarGerar}
              >
                Gerar documento
              </button>
            ) : (
              <>
                <button
                  type="button"
                  className="btn btn-contorno"
                  disabled
                  title="Gerar PDF ainda não está disponível nesta fatia."
                >
                  Gerar PDF
                </button>
                {!bloqueado && (
                  <>
                    <button
                      type="button"
                      className="btn btn-contorno"
                      disabled={enviandoRascunho}
                      onClick={aoClicarSalvarRascunho}
                    >
                      Salvar rascunho
                    </button>
                    <button
                      type="button"
                      className="btn btn-primaria"
                      disabled={enviandoProtocolo}
                      onClick={() => aoProtocolar()}
                    >
                      Protocolar e numerar
                    </button>
                  </>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </>
  );
}
