"use client";

// A lista de "Meus protocolos" (formulários do cidadão, ADR-0015): o que a cidadã protocolou na Casa, em três
// grupos, cada item com estado, prazo e a resposta da Câmara quando houver. O pedido de e-SIC respondido ou
// indeferido oferece o recurso (LAI art. 15) ali mesmo; o backend decide se cabe (409 → mensagem). O protocolo
// INDEFERIDO (e-SIC ou LGPD) mostra a fundamentação da Casa — a lei exige que a recusa diga as razões — e um selo
// neutro: negar não é aprovar, e a cor de "aprovado" mentiria sobre o desfecho. O protocolo PRORROGADO mostra as duas
// datas e a justificativa da Câmara (LAI art. 11 §2º: o requerente é cientificado) — só aqui, no protocolo do dono. Os
// ANEXOS da resposta (a resposta a um pedido costuma ser um documento) aparecem sob a resposta, com o download pela rota
// do próprio requerente (o servidor devolve 404 a quem não é o dono). Os anexos que a PRÓPRIA cidadã juntou ao pedido
// ("Seus anexos", origem `requerente`) ficam numa lista à parte, e enquanto o servidor diz `podeAnexar` (10 minutos depois
// do protocolo, até 5 arquivos) o controle "Anexar ao pedido" deixa juntar mais.

import { useState } from "react";
import { DIREITOS_LGPD, LIMITES, TIPOS_MANIFESTACAO, rotuloEstado } from "@/lib/formularios-cidadao";
import { formatarData, formatarDataSimples } from "@/lib/formatar-data";
import { useEnvioCidadao } from "@/lib/use-envio-cidadao";
import type { Anexo, Complemento, EspecieDoPortal, MeusProtocolos, Prorrogacao, RecursoEsic, Resposta } from "@/lib/use-meus-protocolos";
import { ListaDeAnexos } from "../../anexos-do-atendimento";
import { AnexarAoProtocolo } from "./anexar-ao-protocolo";

const ABERTOS = new Set(["protocolado", "protocolada", "em_analise"]);
const RECORRIVEIS = new Set(["respondido", "indeferido"]);
const INDEFERIDOS = new Set(["indeferido", "indeferida"]);

function Prazo({ estado, dias }: { estado: string; dias: number | null }) {
  if (!ABERTOS.has(estado) || dias === null) return null;
  if (dias < 0) return <span className="mp-prazo mp-vencido">O prazo venceu há {-dias} dias</span>;
  return <span className="mp-prazo">{dias === 0 ? "Hoje é o último dia do prazo" : `${dias} dias para a resposta`}</span>;
}

// As datas do prazo são DIA CIVIL (AAAA-MM-DD): formatarDataSimples não passa por Date, então o dia não recua com o fuso.
// O instante em que foi prorrogado, esse sim, é um instante: formatarData, como o resto da tela.
function PrazoProrrogado({ prorrogacao }: { prorrogacao: Prorrogacao | null | undefined }) {
  if (!prorrogacao) return null;
  return (
    <div className="mp-prorrogacao">
      <p className="mp-prorrogacao-cab">
        Prazo prorrogado de {formatarDataSimples(prorrogacao.deData)} para {formatarDataSimples(prorrogacao.paraData)}, em{" "}
        {formatarData(prorrogacao.prorrogadoEm)}.
      </p>
      <p className="mp-prorrogacao-texto">
        <span className="mp-prorrogacao-rotulo">Justificativa da Câmara:</span> {prorrogacao.justificativa}
      </p>
    </div>
  );
}

function RespostaDaCasa({ resposta, indeferido }: { resposta: Resposta | null; indeferido: boolean }) {
  if (!resposta) return null;
  return (
    <div className="mp-resposta">
      <p className="mp-resposta-cab">
        {indeferido ? "Fundamentação do indeferimento" : "Resposta da Câmara"} · {formatarData(resposta.respondidaEm)}
      </p>
      <p>{resposta.corpo}</p>
    </div>
  );
}

// O que a Casa acrescentou DEPOIS de responder (ADR-0022), em ordem de chegada, logo abaixo da resposta. Só o texto e quando:
// quem escreveu nunca chega aqui. Não aparece nas rotas públicas por número de protocolo.
function ComplementosDaResposta({ complementos }: { complementos: Complemento[] | undefined }) {
  if (!complementos || complementos.length === 0) return null;
  return (
    <>
      {complementos.map((c) => (
        <div key={c.id} className="mp-resposta mp-complemento">
          <p className="mp-resposta-cab">Complemento da resposta · {formatarData(c.complementadoEm)}</p>
          <p>{c.corpo}</p>
        </div>
      ))}
    </>
  );
}

function Recurso({
  pedidoId,
  token,
  aoMudar,
  indeferido,
}: {
  pedidoId: string;
  token: string | null;
  aoMudar: () => void;
  indeferido: boolean;
}) {
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
        {indeferido ? "Recorrer do indeferimento" : "Recorrer da resposta"}
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
      <label htmlFor={`motivo-${pedidoId}`}>
        {indeferido ? "Por que você não concordou com o indeferimento?" : "Por que você não concordou com a resposta?"}
      </label>
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
  prorrogacao,
  anexos,
  complementos,
  rotaDoAnexo,
  token,
  anexavel,
  aoMudar,
  children,
}: {
  protocolo: string;
  titulo: string;
  estado: string;
  reciboEm: string;
  dias: number | null;
  resposta: Resposta | null;
  prorrogacao?: Prorrogacao | null;
  anexos?: Anexo[];
  complementos?: Complemento[];
  rotaDoAnexo: (anexoId: string) => string;
  token: string | null;
  /** O que o controle de anexar precisa saber: qual protocolo e se o servidor diz que ainda cabe. */
  anexavel: { especie: EspecieDoPortal; id: string; podeAnexar: boolean };
  aoMudar: () => void;
  children?: React.ReactNode;
}) {
  const doRequerente = (anexos ?? []).filter((a) => a.origem === "requerente");
  const daCasa = (anexos ?? []).filter((a) => a.origem !== "requerente");
  return (
    <li className="mp-item">
      <div className="mp-item-topo">
        <span className="mp-protocolo">{protocolo}</span>
        <span className={`chip ${ABERTOS.has(estado) ? "chip-aguarda" : INDEFERIDOS.has(estado) ? "chip-neutro" : "chip-aprovada"}`}>
          {rotuloEstado(estado)}
        </span>
      </div>
      <p className="mp-titulo">{titulo}</p>
      <p className="mp-meta">
        Recebido em {formatarData(reciboEm)} <Prazo estado={estado} dias={dias} />
      </p>
      <PrazoProrrogado prorrogacao={prorrogacao} />
      <RespostaDaCasa resposta={resposta} indeferido={INDEFERIDOS.has(estado)} />
      <ComplementosDaResposta complementos={complementos} />
      <ListaDeAnexos anexos={daCasa} rotaDe={rotaDoAnexo} token={token} Titulo="h3" titulo="Anexos da resposta" />
      <ListaDeAnexos anexos={doRequerente} rotaDe={rotaDoAnexo} token={token} Titulo="h3" titulo="Seus anexos" />
      <AnexarAoProtocolo especie={anexavel.especie} id={anexavel.id} podeAnexar={anexavel.podeAnexar} token={token} aoMudar={aoMudar} />
      {children}
    </li>
  );
}

/** O download do anexo, pela rota do REQUERENTE (so' o dono baixa; qualquer outro recebe 404). */
const rotaDoAnexo = (especie: "esic" | "ouvidoria" | "lgpd", id: string) => (anexoId: string) =>
  `/api/portal/meus-protocolos/${especie}/${encodeURIComponent(id)}/anexos/${encodeURIComponent(anexoId)}`;

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
                dias={p.diasRestantes} resposta={p.resposta} prorrogacao={p.prorrogacao} anexos={p.anexos}
                complementos={p.complementos}
                rotaDoAnexo={rotaDoAnexo("esic", p.id)} token={token}
                anexavel={{ especie: "esic", id: p.id, podeAnexar: p.podeAnexar === true }} aoMudar={aoMudar}>
                {p.recurso ? (
                  <RecursoInterposto recurso={p.recurso} />
                ) : (
                  RECORRIVEIS.has(p.estado) && (
                    <Recurso pedidoId={p.id} token={token} aoMudar={aoMudar} indeferido={INDEFERIDOS.has(p.estado)} />
                  )
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
                reciboEm={s.reciboEm} dias={s.diasRestantes} resposta={s.resposta} anexos={s.anexos}
                complementos={s.complementos}
                rotaDoAnexo={rotaDoAnexo("lgpd", s.id)} token={token}
                anexavel={{ especie: "lgpd", id: s.id, podeAnexar: s.podeAnexar === true }} aoMudar={aoMudar} />
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
                estado={m.estado} reciboEm={m.reciboEm} dias={m.diasRestantes} resposta={m.resposta}
                prorrogacao={m.prorrogacao} anexos={m.anexos} complementos={m.complementos}
                rotaDoAnexo={rotaDoAnexo("ouvidoria", m.id)} token={token}
                anexavel={{ especie: "ouvidoria", id: m.id, podeAnexar: m.podeAnexar === true }} aoMudar={aoMudar} />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
