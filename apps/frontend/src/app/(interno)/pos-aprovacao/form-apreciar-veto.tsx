"use client";

// FormApreciarVeto — "Apreciar o veto" pela Câmara (GAP docs/20 bloco 3). Mesma disciplina de
// FormRegistrarRetorno: componente CONTROLADO, validação de campo obrigatório em JS (não via `required` do
// HTML — clique/submit em teste não dispara a constraint nativa), erro focável em `role="alert"`.
//
// `resultado` (radiogroup, vocabulário FECHADO em código, logic/estados-apreciacao-veto): veto_mantido |
// veto_derrubado. `veto-votacao-id` é OBRIGATÓRIO (o backend recusa sem ele) — referencia a votação do
// plenário que decidiu a apreciação; validar aqui evita a viagem de rede que voltaria 400.

import { useEffect, useRef, useState } from "react";

export type ValoresApreciacao = { resultado: string; vetoVotacaoId: string };

const OPCOES_RESULTADO = [
  { valor: "veto_derrubado", rotulo: "Veto derrubado (a Câmara rejeita o veto)" },
  { valor: "veto_mantido", rotulo: "Veto mantido (a Câmara acata o veto)" },
] as const;

export function FormApreciarVeto({
  aoApreciar,
  aoCancelar,
  enviando,
  erro,
}: {
  aoApreciar: (valores: ValoresApreciacao) => void;
  aoCancelar: () => void;
  enviando: boolean;
  erro: string | null;
}) {
  const [resultado, setResultado] = useState("");
  const [vetoVotacaoId, setVetoVotacaoId] = useState("");
  const [erroValidacao, setErroValidacao] = useState<string | null>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);

  const erroExibido = erro ?? erroValidacao;

  useEffect(() => {
    if (erroExibido) erroRef.current?.focus();
  }, [erroExibido]);

  function aoClicarApreciar() {
    if (!resultado) {
      setErroValidacao("Selecione o resultado da apreciação (derrubar ou manter o veto).");
      return;
    }
    if (!vetoVotacaoId.trim()) {
      setErroValidacao("Informe o ID da votação que decidiu a apreciação do veto.");
      return;
    }
    setErroValidacao(null);
    aoApreciar({ resultado, vetoVotacaoId: vetoVotacaoId.trim() });
  }

  return (
    <form className="form-apreciacao" onSubmit={(e) => e.preventDefault()}>
      {erroExibido && (
        <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
          {erroExibido}
        </p>
      )}

      <div role="radiogroup" aria-label="Resultado da apreciação do veto" className="fr-opcoes">
        {OPCOES_RESULTADO.map((opcao) => (
          <label key={opcao.valor} className="fr-opcao">
            <input
              type="radio"
              name="apreciacao-resultado"
              checked={resultado === opcao.valor}
              disabled={enviando}
              onChange={() => setResultado(opcao.valor)}
            />
            {opcao.rotulo}
          </label>
        ))}
      </div>

      <div className="campo">
        <label htmlFor="veto-votacao-id">ID da votação de apreciação</label>
        <input
          id="veto-votacao-id"
          type="text"
          value={vetoVotacaoId}
          disabled={enviando}
          onChange={(e) => setVetoVotacaoId(e.target.value)}
        />
        <p className="nota-gap">
          A apreciação é decidida em votação no plenário. Registre aqui o resultado e o ID da votação que o
          decidiu — a contagem de votos vive na própria votação.
        </p>
      </div>

      <div className="acoes">
        <button type="button" className="btn btn-contorno" disabled={enviando} onClick={aoCancelar}>
          Cancelar
        </button>
        <button type="button" className="btn btn-primaria" disabled={enviando} onClick={aoClicarApreciar}>
          Registrar apreciação
        </button>
      </div>
    </form>
  );
}
