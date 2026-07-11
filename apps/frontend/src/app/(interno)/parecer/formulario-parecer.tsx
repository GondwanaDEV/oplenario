"use client";

// FormularioParecer — a ilha de papel do editor de parecer (Onda B Slice 5): as 3 seções (Relatório,
// Análise, Voto) + a barra .comando fixa (Pré-visualizar/Salvar rascunho/Emitir parecer). Porte de
// produto/design-system/o-plenario/telas/parecer.html. Mesma disciplina de formulario-proposicao.tsx:
// componente CONTROLADO, `aoSalvarRascunho`/`aoEmitir` disparam os hooks de mutação do caller, estados de
// loading/erro sempre visíveis — erro do backend (prop `erro`) NUNCA é engolido, tem prioridade sobre
// qualquer validação local (CRÍTICO de "erro clobbering" corrigido na fatia anterior, não regredir).
//
// `votoRelator` é vocabulário regimental ABERTO no backend (sem enum) — a UI restringe a escolha às 3
// opções fixas de parecer-vista.ts (VOTO_OPCOES); validação de "voto obrigatório" é feita EM JS (não via
// atributo HTML `required`), porque `fireEvent.submit`/testes não disparam a validação de constraint
// nativa do browser — a msg fica visível e focável do mesmo jeito que o erro de backend (mesmo padrão de
// foco de formulario-proposicao.tsx).
//
// `bloqueado` (o parecer chegou a um dos 4 desfechos terminais — parecerEhTerminal, parecer-vista.ts)
// desabilita os campos e remove os botões de escrita; "Pré-visualizar" continua disponível (é leitura).
//
// A barra .comando É A de chassi.css (fixa no rodapé, `position:fixed`) — não redefinida aqui (ao
// contrário de formulario-proposicao.css, que reusa o NOME da classe só como flex-row local; aqui é a
// barra real de 3 ações do mockup). `position:fixed` tira o elemento da participação em grid (CSS spec),
// então renderizar `.comando` como filho de `.balcao` (grid do caller) não quebra o layout de 2 colunas.

import { useEffect, useRef, useState } from "react";
import { VOTO_OPCOES, rotularVoto, type VotoValor } from "@/lib/parecer-vista";
import "./formulario-parecer.css";

export type ValoresParecer = { relatorio: string; analise: string; votoRelator: string };

const ICONE_POR_CLASSE: Record<string, string> = {
  ve: "M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z",
  vf: "M5 12l5 5L20 6",
  vc: "M18 6 6 18M6 6l12 12",
};

export function FormularioParecer({
  valorInicial,
  aoSalvarRascunho,
  aoEmitir,
  enviandoRascunho,
  enviandoEmissao,
  erro,
  bloqueado,
  mensagemStatus,
}: {
  valorInicial: ValoresParecer;
  aoSalvarRascunho: (valores: ValoresParecer) => void;
  aoEmitir: (valores: ValoresParecer) => void;
  enviandoRascunho: boolean;
  enviandoEmissao: boolean;
  erro: string | null;
  bloqueado: boolean;
  mensagemStatus: string | null;
}) {
  const [valores, setValores] = useState<ValoresParecer>(valorInicial);
  const [preVisualizando, setPreVisualizando] = useState(false);
  const [erroValidacao, setErroValidacao] = useState<string | null>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);
  const barraRef = useRef<HTMLDivElement>(null);

  const erroExibido = erro ?? erroValidacao;

  useEffect(() => {
    if (erroExibido) erroRef.current?.focus();
  }, [erroExibido]);

  // Ajusta o padding-bottom do <main> pra barra .comando fixa nunca cobrir o fim do form — porte do
  // script inline de parecer.html. `document.querySelector('main')` funciona independente de onde
  // `.comando` está aninhado no React tree (fixed escapa o flow de qualquer ancestral sem transform).
  useEffect(() => {
    const barra = barraRef.current;
    const main = document.querySelector("main");
    if (!barra || !main) return;
    function ajustar() {
      (main as HTMLElement).style.paddingBottom = `${(barra as HTMLDivElement).offsetHeight + 28}px`;
    }
    ajustar();
    window.addEventListener("resize", ajustar);
    return () => window.removeEventListener("resize", ajustar);
  }, []);

  function aoClicarSalvarRascunho() {
    setErroValidacao(null);
    aoSalvarRascunho(valores);
  }

  function aoClicarEmitir() {
    if (!valores.votoRelator) {
      setErroValidacao("Selecione o voto do relator antes de emitir o parecer.");
      return;
    }
    setErroValidacao(null);
    aoEmitir(valores);
  }

  const rotuloVotoAtual = rotularVoto(valores.votoRelator || null);

  return (
    <>
      <form className="form-card" onSubmit={(e) => e.preventDefault()}>
        {erroExibido && (
          <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
            {erroExibido}
          </p>
        )}

        {preVisualizando ? (
          <div className="pre-visualizacao">
            <div className="secao">
              <h2>Relatório</h2>
              <p>{valores.relatorio || "(vazio)"}</p>
            </div>
            <div className="secao">
              <h2>Análise</h2>
              <p>{valores.analise || "(vazio)"}</p>
            </div>
            <div className="secao">
              <h2>Voto</h2>
              <p>{rotuloVotoAtual}</p>
            </div>
          </div>
        ) : (
          <>
            <div className="secao">
              <h2>Relatório</h2>
              <p className="aj">Resumo da matéria e do seu trâmite até aqui.</p>
              <div className="campo">
                <textarea
                  aria-label="Relatório"
                  value={valores.relatorio}
                  disabled={bloqueado}
                  onChange={(e) => setValores((v) => ({ ...v, relatorio: e.target.value }))}
                />
              </div>
            </div>

            <div className="secao">
              <h2>Análise</h2>
              <p className="aj">Constitucionalidade, juridicidade e técnica legislativa.</p>
              <div className="campo">
                <textarea
                  aria-label="Análise"
                  value={valores.analise}
                  disabled={bloqueado}
                  onChange={(e) => setValores((v) => ({ ...v, analise: e.target.value }))}
                />
              </div>
            </div>

            <div className="secao">
              <h2>Voto</h2>
              <p className="aj">A conclusão do parecer. Define como a matéria segue.</p>
              <div className="votos" role="radiogroup" aria-label="Voto do relator">
                {VOTO_OPCOES.map((opcao) => (
                  <div className={`voto ${opcao.classe}`} key={opcao.valor}>
                    <input
                      type="radio"
                      name="voto-relator"
                      id={`voto-${opcao.valor}`}
                      checked={valores.votoRelator === opcao.valor}
                      disabled={bloqueado}
                      onChange={() => setValores((v) => ({ ...v, votoRelator: opcao.valor as VotoValor }))}
                    />
                    <label htmlFor={`voto-${opcao.valor}`}>
                      <span className="ic" aria-hidden="true">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4">
                          <path d={ICONE_POR_CLASSE[opcao.classe]} />
                        </svg>
                      </span>
                      <b>{opcao.rotulo}</b>
                    </label>
                  </div>
                ))}
              </div>
            </div>
          </>
        )}
      </form>

      <div className="comando" role="region" aria-label="Ações do parecer" ref={barraRef}>
        <div className="envelope comando-grade">
          <div className="comando-ctx">
            <b>Parecer</b>
            <span>
              voto: {rotuloVotoAtual}
              {mensagemStatus ? ` · ${mensagemStatus}` : ""}
            </span>
          </div>
          <div className="comando-acoes">
            <button type="button" className="btn btn-fantasma" onClick={() => setPreVisualizando((p) => !p)}>
              {preVisualizando ? "Voltar a editar" : "Pré-visualizar"}
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
                  disabled={enviandoEmissao}
                  onClick={aoClicarEmitir}
                >
                  Emitir parecer
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </>
  );
}
