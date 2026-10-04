"use client";

// Rota /atendimento/:tipo/:id (interno) — um protocolo do cidadão no balcão: o pedido, quem pediu (e-SIC e LGPD: nome e
// CPF mascarado; ouvidoria: só se é identificada ou anônima — Lei 13.460, art. 10, §7º), o prazo, o histórico e as
// ações que cabem no estado atual (o servidor diz quais: a tela não deduz regra). Depois da ação, o recibo e a volta à
// fila. Gate de papel via <GuardSecretaria> (a authz real é o backend).
//
// O texto do cidadão é renderizado como TEXTO (o React escapa; nunca dangerouslySetInnerHTML).

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  AVISO_IDENTIDADE_OUVIDORIA,
  ESPECIES,
  TETO_JUSTIFICATIVA,
  avisoDaProrrogacao,
  TETO_RESPOSTA,
  especieValida,
  faltaNoTexto,
  linhaDoEvento,
  linhaDoPrazo,
  recebidoEm,
  rotuloEstado,
  textoDoRecibo,
  tituloDoEvento,
  tituloDoItem,
  type Especie,
} from "@/lib/atendimento-vista";
import {
  anexar,
  arquivar,
  decidirRecurso,
  indeferir,
  prorrogar,
  responder,
  rotaDoAnexoNoBalcao,
  useDetalheAtendimento,
  type DetalheOut,
} from "@/lib/use-atendimento";
import type { ItemDeEnvio } from "@/lib/anexos-do-atendimento";
import { tamanhoLegivel } from "@/lib/comunicacao-vista";
import { ListaDeAnexos } from "@/app/anexos-do-atendimento";
import type { DetalheEsicOut, DetalheLgpdOut, DetalheOuvidoriaOut, PessoaOut } from "@/lib/contrato-atendimento.gen";
import { GuardSecretaria } from "../../../guard-secretaria";
import { TopoInterno } from "../../../topo";
import { SeloPrazo } from "../../fila-atendimento";
import { PainelDeEnvio } from "../../envio-de-anexos";
import { SeletorDeAnexos } from "../../seletor-de-anexos";
import "../../atendimento.css";

export default function PaginaProtocolo() {
  return (
    <GuardSecretaria>
      <Conteudo />
    </GuardSecretaria>
  );
}

type Acao = "responder" | "indeferir" | "decidir-recurso" | "prorrogar" | "arquivar";

function Conteudo() {
  const { token } = useAuth();
  const { tipo, id } = useParams<{ tipo: string; id: string }>();
  const especie = especieValida(tipo) ? tipo : null;
  const { estado, recarregar } = useDetalheAtendimento(token, especie, id ?? null);
  const [recibo, setRecibo] = useState<string | null>(null);
  // os anexos da resposta sobem DEPOIS do ato (a rota de anexo pede a resposta ja' gravada), um a um
  const [envio, setEnvio] = useState<ItemDeEnvio[]>([]);
  const voltar = comToken(`/atendimento${especie ? `?aba=${especie}` : ""}`, token);
  const podeAnexar = estado.fase === "pronto" && Boolean((estado.dado.acoes as { podeAnexar?: boolean }).podeAnexar);

  async function enviarAnexos(lista: ItemDeEnvio[], indices: number[]) {
    if (!especie || !id) return;
    for (const i of indices) {
      setEnvio((xs) => xs.map((x, j) => (j === i ? { ...x, fase: "enviando", mensagem: undefined } : x)));
      const r = await anexar(token, especie, id, lista[i].arquivo);
      setEnvio((xs) => xs.map((x, j) => (j === i ? (r.ok ? { ...x, fase: "ok" } : { ...x, fase: "erro", mensagem: r.mensagem }) : x)));
    }
    recarregar(); // a lista de anexos e o `pode-anexar` do detalhe passam a valer o que o servidor tem agora
  }

  async function concluir(texto: string, arquivos: File[]) {
    setRecibo(texto);
    recarregar();
    if (arquivos.length === 0) return;
    const lista: ItemDeEnvio[] = arquivos.map((arquivo) => ({ arquivo, fase: "esperando" }));
    setEnvio(lista);
    await enviarAnexos(lista, lista.map((_, i) => i));
  }

  return (
    <>
      <TopoInterno area="Atendimento" />
      <main className="envelope atd atd-detalhe">
        <Link className="atd-voltar" href={voltar}>← Atendimento ao cidadão</Link>
        {!especie && <p className="atd-erro" role="alert">Endereço inválido: esta fila não existe.</p>}
        {especie && estado.fase === "carregando" && <p role="status">Carregando o protocolo…</p>}
        {especie && estado.fase === "erro" && <p className="atd-erro" role="alert">{estado.mensagem}</p>}
        {recibo && (
          <div className="atd-recibo" role="status">
            <p>{recibo}</p>
            <Link className="btn btn-primaria" href={voltar}>Voltar à fila</Link>
          </div>
        )}
        <PainelDeEnvio itens={envio} podeTentarDeNovo={podeAnexar} aoTentarDeNovo={(i) => void enviarAnexos(envio, [i])} />
        {especie && estado.fase === "pronto" && (
          <Protocolo especie={especie} id={id} d={estado.dado} token={token} aoConcluir={concluir} />
        )}
      </main>
    </>
  );
}

function Pessoa({ rotulo, p }: { rotulo: string; p: PessoaOut | null }) {
  return (
    <p className="atd-pessoa">
      <span className="atd-rotulo">{rotulo}</span>{" "}
      {p ? <><b>{p.nome}</b> · CPF {p.cpfMascarado}</> : "não encontrado no cadastro"}
    </p>
  );
}

function Protocolo({ especie, id, d, token, aoConcluir }: {
  especie: Especie;
  id: string;
  d: DetalheOut;
  token: string | null;
  aoConcluir: (recibo: string, arquivos: File[]) => void;
}) {
  const info = ESPECIES.find((e) => e.especie === especie)!;
  const prazo = linhaDoPrazo(d);
  const esic = especie === "esic" ? (d as DetalheEsicOut) : null;
  const ouv = especie === "ouvidoria" ? (d as DetalheOuvidoriaOut) : null;
  const lgpd = especie === "lgpd" ? (d as DetalheLgpdOut) : null;
  const texto = esic?.descricao ?? ouv?.descricao ?? lgpd?.detalhe ?? null;

  return (
    <>
      <header className="atd-cabeca">
        <p className="atd-ref">
          <span className="atd-protocolo">{d.protocolo}</span>
          <SeloPrazo item={d} />
          <span className="atd-chip">{rotuloEstado(d.estado)}</span>
        </p>
        <h1>{tituloDoItem(especie, d as { assunto?: string; tipo?: string })}</h1>
        <p className="atd-sub">
          {info.titulo} · {recebidoEm(d.recebidoEm)}
          {prazo ? ` · ${prazo}` : ""}
        </p>
      </header>

      <section className="atd-bloco" aria-label="Quem pediu">
        {esic && <Pessoa rotulo="Requerente:" p={esic.requerente} />}
        {lgpd && <Pessoa rotulo="Titular dos dados:" p={lgpd.titular} />}
        {ouv && (
          <>
            <p className="atd-pessoa">
              <span className="atd-rotulo">Manifestação</span>{" "}
              <b>{ouv.identificacao === "anonima" ? "anônima" : "identificada"}</b>
            </p>
            <p className="atd-dica">{AVISO_IDENTIDADE_OUVIDORIA}</p>
          </>
        )}
      </section>

      <section className="atd-bloco" aria-labelledby="atd-pedido-titulo">
        <h2 id="atd-pedido-titulo">{especie === "ouvidoria" ? "O que a pessoa relatou" : "O que a pessoa pediu"}</h2>
        {texto ? <p className="atd-texto">{texto}</p> : <p className="atd-dica">A pessoa não escreveu detalhes além do tipo de pedido.</p>}
      </section>

      {esic?.recurso && (
        <section className="atd-bloco atd-recurso" aria-labelledby="atd-recurso-titulo">
          <h2 id="atd-recurso-titulo">
            Recurso {esic.recurso.protocolo}{" "}
            {esic.recurso.estado === "protocolado" ? <SeloPrazo item={{ aberto: true, diasRestantes: esic.recurso.diasRestantes }} /> : <span className="atd-chip">Decidido</span>}
          </h2>
          <p className="atd-sub">
            {recebidoEm(esic.recurso.recebidoEm)}
            {esic.recurso.prazoVigente ? ` · ${linhaDoPrazo(esic.recurso)}` : ""}
          </p>
          {esic.recurso.motivo && <p className="atd-texto">{esic.recurso.motivo}</p>}
        </section>
      )}

      <section className="atd-bloco" aria-labelledby="atd-historico-titulo">
        <h2 id="atd-historico-titulo">Histórico</h2>
        {d.historico.length === 0 ? (
          <p className="atd-dica">Nada registrado ainda além do pedido.</p>
        ) : (
          <ol className="atd-historico">
            {d.historico.map((e, i) => (
              <li key={`${e.tipo}-${e.em}-${i}`}>
                <p className="atd-evento-titulo">{tituloDoEvento(e)}</p>
                <p className="atd-linha">{linhaDoEvento(e)}</p>
                {e.texto && <p className="atd-texto">{e.texto}</p>}
              </li>
            ))}
          </ol>
        )}
      </section>

      <ListaDeAnexos anexos={d.anexos} rotaDe={(anexoId) => rotaDoAnexoNoBalcao(especie, id, anexoId)} token={token} />

      <Acoes especie={especie} d={d} token={token} aoConcluir={aoConcluir} />
    </>
  );
}

const FORM: Record<Acao, { botao: string; rotulo: string; enviar: string; enviando: string; oQue: string; teto: number; aviso: string }> = {
  responder: {
    botao: "Responder", rotulo: "Resposta ao cidadão", enviar: "Enviar a resposta", enviando: "Enviando…",
    oQue: "a resposta", teto: TETO_RESPOSTA,
    aviso: "A resposta é definitiva: depois de enviada, encerra o protocolo e não pode ser editada.",
  },
  indeferir: {
    botao: "Indeferir", rotulo: "Fundamentação do indeferimento", enviar: "Revisar o indeferimento", enviando: "Enviando…",
    oQue: "a fundamentação", teto: TETO_RESPOSTA,
    aviso: "O indeferimento é definitivo: encerra o protocolo, não pode ser desfeito, e a pessoa lê a fundamentação. A lei exige que a recusa diga as razões — escreva o motivo e, se houver, o dispositivo legal.",
  },
  "decidir-recurso": {
    botao: "Decidir o recurso", rotulo: "Decisão sobre o recurso", enviar: "Registrar a decisão", enviando: "Registrando…",
    oQue: "a decisão", teto: TETO_RESPOSTA, aviso: "A decisão é definitiva e encerra o recurso.",
  },
  prorrogar: {
    botao: "Prorrogar o prazo", rotulo: "Justificativa da prorrogação", enviar: "Prorrogar", enviando: "Prorrogando…",
    oQue: "a justificativa", teto: TETO_JUSTIFICATIVA,
    aviso: "A prorrogação só pode ser feita uma vez e exige justificativa.",
  },
  arquivar: {
    botao: "Arquivar sem resposta", rotulo: "Motivo do arquivamento", enviar: "Arquivar", enviando: "Arquivando…",
    oQue: "o motivo", teto: TETO_RESPOSTA,
    aviso: "Arquivar encerra a manifestação sem resposta de mérito — use quando ela não é da competência da Câmara ou não tem como ser apurada.",
  },
};

// o que acompanha-se de arquivo: a resposta, o indeferimento e a decisao do recurso (prorrogar e arquivar nao tem documento)
const COM_ANEXOS: Acao[] = ["responder", "indeferir", "decidir-recurso"];

function Acoes({ especie, d, token, aoConcluir }: {
  especie: Especie;
  d: DetalheOut;
  token: string | null;
  aoConcluir: (recibo: string, arquivos: File[]) => void;
}) {
  const [acao, setAcao] = useState<Acao | null>(null);
  const [texto, setTexto] = useState("");
  const [arquivos, setArquivos] = useState<File[]>([]);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  // o indeferimento é irreversível: um passo de confirmação mostra o texto que a pessoa vai ler antes de enviar
  const [confirmando, setConfirmando] = useState(false);

  const a = d.acoes as {
    podeResponder: boolean;
    podeIndeferir?: boolean;
    podeProrrogar?: boolean;
    podeArquivar?: boolean;
    recursoPendenteId?: string | null;
  };
  const cabem: Acao[] = [
    ...(a.podeResponder ? (["responder"] as const) : []),
    ...(a.podeIndeferir ? (["indeferir"] as const) : []),
    ...(a.recursoPendenteId ? (["decidir-recurso"] as const) : []),
    ...(a.podeProrrogar ? (["prorrogar"] as const) : []),
    ...(a.podeArquivar ? (["arquivar"] as const) : []),
  ];
  if (cabem.length === 0) {
    return <p className="atd-dica atd-encerrado">Este protocolo está encerrado: não há o que responder.</p>;
  }

  const dias = especie === "esic" ? 10 : 30;
  const f = acao ? FORM[acao] : null;
  // o aviso do prorrogar depende de a quem a justificativa é mostrada (ouvidoria anônima: a ninguém)
  const aviso =
    acao === "prorrogar" && especie !== "lgpd"
      ? avisoDaProrrogacao(especie, "identificacao" in d ? d.identificacao : undefined)
      : f?.aviso;
  const falta = f ? faltaNoTexto(texto, f.teto, f.oQue) : null;

  function abrir(x: Acao) {
    setAcao(x);
    setTexto("");
    setArquivos([]);
    setErro(null);
    setConfirmando(false);
  }

  async function enviar() {
    if (!acao) return;
    setEnviando(true);
    setErro(null);
    const t = texto.trim();
    const r =
      acao === "responder"
        ? await responder(token, especie, d.id, t).then((x) => (x.ok ? { ok: true as const, em: x.dado.respondidaEm } : x))
        : acao === "indeferir"
          ? await indeferir(token, especie as "esic" | "lgpd", d.id, t).then((x) => (x.ok ? { ok: true as const, em: x.dado.indeferidoEm } : x))
          : acao === "decidir-recurso"
            ? await decidirRecurso(token, a.recursoPendenteId!, t).then((x) => (x.ok ? { ok: true as const, em: x.dado.decididoEm } : x))
            : acao === "prorrogar"
              ? await prorrogar(token, especie as "esic" | "ouvidoria", d.id, t).then((x) => (x.ok ? { ok: true as const, em: x.dado.prorrogadoAte } : x))
              : await arquivar(token, d.id, t).then((x) => (x.ok ? { ok: true as const, em: x.dado.arquivadaEm } : x));
    setEnviando(false);
    if (r.ok) {
      setAcao(null);
      setConfirmando(false);
      aoConcluir(textoDoRecibo(acao, r.em, d.protocolo), COM_ANEXOS.includes(acao) ? arquivos : []);
    } else {
      setErro(r.mensagem);
      // o erro volta para o editor (com o texto intacto): quem escreveu a fundamentação não a perde antes de recarregar
      setConfirmando(false);
    }
  }

  return (
    <section className="atd-bloco atd-acoes-bloco" aria-label="O que fazer">
      {!acao && (
        <div className="atd-acoes">
          {cabem.map((x, i) => (
            <button key={x} type="button" className={i === 0 ? "btn btn-primaria" : "btn btn-contorno"} onClick={() => abrir(x)}>
              {x === "prorrogar" ? `${FORM.prorrogar.botao} (+${dias} dias)` : FORM[x].botao}
            </button>
          ))}
        </div>
      )}
      {acao && f && confirmando && (
        <div className="atd-form" role="group" aria-label="Confirmar o indeferimento">
          <p className="atd-aviso">
            Você vai indeferir o {d.protocolo}. Esta ação é definitiva e não pode ser desfeita. A pessoa vai ler esta fundamentação:
          </p>
          <p className="atd-texto">{texto.trim()}</p>
          {arquivos.length > 0 && (
            <p className="atd-aviso">
              Vão junto, como anexo da resposta: {arquivos.map((a) => `${a.name} (${tamanhoLegivel(a.size)})`).join("; ")}.
            </p>
          )}
          <div className="atd-acoes">
            <button type="button" className="btn btn-primaria" disabled={enviando} onClick={enviar}>
              {enviando ? f.enviando : "Confirmar o indeferimento"}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => setConfirmando(false)}>
              Voltar e editar
            </button>
          </div>
        </div>
      )}
      {acao && f && !confirmando && (
        <div className="atd-form" role="group" aria-label={f.botao}>
          <p className="atd-aviso">{aviso}</p>
          <div className="atd-campo">
            <label htmlFor="atd-texto">{f.rotulo}</label>
            <textarea id="atd-texto" value={texto} rows={acao === "prorrogar" ? 4 : 10} maxLength={f.teto + 200}
              onChange={(e) => setTexto(e.target.value)} />
          </div>
          {COM_ANEXOS.includes(acao) && <SeletorDeAnexos arquivos={arquivos} aoMudar={setArquivos} desabilitado={enviando} />}
          {falta && <p className="atd-falta">{falta}</p>}
          {erro && <p className="atd-erro" role="alert">{erro}</p>}
          <div className="atd-acoes">
            <button type="button" className="btn btn-primaria" disabled={enviando || falta !== null}
              onClick={acao === "indeferir" ? () => setConfirmando(true) : enviar}>
              {enviando ? f.enviando : acao === "prorrogar" ? `${f.enviar} por mais ${dias} dias` : f.enviar}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => setAcao(null)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
