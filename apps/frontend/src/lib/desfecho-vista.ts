// O desfecho da matéria depois do plenário (docs/16, retriagem linhas 18 e 30). Nenhum desses atos move o `estado`
// do rito — encerrar a votação não transiciona a proposição —, então a ficha e o portal que só liam o estado diziam
// "Aguardando pauta" para matéria aprovada, sancionada e até publicada como lei.
//
// Vocabulário FECHADO (o rito do Executivo e o resultado da votação são enum no backend, não texto livre por Casa):
// aprovada, rejeitada, autografo_enviado, sancionado, sancao_tacita, vetado, veto_mantido, veto_derrubado,
// promulgada, publicada. Os textos da linha do tempo são os mesmos que o portal grava
// (`transparencia/logic/desfecho.clj`); mudar um lado pede mudar o outro.

import type { EstagioTramitacao } from "./tramitacao-vista";

const NOME_DA_ESPECIE: Record<string, string> = {
  lei: "Lei",
  lei_complementar: "Lei Complementar",
  resolucao: "Resolução",
  decreto_legislativo: "Decreto Legislativo",
  emenda_lom: "Emenda à Lei Orgânica",
};

// A situação da matéria (o chip), quando ela já passou pelo plenário.
const SITUACAO_POR_DESFECHO: Record<string, string> = {
  aprovada: "Aprovada em plenário",
  rejeitada: "Rejeitada em plenário",
  autografo_enviado: "Enviada ao Executivo",
  sancionado: "Sancionada",
  sancao_tacita: "Sancionada (sanção tácita)",
  vetado: "Vetada",
  veto_mantido: "Veto mantido",
  veto_derrubado: "Veto derrubado",
  promulgada: "Promulgada",
  publicada: "Virou lei",
};

const ESTAGIOS = ["Protocolo", "Comissões", "1º turno", "2º turno", "Sanção"] as const;

// Quantas etapas da faixa já se completaram, e se a "Sanção" está em andamento. Rejeitada e veto mantido encerram o
// caminho sem lei: a faixa mostra até onde a matéria foi (o plenário; a sanção decidida), sem fingir que virou lei.
const FAIXA_POR_DESFECHO: Record<string, { concluidos: number; ativo: boolean }> = {
  aprovada: { concluidos: 4, ativo: false },
  rejeitada: { concluidos: 4, ativo: false },
  autografo_enviado: { concluidos: 4, ativo: true },
  vetado: { concluidos: 4, ativo: true },
  sancionado: { concluidos: 5, ativo: false },
  sancao_tacita: { concluidos: 5, ativo: false },
  veto_mantido: { concluidos: 5, ativo: false },
  veto_derrubado: { concluidos: 5, ativo: false },
  promulgada: { concluidos: 5, ativo: false },
  publicada: { concluidos: 5, ativo: false },
};

/** A situação e a faixa a partir do desfecho, ou null quando não há desfecho conhecido (a situação vem do rito). */
export function situacaoDoDesfecho(
  desfecho: string | null | undefined,
): { rotuloSituacao: string; estagios: EstagioTramitacao[] } | null {
  if (!desfecho) return null;
  const rotulo = SITUACAO_POR_DESFECHO[desfecho];
  const faixa = FAIXA_POR_DESFECHO[desfecho];
  if (!rotulo || !faixa) return null;
  return {
    rotuloSituacao: rotulo,
    estagios: ESTAGIOS.map((r, i) => ({
      rotulo: r,
      situacao: i < faixa.concluidos ? "concluido" : i === faixa.concluidos && faixa.ativo ? "ativo" : "pendente",
    })),
  };
}

export type AtoDepoisDoPlenario = {
  ato: string;
  redacaoFinal?: boolean;
  tipoNorma?: string;
  numero?: number;
  ano?: number;
};

function numeroDaNorma(a: AtoDepoisDoPlenario): string | null {
  if (a.numero == null || a.ano == null) return null;
  return `${NOME_DA_ESPECIE[a.tipoNorma ?? ""] ?? "Norma"} nº ${a.numero}/${a.ano}`;
}

/** O ato em palavras, para a linha do tempo. Ato desconhecido (versão futura do backend) → null. */
export function rotuloDoAto(a: AtoDepoisDoPlenario): string | null {
  switch (a.ato) {
    case "aprovada":
      return a.redacaoFinal ? "Redação final aprovada em plenário" : "Aprovada em plenário";
    case "rejeitada":
      return a.redacaoFinal ? "Redação final rejeitada em plenário" : "Rejeitada em plenário";
    case "autografo_enviado":
      return a.numero != null && a.ano != null
        ? `Autógrafo nº ${a.numero}/${a.ano} enviado ao Executivo`
        : "Autógrafo enviado ao Executivo";
    case "sancionado":
      return "Sancionada pelo Executivo";
    case "sancao_tacita":
      return "Sancionada sem resposta do Executivo no prazo (sanção tácita)";
    case "vetado":
      return "Vetada pelo Executivo";
    case "veto_mantido":
      return "Veto mantido pela Câmara";
    case "veto_derrubado":
      return "Veto derrubado pela Câmara";
    case "promulgada": {
      const n = numeroDaNorma(a);
      return n ? `Promulgação: ${n}` : "Promulgação";
    }
    case "publicada": {
      const n = numeroDaNorma(a);
      return n ? `Publicação: ${n}` : "Publicação";
    }
    default:
      return null;
  }
}

/** A cor do chip da situação pelo desfecho: virou (ou vai virar) lei = aprovada; rejeitada ou veto mantido = sem lei,
 * mesma cor da arquivada; à espera do Executivo = em tramitação. Null sem desfecho conhecido. */
export function categoriaDoDesfecho(desfecho: string | null | undefined): "aprovada" | "arquivada" | "tram" | null {
  switch (desfecho) {
    case "aprovada":
    case "sancionado":
    case "sancao_tacita":
    case "veto_derrubado":
    case "promulgada":
    case "publicada":
      return "aprovada";
    case "rejeitada":
    case "veto_mantido":
      return "arquivada";
    case "autografo_enviado":
    case "vetado":
      return "tram";
    default:
      return null;
  }
}
