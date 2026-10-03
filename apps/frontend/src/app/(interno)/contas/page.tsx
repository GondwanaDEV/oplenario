"use client";

// Rota /contas (interno) — as PRESTAÇÕES DE CONTAS da Casa (ADR-0021 Parte B): as contas de governo do Prefeito, que a
// Câmara julga sobre o parecer prévio do TCE, e as contas de gestão da Mesa, que ela só acompanha. Secretaria, vereador
// e jurídico leem; só a secretaria registra ("Registrar prestação" — o registro do governo protocola o PDL). Arquétipo
// lista/detalhe, o mesmo de /juridico. Gate de papel via <GuardContas> (a authz real é o backend).

import Link from "next/link";
import { useAuth, usePapeis } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { ordenarPrestacoes, rotuloEstado, rotuloParecer, situacaoDaLinha, tituloDaPrestacao, tomDoEstado, vazioDaLista } from "@/lib/contas-vista";
import { useListaContas } from "@/lib/use-contas";
import type { PrestacaoResumo } from "@/lib/contrato-contas";
import { GuardContas } from "../guard-contas";
import { TopoInterno } from "../topo";
import "./contas.css";

export default function PaginaContas() {
  return (
    <GuardContas>
      <Conteudo />
    </GuardContas>
  );
}

function Linha({ p, token }: { p: PrestacaoResumo; token: string | null }) {
  const titulo = tituloDaPrestacao(p);
  return (
    <li className="cts-item">
      <div className="cts-item-corpo">
        <p className="cts-ref">
          <span className="cts-exercicio">{p.exercicio}</span>
          <span className={`cts-chip cts-chip-${tomDoEstado(p.estado)}`}>{rotuloEstado(p.estado)}</span>
        </p>
        <p className="cts-titulo">{titulo}</p>
        <p className="cts-linha">Responsável: {p.responsavel}</p>
        {p.parecerPrevio && <p className="cts-linha">Parecer prévio do TCE: {rotuloParecer(p.parecerPrevio)}</p>}
        <p className="cts-linha">{situacaoDaLinha(p)}</p>
      </div>
      <Link className="btn btn-contorno cts-abrir" href={comToken(`/contas/${encodeURIComponent(p.id)}`, token)} aria-label={`Abrir: ${titulo}`}>
        Abrir
      </Link>
    </li>
  );
}

function Conteudo() {
  const { token } = useAuth();
  const { papeis } = usePapeis();
  const ehSecretaria = papeis.includes("secretario");
  const { estado } = useListaContas(token);
  const itens = estado.fase === "pronto" ? ordenarPrestacoes(estado.dado.prestacoes) : [];

  return (
    <>
      <TopoInterno area="Contas" />
      <main className="envelope cts">
        <header className="cts-cabeca">
          <span className="cts-crumb">Controle externo · Contas</span>
          <h1>Prestações de contas</h1>
          <p className="cts-sub">
            As contas de governo do Prefeito, que a Câmara julga com base no parecer prévio do Tribunal de Contas, e as
            contas de gestão da Câmara, acompanhadas no TCE.
          </p>
        </header>

        {ehSecretaria && (
          <div className="cts-barra">
            <Link className="btn btn-primaria" href={comToken("/contas/nova", token)}>
              Registrar prestação
            </Link>
          </div>
        )}

        {estado.fase === "carregando" && <p role="status">Carregando as prestações…</p>}
        {estado.fase === "erro" && <p className="cts-erro" role="alert">{estado.mensagem}</p>}
        {estado.fase === "pronto" &&
          (itens.length === 0 ? (
            <p className="cts-vazio">{vazioDaLista(ehSecretaria)}</p>
          ) : (
            <ul className="cts-lista" aria-label="Prestações de contas">
              {itens.map((p) => <Linha key={p.id} p={p} token={token} />)}
            </ul>
          ))}
      </main>
    </>
  );
}
