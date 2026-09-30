"use client";

// Rota /juridico/:id (interno) — um pedido de parecer jurídico (ADR-0019). O jurídico escreve o parecer (relatório,
// fundamentação e conclusão), salva o rascunho e ASSINA — depois de assinado o texto não muda: corrigir é emitir um novo
// parecer que o substitui, e os dois ficam na ficha. A secretaria lê e, enquanto o pedido está pendente, pode cancelar.
// O parecer é opinativo e só um advogado o assina: o rascunho da IA nunca é chamado de parecer.

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useAuth, usePapeis } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  AVISO_OPINATIVO,
  CONCLUSOES,
  camposDoParecer,
  camposIguais,
  frasesFaltaParaAssinar,
  linhaDoPedido,
  modoDoDetalhe,
  podeCancelar,
  podeSalvarRascunho,
  podeSubstituir,
  refDoPedido,
  rotuloEstadoPedido,
  type CamposDoParecer,
} from "@/lib/juridico-vista";
import {
  assinarParecer,
  cancelarPedido,
  salvarRascunho,
  substituirParecer,
  usePedidoJuridico,
  type Resultado,
} from "@/lib/use-juridico";
import type { PedidoJuridicoOut } from "@/lib/contrato-juridico.gen";
import { GuardJuridico } from "../../guard-juridico";
import { ParecerAssinado } from "../parecer-assinado";
import { TopoInterno } from "../../topo";
import "../juridico.css";

export default function PaginaPedido() {
  return (
    <GuardJuridico>
      <Conteudo />
    </GuardJuridico>
  );
}

function Conteudo() {
  const { token } = useAuth();
  const { papeis } = usePapeis();
  const { id } = useParams<{ id: string }>();
  const { estado, setEstado } = usePedidoJuridico(token, id ?? null);
  const ehJuridico = papeis.includes("juridico");
  const ehSecretaria = papeis.includes("secretario");

  return (
    <>
      <TopoInterno area="Jurídico" />
      <main className="envelope jur jur-detalhe">
        <Link className="jur-voltar" href={comToken("/juridico", token)}>← Parecer jurídico</Link>
        {estado.fase === "carregando" && <p role="status">Carregando o pedido…</p>}
        {estado.fase === "erro" && <p className="jur-erro" role="alert">{estado.mensagem}</p>}
        {estado.fase === "pronto" && (
          <Pedido
            pedido={estado.dado}
            token={token}
            ehJuridico={ehJuridico}
            ehSecretaria={ehSecretaria}
            aoMudar={(p) => setEstado({ fase: "pronto", dado: p })}
          />
        )}
      </main>
    </>
  );
}

function Pedido({ pedido, token, ehJuridico, ehSecretaria, aoMudar }: {
  pedido: PedidoJuridicoOut;
  token: string | null;
  ehJuridico: boolean;
  ehSecretaria: boolean;
  aoMudar: (p: PedidoJuridicoOut) => void;
}) {
  const modo = modoDoDetalhe(pedido, ehJuridico);
  const par = pedido.parecer;
  return (
    <>
      <header className="jur-cabeca">
        <p className="jur-ref">
          <span className="jur-numero">{refDoPedido(pedido)}</span>
          <span className={`jur-chip jur-chip-${pedido.estado}`}>{rotuloEstadoPedido(pedido.estado)}</span>
        </p>
        <h1>{pedido.assunto}</h1>
        {pedido.proposicao && (
          <p className="jur-sub">
            {pedido.proposicao.ementa} ·{" "}
            <Link href={comToken(`/ficha-materia/${pedido.proposicao.id}`, token)}>Abrir a ficha da matéria</Link>
          </p>
        )}
        <p className="jur-sub">{linhaDoPedido(pedido)}</p>
        <p className="jur-opinativo" role="note">{AVISO_OPINATIVO}</p>
      </header>

      {pedido.estado === "cancelado" && (
        <p className="jur-vazio">A secretaria cancelou este pedido. Ele fica guardado só como registro.</p>
      )}

      {modo === "escrever" && (
        <Editor key={`${pedido.id}:${par?.substituiId ?? ""}`} pedido={pedido} token={token} aoMudar={aoMudar} />
      )}

      {modo === "ler" && par && par.estado === "assinado" && <ParecerAssinado parecer={par} />}

      {modo === "ler" && par && par.estado === "rascunho" && pedido.estado !== "cancelado" && (
        <p className="jur-vazio" role="status">
          O jurídico está redigindo o parecer. O texto só fica visível depois de assinado.
        </p>
      )}

      {modo === "ler" && !par && pedido.estado === "pendente" && (
        <p className="jur-vazio" role="status">O parecer ainda não foi iniciado.</p>
      )}

      {podeSubstituir(pedido, ehJuridico) && <Substituir pedido={pedido} token={token} aoMudar={aoMudar} />}
      {podeCancelar(pedido, ehSecretaria) && <Cancelar pedido={pedido} token={token} aoMudar={aoMudar} />}
    </>
  );
}

function Editor({ pedido, token, aoMudar }: { pedido: PedidoJuridicoOut; token: string | null; aoMudar: (p: PedidoJuridicoOut) => void }) {
  const salvo = camposDoParecer(pedido.parecer);
  const [campos, setCampos] = useState<CamposDoParecer>(salvo);
  const [enviando, setEnviando] = useState(false);
  const [confirmando, setConfirmando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const sujo = !camposIguais(campos, salvo);
  const falta = frasesFaltaParaAssinar(campos);
  const substitui = !!pedido.parecer?.substituiId;

  function mudar(campo: keyof CamposDoParecer, valor: string) {
    setCampos((c) => ({ ...c, [campo]: valor }));
    setAviso(null);
  }

  async function salvar(): Promise<Resultado<PedidoJuridicoOut>> {
    const r = await salvarRascunho(token, pedido.id, campos);
    if (r.ok) aoMudar(r.dado);
    return r;
  }

  async function aoSalvar() {
    setEnviando(true);
    setErro(null);
    setAviso(null);
    const r = await salvar();
    setEnviando(false);
    if (r.ok) setAviso("Rascunho salvo. O parecer ainda não foi assinado.");
    else setErro(r.mensagem);
  }

  async function aoAssinar() {
    setEnviando(true);
    setErro(null);
    setAviso(null);
    // o que está na tela é o que se assina: grava o rascunho antes, se mudou
    if (sujo) {
      const s = await salvar();
      if (!s.ok) {
        setEnviando(false);
        setConfirmando(false);
        setErro(s.mensagem);
        return;
      }
    }
    const a = await assinarParecer(token, pedido.id);
    setEnviando(false);
    if (a.ok) aoMudar(a.dado);
    else {
      setConfirmando(false);
      setErro(a.mensagem);
    }
  }

  return (
    <section className="jur-editor" aria-label="Escrever o parecer">
      <h2>{substitui ? "Novo parecer (substitui o anterior)" : "Escrever o parecer"}</h2>
      {substitui && (
        <p className="jur-dica" role="note">
          Este rascunho parte do texto do parecer já assinado. O anterior continua valendo até você assinar este; depois
          disso, os dois ficam na ficha, e o anterior aparece como substituído.
        </p>
      )}
      <p className="jur-dica">
        Só você, como jurídico da Casa, escreve e assina. Texto de IA não é parecer: se usar um rascunho de máquina, você o
        lê, assume e responde por ele.
      </p>

      <div className="jur-campo">
        <label htmlFor="jur-relatorio">Relatório</label>
        <textarea id="jur-relatorio" rows={6} value={campos.relatorio} onChange={(e) => mudar("relatorio", e.target.value)} />
      </div>
      <div className="jur-campo">
        <label htmlFor="jur-fundamentacao">Fundamentação</label>
        <textarea id="jur-fundamentacao" rows={10} value={campos.fundamentacao} onChange={(e) => mudar("fundamentacao", e.target.value)} />
      </div>
      <div className="jur-campo">
        <label htmlFor="jur-conclusao">Conclusão</label>
        <select id="jur-conclusao" value={campos.conclusao} onChange={(e) => mudar("conclusao", e.target.value)}>
          <option value="">Escolha a conclusão…</option>
          {CONCLUSOES.map((c) => <option key={c.valor} value={c.valor}>{c.rotulo}</option>)}
        </select>
      </div>

      {falta && <p className="jur-falta">{falta}</p>}
      {aviso && <p role="status" className="jur-ok">{aviso}</p>}
      {erro && <p role="alert" className="jur-erro">{erro}</p>}

      {!confirmando ? (
        <div className="jur-acoes">
          <button type="button" className="btn btn-contorno" disabled={enviando || !podeSalvarRascunho(campos) || !sujo} onClick={aoSalvar}>
            {enviando ? "Salvando…" : "Salvar rascunho"}
          </button>
          <button type="button" className="btn btn-primaria" disabled={enviando || falta !== null}
            onClick={() => { setErro(null); setAviso(null); setConfirmando(true); }}>
            Assinar parecer
          </button>
        </div>
      ) : (
        <div className="jur-confirma" role="group" aria-label="Confirmar a assinatura">
          <p><b>Assinar este parecer?</b> Depois de assinado, o texto não muda. Para corrigir, você emite um novo parecer que
            substitui este, e os dois ficam na ficha.</p>
          <p>A assinatura registra seu nome, sua OAB e sua qualificação, com a data e a hora.</p>
          <div className="jur-acoes">
            <button type="button" className="btn btn-primaria" disabled={enviando} onClick={aoAssinar}>
              {enviando ? "Assinando…" : "Assinar e registrar"}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => setConfirmando(false)}>
              Voltar ao texto
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function Substituir({ pedido, token, aoMudar }: { pedido: PedidoJuridicoOut; token: string | null; aoMudar: (p: PedidoJuridicoOut) => void }) {
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  async function emitir() {
    setEnviando(true);
    setErro(null);
    const r = await substituirParecer(token, pedido.id);
    setEnviando(false);
    if (r.ok) aoMudar(r.dado);
    else setErro(r.mensagem);
  }
  return (
    <section className="jur-substituir" aria-label="Emitir novo parecer">
      <p className="jur-dica">
        O parecer assinado não pode ser editado. Se algo precisa mudar, emita um novo parecer: ele nasce como rascunho, com o
        texto deste, e só substitui o atual quando você o assinar.
      </p>
      {erro && <p role="alert" className="jur-erro">{erro}</p>}
      <button type="button" className="btn btn-contorno" disabled={enviando} onClick={emitir}>
        {enviando ? "Abrindo o rascunho…" : "Emitir novo parecer (substitui este)"}
      </button>
    </section>
  );
}

function Cancelar({ pedido, token, aoMudar }: { pedido: PedidoJuridicoOut; token: string | null; aoMudar: (p: PedidoJuridicoOut) => void }) {
  const [confirmando, setConfirmando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  async function cancelar() {
    setEnviando(true);
    setErro(null);
    const r = await cancelarPedido(token, pedido.id);
    setEnviando(false);
    if (r.ok) aoMudar(r.dado);
    else {
      setConfirmando(false);
      setErro(r.mensagem);
    }
  }
  return (
    <section className="jur-cancelar" aria-label="Cancelar o pedido">
      {erro && <p role="alert" className="jur-erro">{erro}</p>}
      {!confirmando ? (
        <button type="button" className="btn btn-fantasma" onClick={() => setConfirmando(true)}>Cancelar pedido</button>
      ) : (
        <div className="jur-confirma" role="group" aria-label="Confirmar o cancelamento">
          <p><b>Cancelar este pedido?</b> Ele sai da fila do jurídico. Um pedido já atendido não pode ser cancelado.</p>
          <div className="jur-acoes">
            <button type="button" className="btn btn-contorno" disabled={enviando} onClick={cancelar}>
              {enviando ? "Cancelando…" : "Cancelar o pedido"}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => setConfirmando(false)}>Voltar</button>
          </div>
        </div>
      )}
    </section>
  );
}
