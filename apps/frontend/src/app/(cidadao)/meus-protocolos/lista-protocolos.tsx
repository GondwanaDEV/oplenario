"use client";

// A lista de "Meus protocolos" (formulários do cidadão, ADR-0015): o que a cidadã protocolou na Casa, em três
// grupos, cada item com estado, prazo e a resposta da Câmara quando houver. O pedido de e-SIC respondido ou
// indeferido oferece o recurso (LAI art. 15) ali mesmo; o backend decide se cabe (409 → mensagem).

import { useState } from "react";
import { DIREITOS_LGPD, LIMITES, TIPOS_MANIFESTACAO, rotuloEstado } from "@/lib/formularios-cidadao";
import { formatarData } from "@/lib/formatar-data";
import { useEnvioCidadao } from "@/lib/use-envio-cidadao";
import type { MeusProtocolos, RecursoEsic, Resposta } from "@/lib/use-meus-protocolos";

const ABERTOS = new Set(["protocolado", "protocolada", "em_analise"]);
const RECORRIVEIS = new Set(["respondido", "indeferido"]);

function Prazo({ estado, dias }: { estado: string; dias: number | null }) {
  if (!ABERTOS.has(estado) || dias === null) return null;
  if (dias < 0) return <span className="mp-prazo mp-vencido">O prazo venceu há {-dias} dias</span>;
  return <span className="mp-prazo">{dias === 0 ? "Hoje é o último dia do prazo" : `${dias} dias para a resposta`}</span>;
}

function RespostaDaCasa({ resposta }: { resposta: Resposta | null }) {
  if (!resposta) return null;
  return (
    <div className="mp-resposta">
      <p className="mp-resposta-cab">Resposta da Câmara · {formatarData(resposta.respondidaEm)}</p>
      <p>{resposta.corpo}</p>
    </div>
  );
}

function Recurso({ pedidoId, token, aoMudar }: { pedidoId: string; token: string | null; aoMudar: () => void }) {
  const { enviar, estado, erro } = useEnvioCidadao(token);
  const [aberto, setAberto] = useState(false);
  const [motivo, setMotivo] = useState("");
  const [protocolo, setProtocolo] = useState<string | null>(null);

  if (protocolo) {
    return (
      <p className="mp-recurso-ok" role="status">
        Recurso registrado: <span className="mp-protocolo">{protocolo}</span>. Ele vai a uma nova instância de revisão.
      </p>
    );
  }
  if (!aberto) {
    return (
      <button type="button" className="btn btn-contorno btn-mini" onClick={() => setAberto(true)}>
        Recorrer da resposta
      </button>
    );
  }
  const limpo = motivo.trim();
  return (
    <form
      className="mp-recurso"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!limpo) return;
        try {
          const r = await enviar<{ protocolo: string }>(
            `/portal/esic/pedidos/${encodeURIComponent(pedidoId)}/recursos`,
            { motivo: limpo },
            "POST",
            { 409: "Este pedido não pode receber recurso agora." },
          );
          setProtocolo(r.protocolo);
          aoMudar();
        } catch {
          // mensagem em `erro`
        }
      }}
    >
      <label htmlFor={`motivo-${pedidoId}`}>Por que você não concordou com a resposta?</label>
      <textarea id={`motivo-${pedidoId}`} value={motivo} maxLength={LIMITES.motivo} onChange={(e) => setMotivo(e.target.value)} />
      {erro && (
        <p className="form-erro" role="alert">
          {erro}
        </p>
      )}
      <div className="mp-recurso-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={!limpo || estado === "enviando"}>
          {estado === "enviando" ? "Enviando…" : "Enviar recurso"}
        </button>
        <button type="button" className="btn btn-contorno btn-mini" onClick={() => setAberto(false)}>
          Cancelar
        </button>
      </div>
    </form>
  );
}

// V1: um recurso por pedido — já interposto, a tela mostra o recurso (e a decisão) no lugar do botão.
function RecursoInterposto({ recurso }: { recurso: RecursoEsic }) {
  return (
    <div className="mp-recurso-ok">
      <p>
        Recurso <span className="mp-protocolo">{recurso.protocolo}</span> ·{" "}
        {recurso.estado === "decidido" ? "Decidido" : "Em revisão"} (enviado em {formatarData(recurso.reciboEm)})
      </p>
      {recurso.resposta && (
        <div className="mp-resposta">
          <p className="mp-resposta-cab">Decisão do recurso · {formatarData(recurso.resposta.respondidaEm)}</p>
          <p>{recurso.resposta.corpo}</p>
        </div>
      )}
    </div>
  );
}

function Item({
  protocolo,
  titulo,
  estado,
  reciboEm,
  dias,
  resposta,
  children,
}: {
  protocolo: string;
  titulo: string;
  estado: string;
  reciboEm: string;
  dias: number | null;
  resposta: Resposta | null;
  children?: React.ReactNode;
}) {
  return (
    <li className="mp-item">
      <div className="mp-item-topo">
        <span className="mp-protocolo">{protocolo}</span>
        <span className={`chip ${ABERTOS.has(estado) ? "chip-aguarda" : "chip-aprovada"}`}>{rotuloEstado(estado)}</span>
      </div>
      <p className="mp-titulo">{titulo}</p>
      <p className="mp-meta">
        Recebido em {formatarData(reciboEm)} <Prazo estado={estado} dias={dias} />
      </p>
      <RespostaDaCasa resposta={resposta} />
      {children}
    </li>
  );
}

const rotuloDe = (lista: { valor: string; rotulo: string }[], v: string) => lista.find((o) => o.valor === v)?.rotulo ?? v;

export function ListaProtocolos({
  dados,
  token,
  aoMudar,
}: {
  dados: MeusProtocolos;
  token: string | null;
  aoMudar: () => void;
}) {
  return (
    <>
      <section className="mp-grupo" aria-labelledby="mp-esic">
        <h2 id="mp-esic">Acesso à informação (e-SIC)</h2>
        {dados.pedidosEsic.length === 0 ? (
          <p className="ac-nota">Nenhum pedido de informação ainda.</p>
        ) : (
          <ul className="mp-lista">
            {dados.pedidosEsic.map((p) => (
              <Item key={p.id} protocolo={p.protocolo} titulo={p.assunto} estado={p.estado} reciboEm={p.reciboEm}
                dias={p.diasRestantes} resposta={p.resposta}>
                {p.recurso ? (
                  <RecursoInterposto recurso={p.recurso} />
                ) : (
                  RECORRIVEIS.has(p.estado) && <Recurso pedidoId={p.id} token={token} aoMudar={aoMudar} />
                )}
              </Item>
            ))}
          </ul>
        )}
      </section>

      <section className="mp-grupo" aria-labelledby="mp-lgpd">
        <h2 id="mp-lgpd">Meus dados (LGPD)</h2>
        {dados.solicitacoesLgpd.length === 0 ? (
          <p className="ac-nota">Nenhum pedido sobre os seus dados ainda.</p>
        ) : (
          <ul className="mp-lista">
            {dados.solicitacoesLgpd.map((s) => (
              <Item key={s.id} protocolo={s.protocolo} titulo={rotuloDe(DIREITOS_LGPD, s.tipo)} estado={s.estado}
                reciboEm={s.reciboEm} dias={s.diasRestantes} resposta={s.resposta} />
            ))}
          </ul>
        )}
      </section>

      <section className="mp-grupo" aria-labelledby="mp-ouv">
        <h2 id="mp-ouv">Ouvidoria</h2>
        {dados.manifestacoes.length === 0 ? (
          <p className="ac-nota">
            Nenhuma manifestação identificada. As anônimas não aparecem aqui: acompanhe pelo número do protocolo, na
            página da Ouvidoria.
          </p>
        ) : (
          <ul className="mp-lista">
            {dados.manifestacoes.map((m) => (
              <Item key={m.id} protocolo={m.protocolo} titulo={`${rotuloDe(TIPOS_MANIFESTACAO, m.tipo)} · ${m.assunto}`}
                estado={m.estado} reciboEm={m.reciboEm} dias={m.diasRestantes} resposta={m.resposta} />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
