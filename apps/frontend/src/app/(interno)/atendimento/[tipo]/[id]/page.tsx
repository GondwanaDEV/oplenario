"use client";

// Rota /atendimento/:tipo/:id (interno) — um protocolo do cidadão no balcão: o pedido, quem pediu (e-SIC e LGPD: nome e
// CPF mascarado; ouvidoria: só se é identificada ou anônima — Lei 13.460, art. 10, §7º), o prazo, o histórico e as
// ações que cabem no estado atual (o servidor diz quais: a tela não deduz regra). Depois da ação, o recibo e a volta à
// fila. Gate de papel via <GuardSecretaria> (a authz real é o backend).
//
// O texto do cidadão é renderizado como TEXTO (o React escapa; nunca dangerouslySetInnerHTML).

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
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
  tituloQueRecebeOFoco,
  type Especie,
  type PassoDoIndeferimento,
} from "@/lib/atendimento-vista";
import {
  anexar,
  arquivar,
  decidirRecurso,
  indeferir,
  prorrogar,
  responder,
  retirarAnexo,
  rotaDoAnexoNoBalcao,
  useDetalheAtendimento,
  type DetalheOut,
} from "@/lib/use-atendimento";
import { dicaDosAnexosDaResposta } from "@/lib/anexos-do-atendimento";
import { useEnvioDeAnexos } from "@/lib/use-envio-de-anexos";
import { tamanhoLegivel } from "@/lib/comunicacao-vista";
import { ListaDeAnexos } from "@/app/anexos-do-atendimento";
import type { DetalheEsicOut, DetalheLgpdOut, DetalheOuvidoriaOut, PessoaOut } from "@/lib/contrato-atendimento.gen";
import { GuardSecretaria } from "../../../guard-secretaria";
import { TopoInterno } from "../../../topo";
import { SeloPrazo } from "../../fila-atendimento";
import { PainelDeEnvio } from "@/app/envio-de-anexos";
import { SeletorDeAnexos } from "@/app/seletor-de-anexos";
import { AnexarAvulso } from "../../anexar-avulso";
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
  const { estado, recarregar, atualizando } = useDetalheAtendimento(token, especie, id ?? null);
  const [recibo, setRecibo] = useState<string | null>(null);
  const refRecibo = useRef<HTMLDivElement>(null);
  const refResultado = useRef<HTMLDivElement>(null);
  const voltar = comToken(`/atendimento${especie ? `?aba=${especie}` : ""}`, token);
  // "ainda cabe anexar?" tem TRES respostas: sim, nao e NAO SEI (o detalhe esta sendo relido, ou a releitura falhou). "Nao sei"
  // nao e' "nao pode": nao dizemos que passaram os 10 minutos sem o servidor ter dito.
  const podeAnexar: boolean | null =
    estado.fase === "pronto" && !atualizando ? Boolean((estado.dado.acoes as { podeAnexar?: boolean }).podeAnexar) : null;
  // os anexos da resposta sobem DEPOIS do ato (a rota de anexo pede a resposta ja' gravada), um a um; no fim, o detalhe é
  // relido: a lista de anexos e o `pode-anexar` passam a valer o que o servidor tem agora
  const { itens: envio, enviar: enviarAnexos, tentarDeNovo, enviando } = useEnvioDeAnexos(async (arquivo) => {
    if (!especie || !id) return { ok: false, mensagem: "Endereço inválido: este protocolo não existe." };
    const r = await anexar(token, especie, id, arquivo);
    return r.ok ? { ok: true } : { ok: false, mensagem: r.mensagem };
  }, recarregar);

  async function concluir(texto: string, arquivos: File[]) {
    setRecibo(texto);
    recarregar();
    await enviarAnexos(arquivos);
  }

  // depois do ato, o recibo (e o envio dos anexos, logo abaixo) vao para a vista: ficariam acima da dobra, e quem usa leitor de
  // tela precisa ouvir o que aconteceu
  useEffect(() => {
    if (!recibo) return;
    refRecibo.current?.focus();
    refResultado.current?.scrollIntoView?.({ block: "start" });
  }, [recibo]);

  async function retirar(anexoId: string, motivo: string): Promise<string | null> {
    if (!especie || !id) return "Endereço inválido: este protocolo não existe.";
    const r = await retirarAnexo(token, especie, id, anexoId, motivo);
    if (!r.ok) return r.mensagem;
    recarregar();
    return null;
  }

  return (
    <>
      <TopoInterno area="Atendimento" />
      <main className="envelope atd atd-detalhe">
        <Link className="atd-voltar" href={voltar}>← Atendimento ao cidadão</Link>
        {!especie && <p className="atd-erro" role="alert">Endereço inválido: esta fila não existe.</p>}
        {especie && estado.fase === "carregando" && <p role="status">Carregando o protocolo…</p>}
        {especie && estado.fase === "erro" && <p className="atd-erro" role="alert">{estado.mensagem}</p>}
        <div ref={refResultado} className="atd-resultado">
          {recibo && (
            <div ref={refRecibo} tabIndex={-1} className="atd-recibo" role="status">
              <p>{recibo}</p>
              {/* enquanto os anexos sobem, sair levaria os que faltam: o link fica inativo ate' terminar */}
              {enviando ? (
                <span className="btn btn-primaria atd-inativo" aria-disabled="true">Voltar à fila</span>
              ) : (
                <Link className="btn btn-primaria" href={voltar}>Voltar à fila</Link>
              )}
            </div>
          )}
          <PainelDeEnvio
            itens={envio}
            podeTentarDeNovo={podeAnexar !== false && !enviando}
            incerto={podeAnexar === null}
            aoTentarDeNovo={(i) => void tentarDeNovo(i)}
          />
        </div>
        {especie && estado.fase === "pronto" && (
          <Protocolo especie={especie} id={id} d={estado.dado} token={token} aoConcluir={concluir} aoAnexar={enviarAnexos}
            enviandoAnexos={enviando} aoRetirar={retirar} />
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

function Protocolo({ especie, id, d, token, aoConcluir, aoAnexar, enviandoAnexos, aoRetirar }: {
  especie: Especie;
  id: string;
  d: DetalheOut;
  token: string | null;
  aoConcluir: (recibo: string, arquivos: File[]) => void;
  /** O controle avulso "Anexar arquivo" (enquanto `pode-anexar`): o mesmo envio um a um do formulário. */
  aoAnexar: (arquivos: File[]) => void;
  enviandoAnexos: boolean;
  /** Retirar um anexo (incidente de conteúdo): devolve a frase do erro, ou null se deu certo. */
  aoRetirar: (anexoId: string, motivo: string) => Promise<string | null>;
}) {
  const info = ESPECIES.find((e) => e.especie === especie)!;
  const prazo = linhaDoPrazo(d);
  const esic = especie === "esic" ? (d as DetalheEsicOut) : null;
  const ouv = especie === "ouvidoria" ? (d as DetalheOuvidoriaOut) : null;
  const lgpd = especie === "lgpd" ? (d as DetalheLgpdOut) : null;
  const texto = esic?.descricao ?? ouv?.descricao ?? lgpd?.detalhe ?? null;
  const anexos = d.anexos ?? [];
  const doPedido = anexos.filter((a) => a.origem === "requerente");
  const daResposta = anexos.filter((a) => a.origem !== "requerente");
  const podeAnexar = Boolean((d.acoes as { podeAnexar?: boolean }).podeAnexar);
  // manifestação ANONIMA nao tem dono: o arquivo anexado a resposta fica so' no registro da Casa (ninguem o baixa)
  const anonima = ouv?.identificacao === "anonima";

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

      {/* duas listas, duas origens: o que o requerente juntou ao pedido e o que a Casa juntou à resposta (nunca misturadas) */}
      <ListaDeAnexos titulo="Anexos do pedido" anexos={doPedido} rotaDe={(anexoId) => rotaDoAnexoNoBalcao(especie, id, anexoId)}
        token={token} aoRetirar={aoRetirar} />
      <ListaDeAnexos titulo="Anexos da resposta" anexos={daResposta} rotaDe={(anexoId) => rotaDoAnexoNoBalcao(especie, id, anexoId)}
        token={token} aoRetirar={aoRetirar} />

      {podeAnexar && <AnexarAvulso aoEnviar={(arquivos) => void aoAnexar(arquivos)} enviando={enviandoAnexos} anonima={anonima} />}

      <Acoes especie={especie} d={d} token={token} aoConcluir={aoConcluir} desabilitado={enviandoAnexos} anonima={anonima} />
    </>
  );
}

const FORM: Record<Acao, { botao: string; titulo: string; rotulo: string; enviar: string; enviando: string; oQue: string; teto: number; aviso: string }> = {
  responder: {
    botao: "Responder", titulo: "Responder ao cidadão", rotulo: "Resposta ao cidadão", enviar: "Enviar a resposta", enviando: "Enviando…",
    oQue: "a resposta", teto: TETO_RESPOSTA,
    aviso: "A resposta é definitiva: depois de enviada, encerra o protocolo e não pode ser editada.",
  },
  indeferir: {
    botao: "Indeferir", titulo: "Indeferir o pedido", rotulo: "Fundamentação do indeferimento", enviar: "Revisar o indeferimento", enviando: "Enviando…",
    oQue: "a fundamentação", teto: TETO_RESPOSTA,
    aviso: "O indeferimento é definitivo: encerra o protocolo, não pode ser desfeito, e a pessoa lê a fundamentação. A lei exige que a recusa diga as razões — escreva o motivo e, se houver, o dispositivo legal.",
  },
  "decidir-recurso": {
    botao: "Decidir o recurso", titulo: "Decidir o recurso", rotulo: "Decisão sobre o recurso", enviar: "Registrar a decisão", enviando: "Registrando…",
    oQue: "a decisão", teto: TETO_RESPOSTA, aviso: "A decisão é definitiva e encerra o recurso.",
  },
  prorrogar: {
    botao: "Prorrogar o prazo", titulo: "Prorrogar o prazo", rotulo: "Justificativa da prorrogação", enviar: "Prorrogar", enviando: "Prorrogando…",
    oQue: "a justificativa", teto: TETO_JUSTIFICATIVA,
    aviso: "A prorrogação só pode ser feita uma vez e exige justificativa.",
  },
  arquivar: {
    botao: "Arquivar sem resposta", titulo: "Arquivar a manifestação", rotulo: "Motivo do arquivamento", enviar: "Arquivar", enviando: "Arquivando…",
    oQue: "o motivo", teto: TETO_RESPOSTA,
    aviso: "Arquivar encerra a manifestação sem resposta de mérito — use quando ela não é da competência da Câmara ou não tem como ser apurada.",
  },
};

// o que acompanha-se de arquivo: a resposta, o indeferimento e a decisao do recurso (prorrogar e arquivar nao tem documento)
const COM_ANEXOS: Acao[] = ["responder", "indeferir", "decidir-recurso"];

function Acoes({ especie, d, token, aoConcluir, desabilitado = false, anonima = false }: {
  especie: Especie;
  d: DetalheOut;
  token: string | null;
  aoConcluir: (recibo: string, arquivos: File[]) => void;
  /** Os anexos da resposta anterior ainda estao subindo: nada de segundo ato no meio do envio. */
  desabilitado?: boolean;
  /** Manifestação anônima: o arquivo anexado fica só no registro da Casa (a dica do seletor muda). */
  anonima?: boolean;
}) {
  const [acao, setAcao] = useState<Acao | null>(null);
  const [texto, setTexto] = useState("");
  const [arquivos, setArquivos] = useState<File[]>([]);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  // o indeferimento é irreversível: um passo de confirmação mostra o texto que a pessoa vai ler antes de enviar
  const [confirmando, setConfirmando] = useState(false);
  // para onde vai o foco depois de trocar de passo (o editor <-> a confirmacao): o titulo do grupo, que o leitor de tela anuncia
  // (um ref, nao estado: o foco e' um efeito da troca de passo, nao algo que a tela renderiza)
  const foco = useRef<PassoDoIndeferimento | null>(null);
  const tituloDaConfirmacao = useRef<HTMLHeadingElement>(null);
  const tituloDoEditor = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    // só gasta o pedido quando este commit é o do passo pedido (ver `tituloQueRecebeOFoco`)
    const alvo = tituloQueRecebeOFoco(foco.current, confirmando);
    if (alvo === null) return;
    foco.current = null;
    if (alvo === "confirmar") tituloDaConfirmacao.current?.focus();
    else tituloDoEditor.current?.focus();
  }, [confirmando]);

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
            <button key={x} type="button" className={i === 0 ? "btn btn-primaria" : "btn btn-contorno"} disabled={desabilitado}
              onClick={() => abrir(x)}>
              {x === "prorrogar" ? `${FORM.prorrogar.botao} (+${dias} dias)` : FORM[x].botao}
            </button>
          ))}
        </div>
      )}
      {acao && f && confirmando && (
        <div className="atd-form" role="group" aria-label="Confirmar o indeferimento">
          <h3 ref={tituloDaConfirmacao} tabIndex={-1} className="atd-confirmar-titulo">Confirmar o indeferimento</h3>
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
            <button type="button" className="btn btn-fantasma" disabled={enviando}
              onClick={() => { foco.current = "editor"; setConfirmando(false); }}>
              Voltar e editar
            </button>
          </div>
        </div>
      )}
      {acao && f && !confirmando && (
        <div className="atd-form" role="group" aria-label={f.botao}>
          <h3 ref={tituloDoEditor} tabIndex={-1} className="atd-form-titulo">{f.titulo}</h3>
          <p className="atd-aviso">{aviso}</p>
          <div className="atd-campo">
            <label htmlFor="atd-texto">{f.rotulo}</label>
            <textarea id="atd-texto" value={texto} rows={acao === "prorrogar" ? 4 : 10} maxLength={f.teto + 200}
              onChange={(e) => setTexto(e.target.value)} />
          </div>
          {COM_ANEXOS.includes(acao) && (
            <SeletorDeAnexos arquivos={arquivos} aoMudar={setArquivos} desabilitado={enviando}
              id="atd-anexos" dica={dicaDosAnexosDaResposta(anonima)} />
          )}
          {falta && <p className="atd-falta">{falta}</p>}
          {erro && <p className="atd-erro" role="alert">{erro}</p>}
          <div className="atd-acoes">
            <button type="button" className="btn btn-primaria" disabled={enviando || falta !== null}
              onClick={acao === "indeferir" ? () => { setErro(null); foco.current = "confirmar"; setConfirmando(true); } : enviar}>
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
