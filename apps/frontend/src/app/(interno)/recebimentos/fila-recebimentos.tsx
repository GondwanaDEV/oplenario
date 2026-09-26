"use client";

// A fila de cargas não recebidas (fatia 2b). Cada linha é uma matéria parada num estado que o rito marca como
// "exige recebimento": o número, a ementa, onde chegou e há quanto tempo — e o canhoto para receber e assinar
// ali mesmo, sem abrir a ficha (quem recebe o malote recebe vários de uma vez). Mais antigas primeiro: é a
// ordem do backend, quem espera há mais tempo aparece em cima.

import Link from "next/link";
import { useRecebimentosPendentes } from "@/lib/use-recebimentos-pendentes";
import { vistaCarga } from "@/lib/recebimento-vista";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { comToken } from "@/lib/nav";
import { ReceberCarga } from "../receber-carga";

export function FilaRecebimentos({ token = null }: { token?: string | null }) {
  const { itens, estado, recarregar } = useRecebimentosPendentes(token);

  return (
    <main className="envelope receb">
      <header className="receb-cabeca">
        <h1>Recebimentos pendentes</h1>
        <p className="receb-sub">
          Matérias que chegaram a uma etapa em que o rito da Casa exige recebimento. Enquanto ninguém receber e
          assinar, elas não andam.
        </p>
        {estado === "pronto" && (
          <p className="receb-resumo" role="status">
            {itens.length === 0
              ? "Nenhuma carga esperando recebimento."
              : itens.length === 1
                ? "1 matéria esperando recebimento"
                : `${itens.length} matérias esperando recebimento`}
          </p>
        )}
      </header>

      {estado === "carregando" && <p role="status">Carregando a fila…</p>}
      {estado === "erro" && <p role="status">Não foi possível carregar a fila de recebimentos.</p>}

      {estado === "pronto" && itens.length > 0 && (
        <ul className="receb-lista">
          {itens.map((item) => (
            <li key={item.movimentacaoId} className="receb-item">
              <p className="receb-numero">{formatarNumeroProposicao(item.tipo, item.sequencial, item.ano)}</p>
              <p className="receb-ementa">{item.ementa}</p>
              <Link className="receb-ir" href={comToken(`/ficha-materia/${item.proposicaoId}`, token)}>
                Abrir a ficha
              </Link>
              <ReceberCarga
                proposicaoId={item.proposicaoId}
                carga={vistaCarga(item)}
                token={token}
                titulo={false}
                onMudou={recarregar}
              />
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
