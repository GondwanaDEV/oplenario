"use client";

// O relator pede o PARECER JURÍDICO da matéria que relata (ADR-0019, Eixo 2). Mostra primeiro o que já existe — pedidos
// em aberto e o parecer assinado vigente (GET /api/legislativo/proposicoes/:id/pareceres-juridicos, que o vereador lê) —
// e só então oferece o pedido: assunto opcional, confirmação e o recibo. O parecer jurídico é opinativo e o pedido não
// move a matéria; a tela diz as duas coisas.

import { useState } from "react";
import { AVISO_OPINATIVO, ASSUNTO_PADRAO_DA_MATERIA } from "@/lib/juridico-vista";
import {
  CONFIRMACAO_PEDIDO_DO_RELATOR,
  RECIBO_PEDIDO_DO_RELATOR,
  linhasDaSituacaoJuridica,
  pedirParecerJuridicoDoRelator,
} from "@/lib/juridico-do-relator";
import { usePareceresJuridicosDaMateria } from "@/lib/use-pareceres-juridicos-da-materia";

export function PedidoJuridicoDoRelator({
  token,
  parecerId,
  proposicaoId,
}: {
  token: string | null;
  parecerId: string;
  proposicaoId: string;
}) {
  const { estado, recarregar } = usePareceresJuridicosDaMateria(token, proposicaoId);
  const [confirmando, setConfirmando] = useState(false);
  const [assunto, setAssunto] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [recibo, setRecibo] = useState<string | null>(null);

  async function confirmar() {
    setEnviando(true);
    setErro(null);
    try {
      const r = await pedirParecerJuridicoDoRelator(token, parecerId, assunto);
      if (!r.ok) {
        setErro(r.mensagem);
        return;
      }
      setConfirmando(false);
      setAssunto("");
      setRecibo(`${RECIBO_PEDIDO_DO_RELATOR} Assunto: ${r.dado.assunto}.`);
      recarregar();
    } finally {
      setEnviando(false);
    }
  }

  const linhas = estado.fase === "pronto" ? linhasDaSituacaoJuridica(estado.dado) : [];

  return (
    <section className="red-juridico" aria-label="Parecer jurídico da matéria">
      <h2>Parecer jurídico</h2>
      <p className="red-aj" role="note">
        {AVISO_OPINATIVO}
      </p>

      {estado.fase === "carregando" && <p role="status">Carregando o parecer jurídico da matéria…</p>}
      {estado.fase === "erro" && (
        <p role="status" className="red-aviso">
          Não foi possível ver se já há pedido ou parecer jurídico sobre esta matéria. {estado.mensagem}
        </p>
      )}
      {estado.fase === "pronto" &&
        (linhas.length === 0 ? (
          <p>Nenhum parecer jurídico assinado nem pedido em aberto sobre esta matéria.</p>
        ) : (
          <ul className="red-lista">
            {linhas.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        ))}

      {recibo && (
        <p role="status" className="red-ok">
          {recibo}
        </p>
      )}

      {!confirmando ? (
        <button
          type="button"
          className="btn btn-contorno btn-mini"
          onClick={() => {
            setRecibo(null);
            setErro(null);
            setConfirmando(true);
          }}
        >
          Pedir parecer jurídico
        </button>
      ) : (
        <div className="red-confirmar">
          <label htmlFor="red-assunto">Assunto do pedido (opcional)</label>
          <input
            id="red-assunto"
            type="text"
            maxLength={300}
            value={assunto}
            placeholder={ASSUNTO_PADRAO_DA_MATERIA}
            onChange={(e) => setAssunto(e.target.value)}
          />
          <p className="red-aj">{CONFIRMACAO_PEDIDO_DO_RELATOR}</p>
          {erro && (
            <p role="alert" className="red-aviso">
              {erro}
            </p>
          )}
          <div className="red-acoes">
            <button type="button" className="btn btn-primaria btn-mini" onClick={confirmar} disabled={enviando}>
              {enviando ? "Registrando…" : "Confirmar o pedido"}
            </button>
            <button type="button" className="btn btn-fantasma btn-mini" onClick={() => setConfirmando(false)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
