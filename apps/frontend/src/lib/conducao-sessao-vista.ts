// View-model PURO do "Comando da Mesa" — o painel que CONDUZ o ciclo de vida da sessão (§22.6 eixo C).
// Sem React, testável isolado. Traduz o `SessaoOut` (estado + lock-version) na lista de transições que a
// máquina de estados do backend PERMITE a partir do estado atual, já com rótulo/tom/semântica de tela.
//
// A FONTE DA VERDADE é o backend (`sessoes/logic/transicoes-sessao`): a UI só ESPELHA o grafo para não
// oferecer um botão que o servidor recusaria (409 `:conflito/transicao`). Se as duas divergirem, o servidor
// continua sendo a autoridade — o disparo é recusado, nunca aplicado. Este módulo nunca envia nada: só
// deriva o que a Mesa PODE tentar. O `para` é o alvo imutável que vai no corpo do POST /transicao; `rotulo`
// e `descricao` são só apresentação.
//
// Grafo espelhado (transicoes-sessao):
//   agendada     → aberta | nao_realizada
//   aberta       → suspensa | encerrada
//   suspensa     → aberta | encerrada
//   encerrada    → arquivada
//   nao_realizada→ arquivada
//   arquivada    → (terminal, sem saída)

import type { SessaoOut } from "./contrato-sessoes.gen";

export type SessaoEstado = SessaoOut["estado"];

/** Tom visual do ato — dirige a classe do botão na tela, nunca a lógica. */
export type TomAto = "primaria" | "neutra" | "perigo";

export interface AtoConducao {
  /** Estado-alvo — o valor imutável de `para` no corpo do POST /transicao. */
  para: SessaoEstado;
  rotulo: string;
  descricao: string;
  tom: TomAto;
  /** Quando true, a tela EXIGE um motivo antes de disparar (o backend registra o porquê). */
  exigeMotivo: boolean;
  /** Quando presente, a tela pede confirmação explícita (ato irreversível/pesado) com este texto. */
  confirmacao?: string;
}

/** Situação macro do estado atual — dirige o tom do selo de estado, não a lógica. */
export type SituacaoSessao = "agendada" | "viva" | "suspensa" | "terminal";

export interface VistaConducao {
  estado: SessaoEstado;
  rotuloEstado: string;
  situacao: SituacaoSessao;
  /** Sessão em estado terminal (arquivada) — nada mais a conduzir. */
  terminal: boolean;
  atos: AtoConducao[];
}

const ROTULO_ESTADO: Record<SessaoEstado, string> = {
  agendada: "Agendada",
  aberta: "Aberta",
  suspensa: "Suspensa",
  encerrada: "Encerrada",
  nao_realizada: "Não realizada",
  arquivada: "Arquivada",
};

const SITUACAO: Record<SessaoEstado, SituacaoSessao> = {
  agendada: "agendada",
  aberta: "viva",
  suspensa: "suspensa",
  encerrada: "terminal",
  nao_realizada: "terminal",
  arquivada: "terminal",
};

// Cada aresta do grafo com a sua apresentação. O mesmo alvo (`encerrada`) aparece a partir de `aberta` e de
// `suspensa` com o MESMO ato — definido uma vez e reusado.
const ATO_ABRIR: AtoConducao = {
  para: "aberta",
  rotulo: "Abrir a sessão",
  descricao: "Instala a sessão e libera a chamada, a tribuna e as votações.",
  tom: "primaria",
  exigeMotivo: false,
};
const ATO_ENCERRAR: AtoConducao = {
  para: "encerrada",
  rotulo: "Encerrar a sessão",
  descricao: "Fecha a sessão. A presença e as votações desta sessão deixam de aceitar alteração.",
  tom: "perigo",
  exigeMotivo: false,
  confirmacao: "Encerrar a sessão fecha a presença e as votações desta sessão. Confirmar o encerramento?",
};
const ATO_ARQUIVAR: AtoConducao = {
  para: "arquivada",
  rotulo: "Arquivar",
  descricao: "Arquiva a sessão. Estado final, para organização do histórico.",
  tom: "neutra",
  exigeMotivo: false,
};

const TRANSICOES: Record<SessaoEstado, AtoConducao[]> = {
  agendada: [
    ATO_ABRIR,
    {
      para: "nao_realizada",
      rotulo: "Marcar como não realizada",
      descricao: "Registra que a sessão convocada não se realizou (falta de quórum, adiamento etc.).",
      tom: "perigo",
      exigeMotivo: true,
    },
  ],
  aberta: [
    {
      para: "suspensa",
      rotulo: "Suspender",
      descricao: "Interrompe a sessão temporariamente. Pode ser reaberta em seguida.",
      tom: "neutra",
      exigeMotivo: false,
    },
    ATO_ENCERRAR,
  ],
  suspensa: [
    {
      para: "aberta",
      rotulo: "Reabrir",
      descricao: "Retoma a sessão suspensa de onde parou.",
      tom: "primaria",
      exigeMotivo: false,
    },
    ATO_ENCERRAR,
  ],
  encerrada: [ATO_ARQUIVAR],
  nao_realizada: [ATO_ARQUIVAR],
  arquivada: [],
};

export function rotuloEstadoSessao(estado: SessaoEstado): string {
  return ROTULO_ESTADO[estado];
}

/** Deriva o painel de condução a partir do `SessaoOut`. Puro: mesmo estado → mesma vista, sempre. */
export function derivarConducaoSessao(sessao: SessaoOut): VistaConducao {
  const estado = sessao.estado;
  const atos = TRANSICOES[estado] ?? [];
  return {
    estado,
    rotuloEstado: ROTULO_ESTADO[estado],
    situacao: SITUACAO[estado],
    terminal: estado === "arquivada",
    atos,
  };
}
