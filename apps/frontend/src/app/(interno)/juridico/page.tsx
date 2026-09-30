"use client";

// Rota /juridico (interno) — a FILA DO PARECER JURÍDICO (ADR-0019, fatia 1). O jurídico da Casa (papel `juridico`) abre
// cada pedido, redige e assina o parecer; a secretaria pede (matéria ou consulta avulsa) e acompanha. O parecer é
// opinativo: não decide a matéria. Arquétipo lista/detalhe, o mesmo de /conferencias. Gate de papel via <GuardJuridico>
// (a authz real é o backend).

import { useState } from "react";
import Link from "next/link";
import { useAuth, usePapeis } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  ABAS_FILA,
  AVISO_OPINATIVO,
  linhaDoPedido,
  refDoPedido,
  rotuloEstadoPedido,
  situacaoDoPedido,
  vazioDaFila,
} from "@/lib/juridico-vista";
import { usePedidosJuridicos } from "@/lib/use-juridico";
import type { EstadoPedido, PedidoJuridicoOut } from "@/lib/contrato-juridico.gen";
import { GuardJuridico } from "../guard-juridico";
import { TopoInterno } from "../topo";
import { NovoPedidoForm } from "./novo-pedido-form";
import "./juridico.css";

export default function PaginaJuridico() {
  return (
    <GuardJuridico>
      <Conteudo />
    </GuardJuridico>
  );
}

function Linha({ p, token, ehJuridico }: { p: PedidoJuridicoOut; token: string | null; ehJuridico: boolean }) {
  const ref = refDoPedido(p);
  return (
    <li className="jur-item">
      <div className="jur-item-corpo">
        <p className="jur-ref">
          <span className="jur-numero">{ref}</span>
          <span className={`jur-chip jur-chip-${p.estado}`}>{rotuloEstadoPedido(p.estado)}</span>
        </p>
        {p.proposicao && <p className="jur-ementa">{p.proposicao.ementa}</p>}
        <p className="jur-assunto">{p.assunto}</p>
        <p className="jur-linha">{linhaDoPedido(p)}</p>
        <p className="jur-linha">{situacaoDoPedido(p)}</p>
      </div>
      <Link className="btn btn-contorno jur-abrir" href={comToken(`/juridico/${p.id}`, token)} aria-label={`${ehJuridico ? "Abrir" : "Ver"} o pedido: ${ref}, ${p.assunto}`}>
        {ehJuridico ? "Abrir" : "Ver"}
      </Link>
    </li>
  );
}

function Conteudo() {
  const { token } = useAuth();
  const { papeis } = usePapeis();
  const ehJuridico = papeis.includes("juridico");
  const ehSecretaria = papeis.includes("secretario");
  const [aba, setAba] = useState<EstadoPedido>("pendente");
  const { estado, recarregar } = usePedidosJuridicos(token, aba);
  const [novoAberto, setNovoAberto] = useState(false);
  const [aviso, setAviso] = useState<{ id: string; assunto: string } | null>(null);
  const itens = estado.fase === "pronto" ? estado.dado.pedidos : [];

  return (
    <>
      <TopoInterno area="Jurídico" />
      <main className="envelope jur">
        <header className="jur-cabeca">
          <h1>Parecer jurídico</h1>
          <p className="jur-sub">
            {ehJuridico
              ? "Os pedidos de parecer da Casa. Abra um pedido, escreva o relatório e a fundamentação, conclua e assine."
              : "Os pedidos de parecer jurídico da Casa e em que pé está cada um."}
          </p>
          <p className="jur-opinativo" role="note">{AVISO_OPINATIVO}</p>
        </header>

        {ehSecretaria && (
          <div className="jur-barra">
            {!novoAberto && (
              <button type="button" className="btn btn-primaria" onClick={() => { setAviso(null); setNovoAberto(true); }}>
                Novo pedido de parecer
              </button>
            )}
          </div>
        )}

        {aviso && (
          <p role="status" className="jur-ok">
            Pedido aberto: {aviso.assunto}. <Link href={comToken(`/juridico/${aviso.id}`, token)}>Abrir o pedido</Link>
          </p>
        )}

        {ehSecretaria && novoAberto && (
          <section className="jur-painel" aria-labelledby="jur-novo-titulo">
            <h2 id="jur-novo-titulo">Novo pedido de parecer (consulta avulsa)</h2>
            <NovoPedidoForm
              token={token}
              onCancelar={() => setNovoAberto(false)}
              onCriado={(p) => {
                setNovoAberto(false);
                setAviso({ id: p.id, assunto: p.assunto });
                setAba("pendente");
                recarregar();
              }}
            />
          </section>
        )}

        <nav className="jur-abas" aria-label="Filtrar os pedidos">
          {ABAS_FILA.map((a) => (
            <button key={a.estado} type="button" className="jur-aba" aria-pressed={aba === a.estado} onClick={() => setAba(a.estado)}>
              {a.rotulo}
              {a.estado === aba && estado.fase === "pronto" ? ` (${itens.length})` : ""}
            </button>
          ))}
        </nav>

        {estado.fase === "carregando" && <p role="status">Carregando os pedidos…</p>}
        {estado.fase === "erro" && <p className="jur-erro" role="alert">{estado.mensagem}</p>}
        {estado.fase === "pronto" &&
          (itens.length === 0 ? (
            <p className="jur-vazio">{vazioDaFila(aba, ehSecretaria)}</p>
          ) : (
            <ul className="jur-lista" aria-label="Pedidos de parecer">
              {itens.map((p) => <Linha key={p.id} p={p} token={token} ehJuridico={ehJuridico} />)}
            </ul>
          ))}
      </main>
    </>
  );
}
