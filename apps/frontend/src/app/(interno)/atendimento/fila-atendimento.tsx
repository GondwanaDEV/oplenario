"use client";

// As três filas do balcão (e-SIC · Ouvidoria · LGPD), cada uma pelo prazo que vence primeiro, com o selo do prazo
// (vencido / vence hoje / dias restantes) e o filtro em aberto · encerrados · todos. `token` por PROP para ser testável
// sem <AuthProvider> (mesma disciplina de fila-moderacao.tsx). A ordem e o prazo vêm prontos do servidor.
//
// Assunto e texto são conteúdo do cidadão: renderizados como TEXTO (o React escapa; nunca dangerouslySetInnerHTML).

import Link from "next/link";
import { useState } from "react";
import { comToken } from "@/lib/nav";
import {
  ESPECIES,
  SITUACOES,
  linhaDoPrazo,
  recebidoEm,
  rotuloEstado,
  seloDoPrazo,
  tituloDoItem,
  vazioDaFila,
  type Especie,
  type Situacao,
} from "@/lib/atendimento-vista";
import { useFilaAtendimento } from "@/lib/use-atendimento";
import type { ItemEsicOut, ItemLgpdOut, ItemOuvidoriaOut } from "@/lib/contrato-atendimento.gen";

type Item = ItemEsicOut | ItemOuvidoriaOut | ItemLgpdOut;

export function SeloPrazo({ item }: { item: { aberto: boolean; diasRestantes: number | null } }) {
  const s = seloDoPrazo(item);
  return <span className={`atd-selo atd-selo-${s.tom}`}>{s.texto}</span>;
}

function Linha({ especie, item, token }: { especie: Especie; item: Item; token: string | null }) {
  const titulo = tituloDoItem(especie, item as { assunto?: string; tipo?: string });
  const prazo = linhaDoPrazo(item);
  const recurso = especie === "esic" ? (item as ItemEsicOut).recursoPendente : null;
  return (
    <li className="atd-item">
      <Link href={comToken(`/atendimento/${especie}/${item.id}`, token)} aria-label={`Abrir ${item.protocolo}: ${titulo}`}>
        <span className="atd-item-topo">
          <span className="atd-protocolo">{item.protocolo}</span>
          <SeloPrazo item={item} />
          {recurso && <span className="atd-chip atd-chip-recurso">Recurso aguardando decisão</span>}
          {especie === "ouvidoria" && (
            <span className="atd-chip">{(item as ItemOuvidoriaOut).identificacao === "anonima" ? "Anônima" : "Identificada"}</span>
          )}
        </span>
        <span className="atd-titulo">{titulo}</span>
        <span className="atd-linha">
          {recebidoEm(item.recebidoEm)}
          {prazo ? ` · ${prazo}` : ""} · {rotuloEstado(item.estado)}
        </span>
      </Link>
    </li>
  );
}

export function FilaAtendimento({ token = null, especieInicial = "esic" }: { token?: string | null; especieInicial?: Especie }) {
  const [especie, setEspecie] = useState<Especie>(especieInicial);
  const [situacao, setSituacao] = useState<Situacao>("abertos");
  const { estado } = useFilaAtendimento(token, especie, situacao);
  const info = ESPECIES.find((e) => e.especie === especie)!;
  const itens: Item[] = estado.fase === "pronto" ? estado.dado.itens : [];
  const vencidos = itens.filter((i) => i.aberto && i.diasRestantes !== null && i.diasRestantes < 0).length;

  return (
    <section className="atd-fila" aria-labelledby="atd-fila-titulo">
      <nav className="atd-abas" aria-label="Filas do atendimento">
        {ESPECIES.map((e) => (
          <button key={e.especie} type="button" className="atd-aba" aria-pressed={especie === e.especie}
            onClick={() => setEspecie(e.especie)}>
            {e.rotulo}
          </button>
        ))}
      </nav>

      <div className="atd-fila-cabeca">
        <h2 id="atd-fila-titulo">{info.titulo}</h2>
        <p className="atd-lei">{info.lei}</p>
      </div>

      <nav className="atd-filtro" aria-label="Filtrar a fila">
        {SITUACOES.map((s) => (
          <button key={s.situacao} type="button" className="atd-filtro-botao" aria-pressed={situacao === s.situacao}
            onClick={() => setSituacao(s.situacao)}>
            {s.rotulo}
            {s.situacao === situacao && estado.fase === "pronto" ? ` (${itens.length})` : ""}
          </button>
        ))}
      </nav>

      {estado.fase === "carregando" && <p role="status">Carregando a fila…</p>}
      {estado.fase === "erro" && <p className="atd-erro" role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" && vencidos > 0 && situacao !== "respondidos" && (
        <p className="atd-alerta" role="status">
          {vencidos === 1 ? "1 protocolo com o prazo vencido." : `${vencidos} protocolos com o prazo vencido.`} Responda
          primeiro os do topo da fila.
        </p>
      )}
      {estado.fase === "pronto" &&
        (itens.length === 0 ? (
          <p className="atd-vazio">{vazioDaFila(especie, situacao)}</p>
        ) : (
          <ul className="atd-lista" aria-label={info.titulo}>
            {itens.map((i) => <Linha key={i.id} especie={especie} item={i} token={token} />)}
          </ul>
        ))}
    </section>
  );
}
