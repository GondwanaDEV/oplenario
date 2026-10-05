"use client";

// FormGerarAutografo — "Gerar autógrafo e enviar ao Executivo" com o prazo de sanção/veto.
// O backend (POST /legislativo/proposicoes/:id/autografo) aceita `prazo-resposta-em` OPCIONAL (instante
// ISO-8601) e o autógrafo é append-only: o prazo informado aqui não se corrige depois. Por isso:
//   - o campo é um DIA (o último em que o Executivo pode responder), vazio por padrão — o prazo é o da Lei
//     Orgânica de cada Município ([GAP] do projeto), a tela nunca o presume;
//   - antes de enviar, a data aparece por extenso ("O Executivo tem até 20/10/2026 …") e o aviso de que
//     não se altera depois;
//   - validação em JS (mesma disciplina de form-registrar-retorno.tsx), espelhando o que o backend recusa:
//     data inexistente e prazo no passado.
// `hoje` é injetável só para teste determinístico; em uso vem do relógio, no dia da Casa.

import { useEffect, useRef, useState } from "react";
import {
  fraseDoPrazoDoExecutivo,
  hojeDaCasa,
  instanteFimDoDia,
  validarDiaDoPrazo,
} from "@/lib/pos-aprovacao-vista";

export function FormGerarAutografo({
  aoGerar,
  enviando,
  erro,
  hoje,
}: {
  /** `prazo` = instante ISO do fim do dia escolhido; `undefined` quando nenhum prazo foi informado. */
  aoGerar: (prazo: string | undefined) => void;
  enviando: boolean;
  erro: string | null;
  hoje?: string;
}) {
  const [dia, setDia] = useState("");
  const [erroValidacao, setErroValidacao] = useState<string | null>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);
  const [diaDeHoje] = useState(() => hoje ?? hojeDaCasa());

  const erroExibido = erroValidacao ?? erro;
  const diaValido = dia !== "" && validarDiaDoPrazo(dia, diaDeHoje) === null;

  useEffect(() => {
    if (erroExibido) erroRef.current?.focus();
  }, [erroExibido]);

  function aoClicar() {
    const problema = validarDiaDoPrazo(dia, diaDeHoje);
    if (problema) {
      setErroValidacao(problema);
      return;
    }
    setErroValidacao(null);
    aoGerar(dia === "" ? undefined : instanteFimDoDia(dia));
  }

  return (
    <form className="form-gerar-autografo" onSubmit={(e) => e.preventDefault()}>
      {erroExibido && (
        <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
          {erroExibido}
        </p>
      )}

      <div className="campo">
        <label htmlFor="prazo-do-executivo">Prazo de sanção ou veto do Executivo (último dia)</label>
        <input
          id="prazo-do-executivo"
          type="date"
          value={dia}
          min={diaDeHoje}
          disabled={enviando}
          aria-describedby="prazo-do-executivo-ajuda"
          onChange={(e) => {
            setDia(e.target.value);
            setErroValidacao(null);
          }}
        />
      </div>

      <div id="prazo-do-executivo-ajuda" className="nota-gap">
        <p>
          O prazo é o que a Lei Orgânica do Município dá ao Prefeito para sancionar ou vetar; o artigo varia
          por Câmara.
        </p>
        {diaValido ? (
          <p>
            <strong>{fraseDoPrazoDoExecutivo(instanteFimDoDia(dia))}</strong>
          </p>
        ) : (
          <p>Sem prazo informado, o sistema não acompanha o vencimento.</p>
        )}
        <p>Depois de gerado, o prazo não pode ser alterado.</p>
      </div>

      <div className="acoes">
        <button type="button" className="btn btn-primaria" disabled={enviando} onClick={aoClicar}>
          Gerar autógrafo e enviar ao Executivo
        </button>
      </div>
    </form>
  );
}
