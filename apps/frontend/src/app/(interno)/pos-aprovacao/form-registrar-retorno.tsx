"use client";

// FormRegistrarRetorno — "Registrar retorno" do Executivo (Onda B Slice 7). Mesma disciplina de
// FormularioParecer (parecer/formulario-parecer.tsx): componente CONTROLADO, validação de campo
// obrigatório em JS (não via `required` do HTML — `fireEvent.submit`/clique não dispara a validação de
// constraint nativa do browser em teste), erro focável em `role="alert"`.
//
// `resultado` (radiogroup, 3 opções fechadas: sancionado|sancao_tacita|vetado — vocabulário FECHADO em
// código, logic/estados-resposta-executivo, ao contrário do voto-de-parecer que é aberto) — `veto-tipo`/
// `veto-razoes` só aparecem quando "vetado" está selecionado (o backend também recusa `veto-tipo` ausente
// com "vetado", adapters/in/pos_aprovacao.clj; a validação aqui evita a viagem de rede que voltaria 400).

import { useEffect, useRef, useState } from "react";

export type ValoresRetorno = { resultado: string; vetoTipo?: string; vetoRazoes?: string };

const OPCOES_RESULTADO = [
  { valor: "sancionado", rotulo: "Sancionado" },
  { valor: "sancao_tacita", rotulo: "Sanção tácita" },
  { valor: "vetado", rotulo: "Vetado" },
] as const;

const OPCOES_VETO_TIPO = [
  { valor: "total", rotulo: "Total" },
  { valor: "parcial", rotulo: "Parcial" },
] as const;

export function FormRegistrarRetorno({
  aoRegistrar,
  aoCancelar,
  enviando,
  erro,
}: {
  aoRegistrar: (valores: ValoresRetorno) => void;
  aoCancelar: () => void;
  enviando: boolean;
  erro: string | null;
}) {
  const [resultado, setResultado] = useState("");
  const [vetoTipo, setVetoTipo] = useState("");
  const [vetoRazoes, setVetoRazoes] = useState("");
  const [erroValidacao, setErroValidacao] = useState<string | null>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);

  const erroExibido = erro ?? erroValidacao;

  useEffect(() => {
    if (erroExibido) erroRef.current?.focus();
  }, [erroExibido]);

  function aoClicarRegistrar() {
    if (!resultado) {
      setErroValidacao("Selecione o resultado do Executivo.");
      return;
    }
    if (resultado === "vetado" && !vetoTipo) {
      setErroValidacao("Selecione o tipo do veto (total ou parcial).");
      return;
    }
    setErroValidacao(null);
    aoRegistrar({
      resultado,
      vetoTipo: resultado === "vetado" ? vetoTipo : undefined,
      vetoRazoes: resultado === "vetado" && vetoRazoes ? vetoRazoes : undefined,
    });
  }

  return (
    <form className="form-retorno" onSubmit={(e) => e.preventDefault()}>
      {erroExibido && (
        <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
          {erroExibido}
        </p>
      )}

      <div role="radiogroup" aria-label="Resultado do Executivo" className="fr-opcoes">
        {OPCOES_RESULTADO.map((opcao) => (
          <label key={opcao.valor} className="fr-opcao">
            <input
              type="radio"
              name="resultado"
              checked={resultado === opcao.valor}
              disabled={enviando}
              onChange={() => setResultado(opcao.valor)}
            />
            {opcao.rotulo}
          </label>
        ))}
      </div>

      {resultado === "vetado" && (
        <div className="fr-veto">
          <div role="radiogroup" aria-label="Tipo do veto" className="fr-opcoes">
            {OPCOES_VETO_TIPO.map((opcao) => (
              <label key={opcao.valor} className="fr-opcao">
                <input
                  type="radio"
                  name="veto-tipo"
                  checked={vetoTipo === opcao.valor}
                  disabled={enviando}
                  onChange={() => setVetoTipo(opcao.valor)}
                />
                {opcao.rotulo}
              </label>
            ))}
          </div>
          <div className="campo">
            <label htmlFor="veto-razoes">Razões do veto</label>
            <textarea
              id="veto-razoes"
              value={vetoRazoes}
              disabled={enviando}
              onChange={(e) => setVetoRazoes(e.target.value)}
            />
          </div>
        </div>
      )}

      <div className="acoes">
        <button type="button" className="btn btn-contorno" disabled={enviando} onClick={aoCancelar}>
          Cancelar
        </button>
        <button type="button" className="btn btn-primaria" disabled={enviando} onClick={aoClicarRegistrar}>
          Registrar retorno
        </button>
      </div>
    </form>
  );
}
