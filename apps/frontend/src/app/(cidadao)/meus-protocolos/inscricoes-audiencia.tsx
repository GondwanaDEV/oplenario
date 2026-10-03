"use client";

// "Inscrições em audiências" em /meus-protocolos (ADR-0021 A2): as inscrições da cidadã para falar em audiência
// pública, com o protocolo AUD-…, a posição na fila e o estado em palavras. Enquanto `inscrita`, ela pode desistir
// (com confirmação — a desistência é terminal). Fonte PRÓPRIA (GET /portal/minhas-inscricoes): se ela falhar, só esta
// seção diz que não carregou; os protocolos de e-SIC, LGPD e ouvidoria seguem de pé, e vice-versa.

import { useState } from "react";
import { rotuloEstadoInscricao, ROTAS_AUDIENCIA, type MinhaInscricaoOut } from "@/lib/contrato-audiencia";
import { quandoPorExtenso } from "@/lib/audiencia-vista";
import { formatarData } from "@/lib/formatar-data";
import { useEnvioCidadao } from "@/lib/use-envio-cidadao";
import { useMinhasInscricoes } from "@/lib/use-audiencia";

const CHIP: Record<string, string> = {
  inscrita: "chip-aguarda",
  falando: "chip-aguarda",
  falou: "chip-aprovada",
};

function Desistir({ inscricao, token, aoMudar }: { inscricao: MinhaInscricaoOut; token: string | null; aoMudar: () => void }) {
  const { enviar, estado, erro } = useEnvioCidadao(token);
  const [confirmando, setConfirmando] = useState(false);
  if (!confirmando) {
    return (
      <button type="button" className="btn btn-contorno btn-mini" onClick={() => setConfirmando(true)}>
        Desistir da fala
      </button>
    );
  }
  return (
    <div className="mp-recurso">
      <p className="mp-recurso-ok">Desistir tira você da fila desta audiência. Não dá para desfazer — seria preciso se inscrever de novo.</p>
      {erro && (
        <p className="form-erro" role="alert">
          {erro}
        </p>
      )}
      <div className="mp-recurso-acoes">
        <button
          type="button"
          className="btn btn-primaria btn-mini"
          disabled={estado === "enviando"}
          onClick={async () => {
            try {
              await enviar(ROTAS_AUDIENCIA.desistencia(inscricao.id), {}, "POST", {
                409: "Esta inscrição não está mais na fila: a fala já foi chamada ou encerrada.",
              });
              aoMudar();
            } catch {
              // mensagem em `erro`
            }
          }}
        >
          {estado === "enviando" ? "Enviando…" : "Confirmar desistência"}
        </button>
        <button type="button" className="btn btn-contorno btn-mini" onClick={() => setConfirmando(false)}>
          Manter inscrição
        </button>
      </div>
    </div>
  );
}

export function InscricoesAudiencia({ token }: { token: string | null }) {
  const { estado, recarregar } = useMinhasInscricoes(token);
  return (
    <section className="mp-grupo" aria-labelledby="mp-aud">
      <h2 id="mp-aud">Inscrições em audiências</h2>
      {estado.fase === "carregando" && (
        <p className="ac-nota" aria-live="polite">
          Carregando…
        </p>
      )}
      {estado.fase === "erro" && (
        <p className="ac-nota ac-erro" role="alert">
          Não foi possível carregar as suas inscrições em audiências agora. Tente novamente em instantes.
        </p>
      )}
      {estado.fase === "pronto" &&
        (estado.dado.inscricoes.length === 0 ? (
          <p className="ac-nota">Nenhuma inscrição para falar em audiência pública.</p>
        ) : (
          <ul className="mp-lista">
            {estado.dado.inscricoes.map((i) => {
              const quando = quandoPorExtenso(i.agendadaPara);
              return (
                <li className="mp-item" key={i.id}>
                  <div className="mp-item-topo">
                    <span className="mp-protocolo">{i.protocolo}</span>
                    <span className={`chip ${CHIP[i.estado] ?? "chip-neutro"}`}>{rotuloEstadoInscricao(i.estado)}</span>
                  </div>
                  <p className="mp-titulo">{i.tema}</p>
                  <p className="mp-meta">
                    <span>{i.comissaoNome}</span>
                    <span>{quando ?? "data a definir"}</span>
                    {i.estado === "inscrita" && <span className="mp-prazo">{i.ordem}ª na fila</span>}
                    <span>Inscrição em {formatarData(i.reciboEm)}</span>
                  </p>
                  {i.estado === "inscrita" && <Desistir inscricao={i} token={token} aoMudar={recarregar} />}
                </li>
              );
            })}
          </ul>
        ))}
    </section>
  );
}
