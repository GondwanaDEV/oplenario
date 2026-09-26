"use client";

// ReceberCarga — o "canhoto" da carga (fatia 2b, pedido do stakeholder: "toda movimentação do documento
// assinada por quem recebe"). Aparece onde a matéria está parada esperando alguém receber: no painel de atos
// da ficha e em cada linha da fila /recebimentos. Compartilhado aqui, no nível (interno), pelo mesmo motivo
// de <GuardSecretaria>: duas rotas usam.
//
// DOIS TOQUES, como a assinatura do parecer: o primeiro diz o que vai ser assinado (qual carga, de quando);
// o segundo assina. Um clique solto num botão de lista não pode virar recibo assinado.
//
// O que se assina é a MOVIMENTAÇÃO que a pessoa está vendo (`movimentacaoId`): se a matéria andou depois que a
// tela carregou, o servidor recusa (409) e a tela pede para conferir — `onMudou` recarrega quem nos mostrou.

import { useState } from "react";
import { useReceber } from "@/lib/use-receber";
import type { VistaCarga } from "@/lib/recebimento-vista";
import "./receber-carga.css";

export function ReceberCarga({
  proposicaoId,
  carga,
  token = null,
  onMudou,
  titulo = true,
}: {
  proposicaoId: string;
  carga: VistaCarga;
  token?: string | null;
  /** Chamado depois de receber (ou quando o servidor diz que a situação mudou): quem mostrou a carga recarrega. */
  onMudou?: () => void;
  /** Na fila, a linha já diz qual é a matéria — o cabeçalho do canhoto sobra. */
  titulo?: boolean;
}) {
  const { receber, enviando } = useReceber(token);
  const [confirmando, setConfirmando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function assinar() {
    setErro(null);
    const r = await receber(proposicaoId, carga.movimentacaoId);
    if (r.ok) {
      setConfirmando(false);
      onMudou?.();
      return;
    }
    setErro(r.erro);
    setConfirmando(false);
    if (r.recarregar) onMudou?.();
  }

  return (
    <div className="canhoto">
      {titulo && <p className="canhoto-rotulo">Aguardando recebimento</p>}
      <p className="canhoto-linha">
        Chegou a <b>{carga.estadoNome}</b> em {carga.chegouEm}
        {carga.espera && <span className="canhoto-espera"> · {carga.espera}</span>}
      </p>
      {titulo && (
        <p className="canhoto-nota">Enquanto ninguém receber e assinar, nenhum ato de tramitação é aceito.</p>
      )}
      {carga.restrito && (
        <p className="canhoto-nota">O rito desta Casa define quem pode receber aqui — o recebimento pode ser recusado.</p>
      )}

      {confirmando ? (
        <div className="canhoto-confirma" role="group" aria-label="Confirmar recebimento">
          <p className="canhoto-declara">
            Você declara que recebeu esta matéria em <b>{carga.estadoNome}</b>. O recibo leva o seu nome e fica
            assinado no histórico — não se desfaz.
          </p>
          <div className="canhoto-botoes">
            <button type="button" className="btn btn-primaria" disabled={enviando} onClick={assinar}>
              {enviando ? "Assinando…" : "Assinar recebimento"}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => setConfirmando(false)}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <div className="canhoto-botoes">
          <button
            type="button"
            className="btn btn-contorno"
            onClick={() => {
              setErro(null);
              setConfirmando(true);
            }}
          >
            Receber e assinar
          </button>
        </div>
      )}

      {erro && (
        <p role="alert" className="campo-erro">
          {erro}
        </p>
      )}
    </div>
  );
}
