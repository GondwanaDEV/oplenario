"use client";

// CardNorma — "Lei" (F3.8b): o fim do caminho da aprovação à lei. Dois atos, cada um na sua vez:
//   * PROMULGAR (sem norma, desfecho que promulga): pede confirmação, porque não se desfaz — o número da espécie
//     no ano fica reservado para esta matéria e o texto do autógrafo vira o texto da lei;
//   * REGISTRAR A PUBLICAÇÃO (norma promulgada): o veículo é a prova de que a lei saiu (Diário Oficial, mural...).
// Publicada, o card só mostra. Mesma disciplina de FormApreciarVeto: validação em JS, erro focável em role=alert.

import { useEffect, useRef, useState } from "react";
import { formatarData } from "@/lib/formatar-data";
import { formatarNumeroNorma } from "@/lib/pos-aprovacao-vista";
import type { NormaOut } from "@/lib/contrato-legislativo.gen";

export function CardNorma({
  norma,
  aoPromulgar,
  aoPublicar,
  enviando,
  erro,
  mensagem,
}: {
  norma: NormaOut | null;
  aoPromulgar: () => void;
  aoPublicar: (veiculo: string) => void;
  enviando: boolean;
  erro: string | null;
  mensagem: string | null;
}) {
  const [confirmando, setConfirmando] = useState(false);
  const [veiculo, setVeiculo] = useState("");
  const [erroValidacao, setErroValidacao] = useState<string | null>(null);
  const erroRef = useRef<HTMLParagraphElement>(null);
  const erroExibido = erro ?? erroValidacao;

  useEffect(() => {
    if (erroExibido) erroRef.current?.focus();
  }, [erroExibido]);

  function aoClicarPublicar() {
    if (!veiculo.trim()) {
      setErroValidacao("Informe onde a lei foi publicada.");
      return;
    }
    setErroValidacao(null);
    aoPublicar(veiculo.trim());
  }

  return (
    <div className="card card-norma">
      <h2>Lei</h2>
      {mensagem && <p role="status" className="status-norma">{mensagem}</p>}
      {erroExibido && (
        <p ref={erroRef} tabIndex={-1} role="alert" className="form-erro">
          {erroExibido}
        </p>
      )}

      {!norma && (
        <>
          <p>
            O retorno do Executivo permite promulgar. A lei recebe o próximo número da espécie neste ano e o
            endereço LexML, e leva o texto do autógrafo.
          </p>
          {!confirmando ? (
            <div className="acoes">
              <button type="button" className="btn btn-primaria" onClick={() => setConfirmando(true)}>
                Promulgar a lei
              </button>
            </div>
          ) : (
            <div role="group" aria-label="Confirmar a promulgação" className="confirmar-norma">
              <p>Promulgar não se desfaz: o número fica reservado para esta matéria e o texto não muda mais.</p>
              <div className="acoes">
                <button type="button" className="btn btn-contorno" disabled={enviando} onClick={() => setConfirmando(false)}>
                  Cancelar
                </button>
                <button type="button" className="btn btn-primaria" disabled={enviando} onClick={aoPromulgar}>
                  Confirmar a promulgação
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {norma && (
        <>
          <dl className="dl">
            <dt>Número</dt>
            <dd>{formatarNumeroNorma(norma.tipoNorma, norma.numero, norma.ano)}</dd>
            <dt>Promulgada em</dt>
            <dd className="mono">{formatarData(norma.promulgadoEm)}</dd>
            <dt>URN</dt>
            <dd className="mono urn">{norma.urn}</dd>
            {norma.estado === "publicada" && (
              <>
                <dt>Publicada em</dt>
                <dd className="mono">{norma.publicadoEm ? formatarData(norma.publicadoEm) : "—"}</dd>
                <dt>Onde</dt>
                <dd>{norma.veiculoPublicacao}</dd>
              </>
            )}
          </dl>

          {norma.estado === "publicada" ? (
            <p className="nota-gap">A lei publicada aparece no portal da Câmara, em Leis e normas.</p>
          ) : (
            <form className="form-publicacao" onSubmit={(e) => e.preventDefault()}>
              <div className="campo">
                <label htmlFor="norma-veiculo">Onde a lei foi publicada</label>
                <input
                  id="norma-veiculo"
                  type="text"
                  maxLength={300}
                  value={veiculo}
                  disabled={enviando}
                  aria-describedby="norma-veiculo-dica"
                  onChange={(e) => setVeiculo(e.target.value)}
                />
                <p id="norma-veiculo-dica" className="nota-gap">
                  Por exemplo: Diário Oficial do Município, ed. 1.234, p. 3. É a prova de que a lei saiu; depois
                  de registrada, a lei vai para o portal.
                </p>
              </div>
              <div className="acoes">
                <button type="button" className="btn btn-primaria" disabled={enviando} onClick={aoClicarPublicar}>
                  Registrar publicação
                </button>
              </div>
            </form>
          )}
        </>
      )}
    </div>
  );
}
