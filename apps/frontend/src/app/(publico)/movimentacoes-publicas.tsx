"use client";

// "Por onde a matéria passou" na ficha PÚBLICA da matéria: cada movimentação com a data e o NOME da etapa no rito da
// Casa, da mais recente para a mais antiga (GET /api/portal/casa/{ente}/materias/{id}/movimentacoes). O servidor só
// devolve data + etapa — nada de quem despachou. A etapa chega como o rótulo do rito ("Em análise nas comissões"), nunca
// como a chave de cadastro; se o rito não nomeou a etapa, a tela diz "Mudança de etapa" em vez de inventar um nome.
//
// HONESTIDADE (nunca finge completude):
//  - histórico que começa no meio (a abertura não está registrada): "Histórico disponível a partir de DD/MM/AAAA";
//  - mais movimentações do que o servidor devolve: "Mostrando as N mais recentes, de um total de M";
//  - falha de rede / resposta estranha: a seção diz que não carregou, e a ficha segue de pé (degradação por seção).
// Enquanto carrega não mostra nada (evita o flash de "sem histórico").
//
// "ETAPA ATUAL" é a etapa mais recente que NÃO é votação. Votar não move a matéria de etapa (encerrar a votação não
// transiciona o rito, e o selo da ficha só muda a partir do autógrafo): marcar "Aprovada em 1º turno" como etapa atual
// contradizia o selo "Em pauta" ao lado. A votação continua na lista, com a data e o nome do ato (`votacao` vem do
// servidor; resposta sem o campo conta como etapa).

import { useEffect, useState } from "react";
import { buscarPublico } from "@/lib/portal-api";
import { formatarData } from "@/lib/formatar-data";
import type { MovimentacoesOut } from "@/lib/contrato-portal.gen";
import "./movimentacoes-publicas.css";

type Carga = { chave: string; dado: MovimentacoesOut | "erro" };

const valido = (d: unknown): d is MovimentacoesOut =>
  !!d &&
  Array.isArray((d as MovimentacoesOut).movimentacoes) &&
  typeof (d as MovimentacoesOut).movimentacoesTotal === "number" &&
  typeof (d as MovimentacoesOut).historicoCompleto === "boolean";

export function useMovimentacoesPublicas(ente: string, proposicaoId: string): MovimentacoesOut | "erro" | null {
  // o resultado guarda DE QUAL matéria veio: trocar de matéria esconde o anterior sem setState síncrono no efeito
  const [res, setRes] = useState<Carga>({ chave: "", dado: "erro" });
  const chave = `${ente}\u0000${proposicaoId}`;
  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await buscarPublico<MovimentacoesOut>(ente, "materias", proposicaoId, "movimentacoes");
      if (!vivo) return;
      setRes({ chave, dado: valido(r) ? r : "erro" });
    })();
    return () => {
      vivo = false;
    };
  }, [ente, proposicaoId, chave]);
  return res.chave === chave ? res.dado : null;
}

/** A posição da etapa atual na lista (da mais recente para a mais antiga): a mais recente que NÃO é votação, ou -1.
 * Uma só regra para a marca "Etapa atual" e para o chip da ficha, que assim nunca divergem. */
export function indiceDaEtapaAtual(movimentacoes: MovimentacoesOut["movimentacoes"]): number {
  return movimentacoes.findIndex((m) => !m.votacao);
}

/** O nome da etapa atual (a mesma marcada "Etapa atual"), ou null quando o histórico não carregou, só há votação ou o
 * rito não nomeou a etapa. É o que o chip da ficha pública diz. */
export function etapaAtualDasMovimentacoes(dado: MovimentacoesOut | "erro" | null): string | null {
  if (!dado || dado === "erro") return null;
  return dado.movimentacoes[indiceDaEtapaAtual(dado.movimentacoes)]?.etapa ?? null;
}

export function MovimentacoesPublicas({ ente, proposicaoId }: { ente: string; proposicaoId: string }) {
  const dado = useMovimentacoesPublicas(ente, proposicaoId);
  return <SecaoMovimentacoes dado={dado} />;
}

/** A seção a partir do histórico já buscado (a ficha busca uma vez e usa também no chip). */
export function SecaoMovimentacoes({ dado }: { dado: MovimentacoesOut | "erro" | null }) {
  if (dado === null) return null;

  return (
    <section className="secao mov" aria-labelledby="mov-titulo">
      <h2 id="mov-titulo">Por onde a matéria passou</h2>
      {dado === "erro" ? (
        <p className="mov-aviso">Não foi possível carregar o histórico da matéria agora. Tente novamente em instantes.</p>
      ) : (
        <Historico dado={dado} />
      )}
    </section>
  );
}

function Historico({ dado }: { dado: MovimentacoesOut }) {
  const { movimentacoes, movimentacoesTotal, historicoCompleto, historicoDesde } = dado;
  const atual = indiceDaEtapaAtual(movimentacoes);
  if (movimentacoes.length === 0) {
    return <p className="mov-aviso">O histórico desta matéria ainda não está disponível aqui.</p>;
  }
  return (
    <>
      {!historicoCompleto && historicoDesde && (
        <p className="mov-aviso">
          Histórico disponível a partir de {formatarData(historicoDesde)}. O que aconteceu antes dessa data não está
          registrado nesta página.
        </p>
      )}
      {movimentacoesTotal > movimentacoes.length && (
        <p className="mov-aviso">
          Mostrando as {movimentacoes.length} movimentações mais recentes, de um total de {movimentacoesTotal}.
        </p>
      )}
      <ol className="mov-lista" aria-label="Movimentações da matéria, da mais recente para a mais antiga">
        {movimentacoes.map((m, i) => (
          <li key={`${m.ocorridoEm}-${i}`} className="mov-item" aria-current={i === atual ? "step" : undefined}>
            <time className="mov-data" dateTime={m.ocorridoEm}>
              {formatarData(m.ocorridoEm)}
            </time>
            <p className="mov-etapa">{m.etapa ?? "Mudança de etapa"}</p>
            {i === atual && <span className="mov-atual">Etapa atual</span>}
          </li>
        ))}
      </ol>
    </>
  );
}
