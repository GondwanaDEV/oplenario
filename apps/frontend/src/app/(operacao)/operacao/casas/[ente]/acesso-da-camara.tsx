"use client";

// ADR-0018 (fatia 1) — o acesso da câmara no console: suspender (motivo fechado + justificativa), a fila "aguardando 2º
// operador" (aprovar / recusar; quem pediu só retira), reativar e iniciar o encerramento. A sessão do console já exige a
// chave física (ADR-0016): cada operador aqui passou por ela. Quem decide de verdade é o servidor — two-person rule no
// banco, sessão ao vivo que adia a suspensão, o incidente que volta sozinho sem a 2ª aprovação em 24 h —; esta tela
// explica e pede, e mostra a recusa dele com o porquê.

import { useState } from "react";
import {
  type Casa,
  type Pedido,
  MOTIVOS_SUSPENSAO,
  ORIGENS_ENCERRAMENTO,
  aprovarPedido,
  conferirJustificativa,
  iniciarEncerramento,
  pedirSuspensao,
  reativarCasa,
  recusarPedido,
  rotuloMotivo,
  useOperador,
} from "@/lib/use-operacao";
import { dataHora } from "../../quando";

type Aviso = { tipo: "ok" | "erro"; texto: string } | null;

export function AcessoDaCamara({ casa, pedidoAberto, token, aoMudar }: {
  casa: Casa;
  pedidoAberto: Pedido | null;
  token: string | null;
  aoMudar: () => void;
}) {
  const [aviso, setAviso] = useState<Aviso>(null);
  if (casa.estado !== "ativo" && casa.estado !== "suspenso") return null;
  const suspensa = casa.estado === "suspenso";
  const emEncerramento = casa.restricao?.motivo === "encerramento_em_curso";
  const livre = !pedidoAberto && !casa.suspensaoAgendada;

  const concluir = (texto: string) => {
    setAviso({ tipo: "ok", texto });
    aoMudar();
  };

  return (
    <section className="op-secao" aria-labelledby="titulo-acesso">
      <h2 id="titulo-acesso">Acesso da câmara</h2>
      <p className="aj">
        Suspender restringe a câmara sem tirá-la do ar: o portal continua publicado, os pedidos do cidadão (e-SIC,
        ouvidoria, LGPD) continuam chegando e os servidores continuam respondendo a eles. A operação legislativa para.
        Um operador pede e outro aprova.
      </p>
      {aviso && (
        <p className={`op-aviso${aviso.tipo === "erro" ? " erro" : ""}`} role={aviso.tipo === "erro" ? "alert" : "status"}>
          {aviso.texto}
        </p>
      )}

      <div className="op-cartao">
        <div className="op-cartao-top">
          <div className="estado">
            {suspensa ? (
              <>
                <b>Acesso restrito desde {casa.restricao ? dataHora(casa.restricao.desde) : "—"}</b>
                <span>Motivo: {casa.restricao ? rotuloMotivo(casa.restricao.motivo) : "—"}</span>
              </>
            ) : casa.suspensaoAgendada ? (
              <>
                <b>Suspensão aprovada, esperando a sessão em curso</b>
                <span>Entra quando a sessão plenária encerrar — nunca no meio de uma votação.</span>
              </>
            ) : (
              <>
                <b>Acesso pleno</b>
                <span>A câmara opera normalmente.</span>
              </>
            )}
          </div>
        </div>
        <div className="op-cartao-corpo">
          {(suspensa || casa.suspensaoAgendada) && (
            <FormJustificativa
              rotulo={suspensa ? "Por que reativar" : "Por que cancelar a suspensão agendada"}
              dica={suspensa
                ? "Um operador basta (ex.: pagamento regularizado). Fica na atuação selada."
                : "A câmara segue ativa e a suspensão não entra ao fim da sessão."}
              botao={suspensa ? "Reativar câmara" : "Cancelar suspensão agendada"}
              enviar={(j) => reativarCasa(casa.enteId, j, token)}
              aoConcluir={() => concluir(suspensa ? "Câmara reativada." : "Suspensão agendada cancelada.")}
              aoFalhar={(m) => setAviso({ tipo: "erro", texto: m })}
            />
          )}
          {!suspensa && livre && (
            <FormSuspender casa={casa} token={token} aoConcluir={concluir} aoFalhar={(m) => setAviso({ tipo: "erro", texto: m })} />
          )}
        </div>
      </div>

      {pedidoAberto && (
        <PedidoAberto pedido={pedidoAberto} token={token} aoConcluir={concluir}
          aoFalhar={(m) => setAviso({ tipo: "erro", texto: m })} />
      )}

      {livre && !emEncerramento && (
        <details className="op-cartao op-encerrar">
          <summary>Iniciar encerramento</summary>
          <div className="op-cartao-corpo">
            <p className="aj">
              O fim do contrato. Dois operadores aprovam e a câmara fica com acesso restrito até a entrega da exportação
              completa — a exportação e a janela de guarda são a próxima etapa, ainda não disponível aqui.
            </p>
            <FormEncerrar casa={casa} token={token} aoConcluir={concluir} aoFalhar={(m) => setAviso({ tipo: "erro", texto: m })} />
          </div>
        </details>
      )}
    </section>
  );
}

function FormSuspender({ casa, token, aoConcluir, aoFalhar }: {
  casa: Casa; token: string | null; aoConcluir: (t: string) => void; aoFalhar: (m: string) => void;
}) {
  const [motivo, setMotivo] = useState<string>("");
  const [justificativa, setJustificativa] = useState("");
  const [erros, setErros] = useState<{ motivo?: string; justificativa?: string }>({});
  const [ocupado, setOcupado] = useState(false);

  async function enviar(e: React.FormEvent) {
    e.preventDefault();
    const ej = conferirJustificativa(justificativa);
    const novos = { motivo: motivo ? undefined : "Escolha o motivo.", justificativa: ej ?? undefined };
    setErros(novos);
    if (novos.motivo || novos.justificativa || ocupado) return;
    setOcupado(true);
    const r = await pedirSuspensao(casa.enteId, motivo, justificativa, token);
    setOcupado(false);
    if (!r.ok) return aoFalhar(r.mensagem);
    setJustificativa("");
    aoConcluir(r.dados.casa.estado === "suspenso"
      ? "Câmara com acesso restrito agora. Outro operador precisa confirmar em até 24 h; sem isso, ela volta a ativa."
      : "Pedido registrado. Outro operador precisa aprovar para a suspensão valer.");
  }

  return (
    <form className="op-form op-form-curto" onSubmit={enviar} noValidate>
      <div className="op-campo">
        <label htmlFor="motivo-suspensao">Motivo</label>
        <select id="motivo-suspensao" value={motivo} onChange={(e) => setMotivo(e.target.value)}
          aria-invalid={!!erros.motivo} aria-describedby="motivo-suspensao-dica">
          <option value="">Escolha…</option>
          {MOTIVOS_SUSPENSAO.map((m) => <option key={m.valor} value={m.valor}>{m.rotulo}</option>)}
        </select>
        <span className="dica" id="motivo-suspensao-dica">
          {motivo === "incidente_de_seguranca"
            ? "Incidente de segurança restringe agora, com você só; outro operador confirma em até 24 h."
            : "Ordem judicial corta na hora; os outros motivos esperam a sessão plenária em curso encerrar."}
        </span>
        {erros.motivo && <span className="erro">{erros.motivo}</span>}
      </div>
      <div className="op-campo">
        <label htmlFor="justificativa-suspensao">Justificativa</label>
        <textarea id="justificativa-suspensao" rows={3} value={justificativa} onChange={(e) => setJustificativa(e.target.value)}
          aria-invalid={!!erros.justificativa} />
        <span className="dica">Fica na atuação selada; a câmara vê o motivo, o cidadão só vê “acesso restrito”.</span>
        {erros.justificativa && <span className="erro">{erros.justificativa}</span>}
      </div>
      <div className="op-acoes">
        <button className="btn btn-contorno" type="submit" disabled={ocupado} aria-busy={ocupado}>
          {ocupado ? "Enviando…" : motivo === "incidente_de_seguranca" ? "Restringir agora" : "Pedir suspensão"}
        </button>
      </div>
    </form>
  );
}

function FormEncerrar({ casa, token, aoConcluir, aoFalhar }: {
  casa: Casa; token: string | null; aoConcluir: (t: string) => void; aoFalhar: (m: string) => void;
}) {
  const [origem, setOrigem] = useState<string>(ORIGENS_ENCERRAMENTO[0].valor);
  const [justificativa, setJustificativa] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);

  async function enviar(e: React.FormEvent) {
    e.preventDefault();
    const ej = conferirJustificativa(justificativa);
    setErro(ej);
    if (ej || ocupado) return;
    setOcupado(true);
    const r = await iniciarEncerramento(casa.enteId, origem, justificativa, token);
    setOcupado(false);
    if (!r.ok) return aoFalhar(r.mensagem);
    aoConcluir("Pedido de encerramento registrado. Outro operador precisa aprovar.");
  }

  return (
    <form className="op-form op-form-curto" onSubmit={enviar} noValidate>
      <div className="op-campo">
        <label htmlFor="origem-encerramento">Origem</label>
        <select id="origem-encerramento" value={origem} onChange={(e) => setOrigem(e.target.value)}>
          {ORIGENS_ENCERRAMENTO.map((o) => <option key={o.valor} value={o.valor}>{o.rotulo}</option>)}
        </select>
      </div>
      <div className="op-campo">
        <label htmlFor="justificativa-encerramento">Justificativa do encerramento</label>
        <textarea id="justificativa-encerramento" rows={3} value={justificativa}
          onChange={(e) => setJustificativa(e.target.value)} aria-invalid={!!erro} />
        {erro && <span className="erro">{erro}</span>}
      </div>
      <div className="op-acoes">
        <button className="btn btn-contorno" type="submit" disabled={ocupado} aria-busy={ocupado}>
          {ocupado ? "Enviando…" : "Pedir encerramento"}
        </button>
      </div>
    </form>
  );
}

function FormJustificativa({ rotulo, dica, botao, enviar, aoConcluir, aoFalhar }: {
  rotulo: string; dica: string; botao: string;
  enviar: (j: string) => Promise<{ ok: true } | { ok: false; mensagem: string }>;
  aoConcluir: () => void; aoFalhar: (m: string) => void;
}) {
  const [justificativa, setJustificativa] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);

  async function submeter(e: React.FormEvent) {
    e.preventDefault();
    const ej = conferirJustificativa(justificativa);
    setErro(ej);
    if (ej || ocupado) return;
    setOcupado(true);
    const r = await enviar(justificativa);
    setOcupado(false);
    if (!r.ok) return aoFalhar(r.mensagem);
    setJustificativa("");
    aoConcluir();
  }

  return (
    <form className="op-form op-form-curto" onSubmit={submeter} noValidate>
      <div className="op-campo">
        <label htmlFor="justificativa-reativar">{rotulo}</label>
        <textarea id="justificativa-reativar" rows={2} value={justificativa} onChange={(e) => setJustificativa(e.target.value)}
          aria-invalid={!!erro} />
        <span className="dica">{dica}</span>
        {erro && <span className="erro">{erro}</span>}
      </div>
      <div className="op-acoes">
        <button className="btn btn-contorno" type="submit" disabled={ocupado} aria-busy={ocupado}>
          {ocupado ? "Enviando…" : botao}
        </button>
      </div>
    </form>
  );
}

/** A fila "aguardando 2º operador" desta câmara. Quem pediu não aprova — só retira; o servidor confere de novo. */
function PedidoAberto({ pedido, token, aoConcluir, aoFalhar }: {
  pedido: Pedido; token: string | null; aoConcluir: (t: string) => void; aoFalhar: (m: string) => void;
}) {
  const { operador } = useOperador(token);
  const [ocupado, setOcupado] = useState<"aprovar" | "recusar" | null>(null);
  const [justificativa, setJustificativa] = useState("");
  const meu = operador?.id === pedido.pedidoPorId;
  const encerrar = pedido.acao === "encerrar";

  async function decidir(tipo: "aprovar" | "recusar") {
    if (ocupado) return;
    setOcupado(tipo);
    const r = tipo === "aprovar" ? await aprovarPedido(pedido.id, token) : await recusarPedido(pedido.id, justificativa, token);
    setOcupado(null);
    if (!r.ok) return aoFalhar(r.mensagem);
    if (tipo === "recusar") return aoConcluir(meu ? "Pedido retirado." : "Pedido recusado.");
    aoConcluir(r.dados.efeito === "agendado"
      ? "Aprovado. Há sessão plenária em curso: a suspensão entra quando ela encerrar."
      : "Aprovado. A câmara está com acesso restrito.");
  }

  return (
    <div className="op-cartao op-pedido" role="group" aria-labelledby="titulo-pedido">
      <div className="op-cartao-top">
        <div className="estado">
          <b id="titulo-pedido">Aguardando o 2º operador</b>
          <span>
            {encerrar ? "Encerramento" : "Suspensão"} · {rotuloMotivo(pedido.motivo)} · pedido por {pedido.pedidoPor ?? "—"}
            {" "}em {dataHora(pedido.pedidoEm)}
          </span>
        </div>
      </div>
      <div className="op-cartao-corpo">
        <p className="op-just">“{pedido.justificativa}”</p>
        {pedido.confirmarAte && (
          <p className="aj">
            A câmara já está com acesso restrito (incidente de segurança). Sem a 2ª aprovação até{" "}
            {dataHora(pedido.confirmarAte)}, ela volta a ativa sozinha.
          </p>
        )}
        {meu ? (
          <div className="op-acoes">
            <p className="aj">Você pediu: outro operador precisa aprovar. Você pode retirar o pedido.</p>
            <button className="btn btn-contorno" type="button" onClick={() => decidir("recusar")} disabled={!!ocupado}
              aria-busy={ocupado === "recusar"}>
              {ocupado === "recusar" ? "Retirando…" : "Retirar pedido"}
            </button>
          </div>
        ) : (
          <>
            <div className="op-campo">
              <label htmlFor="justificativa-decisao">Observação (opcional)</label>
              <textarea id="justificativa-decisao" rows={2} value={justificativa} onChange={(e) => setJustificativa(e.target.value)} />
            </div>
            <div className="op-acoes">
              <button className="btn btn-primaria" type="button" onClick={() => decidir("aprovar")} disabled={!!ocupado}
                aria-busy={ocupado === "aprovar"}>
                {ocupado === "aprovar" ? "Aprovando…" : encerrar ? "Aprovar encerramento" : "Aprovar suspensão"}
              </button>
              <button className="btn btn-contorno" type="button" onClick={() => decidir("recusar")} disabled={!!ocupado}
                aria-busy={ocupado === "recusar"}>
                {ocupado === "recusar" ? "Recusando…" : "Recusar"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
