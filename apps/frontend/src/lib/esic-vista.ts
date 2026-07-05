// View-model puro do status público do e-SIC (Task 2.1, Fatia A2.2 — Portal do Cidadão). Porta o rito
// ilustrativo de 3 estágios de portal-cidadao.html:545-563 (Protocolado/Em análise/Respondido) a partir
// do status público real do pedido (AcompanhamentoEsicOut, GET .../esic/acompanhar/{protocolo}).
//
// VOCABULÁRIO: o `estado` de AcompanhamentoEsicOut é um enum FECHADO do backend
// (`participacao/wire/out/acompanhamento.clj`) — "protocolado" | "em_analise" | "respondido" |
// "indeferido" — diferente do vocabulário LIVRE de proposição em tramitacao-vista.ts (por isso este
// view-model não reusa `derivarTramitacao`, mas segue a MESMA disciplina fail-closed). "indeferido" =
// pedido respondido com negativa: para o rito de 3 estágios ele já percorreu o ciclo inteiro (mesma
// faixa completa de "respondido"), só o rótulo da situação muda — honesto sobre o desfecho, não sobre o
// andamento.
//
// Prazo legal (LAI, art. 11, portal-cidadao.html:541): 20 dias, prorrogável por mais 10. É uma
// constante da LEI, não um dado do pedido — não fabricamos um total por pedido (mesmo racional do
// e-SIC balcão para o texto do prazo).
//
// A assinatura aceita `estado: string` (mais largo que o enum de AcompanhamentoEsicOut) de propósito:
// dado que atravessa a fronteira de rede não é garantidamente o subconjunto que o tipo promete —
// fail-closed cobre qualquer valor fora do vocabulário conhecido sem lançar (mesmo padrão de
// `derivarTramitacao`).

import type { EstagioTramitacao } from "./tramitacao-vista";

export const DIAS_TOTAL_LAI = 20;

const ESTAGIOS_ESIC = ["Protocolado", "Em análise", "Respondido"] as const;

const ROTULO_POR_ESTADO: Record<string, string> = {
  protocolado: "Protocolado",
  em_analise: "Em análise",
  respondido: "Respondido",
  indeferido: "Indeferido",
};

export type StatusEsicVista = {
  estagios: EstagioTramitacao[];
  rotuloSituacao: string;
  diasRestantes: number | null;
};

const FAIXA_MINIMA: EstagioTramitacao[] = [{ rotulo: "Protocolado", situacao: "ativo" }];

function faixaConcluida(): EstagioTramitacao[] {
  return ESTAGIOS_ESIC.map((rotulo) => ({ rotulo, situacao: "concluido" as const }));
}

function faixaComAtivo(indiceAtivo: number): EstagioTramitacao[] {
  return ESTAGIOS_ESIC.map((rotulo, i) => ({
    rotulo,
    situacao: i < indiceAtivo ? "concluido" : i === indiceAtivo ? "ativo" : "pendente",
  }));
}

export function derivarStatusEsic(status: { estado: string; diasRestantes: number | null }): StatusEsicVista {
  const diasRestantes = status.diasRestantes ?? null;

  switch (status.estado) {
    case "protocolado":
      return { estagios: faixaComAtivo(0), rotuloSituacao: ROTULO_POR_ESTADO.protocolado, diasRestantes };
    case "em_analise":
      return { estagios: faixaComAtivo(1), rotuloSituacao: ROTULO_POR_ESTADO.em_analise, diasRestantes };
    case "respondido":
      return { estagios: faixaConcluida(), rotuloSituacao: ROTULO_POR_ESTADO.respondido, diasRestantes };
    case "indeferido":
      return { estagios: faixaConcluida(), rotuloSituacao: ROTULO_POR_ESTADO.indeferido, diasRestantes };
    default:
      // fail-closed: estado fora do enum conhecido -> faixa mínima honesta, nunca lança.
      return { estagios: FAIXA_MINIMA, rotuloSituacao: status.estado, diasRestantes };
  }
}
