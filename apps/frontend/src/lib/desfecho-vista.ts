// O desfecho da matéria depois do plenário, no portal (docs/16, retriagem linha 18). Nenhum desses atos move o
// `estado` do rito, então a ficha pública dizia "Aguardando pauta" de matéria sancionada e até publicada como lei.
//
// O selo só muda a partir do AUTÓGRAFO: a votação em plenário fica na frase "Última votação em plenário: …" da ficha
// (#157), e o selo continua no rito — numa matéria de dois turnos, "Aguardando pauta" depois do 1º turno é verdade.
// Depois do autógrafo não há ambiguidade: a matéria saiu do plenário e foi ao Executivo. Na emenda à Lei Orgânica
// (dois turnos, CF art. 29) o desfecho "aprovada" do 1º turno também não decide nada aqui: a linha do tempo diz
// "Aprovada em 1º turno" (rótulo do backend) e a frase da última votação diz o turno (`atoDaVotacao`).
//
// Vocabulário FECHADO (o rito do Executivo é enum no backend, não texto livre por Casa); os mesmos atos entram na
// linha do tempo pública com os rótulos de `transparencia/logic/desfecho.clj`.

import type { EstagioTramitacao } from "./tramitacao-vista";
import type { PosAprovacaoOut } from "./contrato-legislativo.gen";

const SITUACAO_POR_DESFECHO: Record<string, string> = {
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

// À espera do Executivo (autógrafo enviado, veto ainda não apreciado) a "Sanção" está em andamento; decidida a
// sanção ou o veto, a faixa fecha — com veto mantido também: o caminho terminou, só que sem lei.
const SANCAO_EM_ANDAMENTO = new Set(["autografo_enviado", "vetado"]);

/** A situação e a faixa a partir do desfecho, ou null quando ele não decide (sem desfecho, ou só a votação em
 * plenário): aí a situação vem do rito. */
export function situacaoDoDesfecho(
  desfecho: string | null | undefined,
): { rotuloSituacao: string; estagios: EstagioTramitacao[] } | null {
  const rotulo = desfecho ? SITUACAO_POR_DESFECHO[desfecho] : undefined;
  if (!desfecho || !rotulo) return null;
  const emAndamento = SANCAO_EM_ANDAMENTO.has(desfecho);
  return {
    rotuloSituacao: rotulo,
    estagios: ESTAGIOS.map((r, i) => ({
      rotulo: r,
      situacao: i < 4 || !emAndamento ? "concluido" : "ativo",
    })),
  };
}

/** A cor do chip da situação pelo desfecho: lei ou sanção = aprovada; veto mantido = sem lei, a cor da arquivada; à
 * espera do Executivo ou com o veto por apreciar = em tramitação. Null quando o desfecho não decide. */
export function categoriaDoDesfecho(desfecho: string | null | undefined): "aprovada" | "arquivada" | "tram" | null {
  switch (desfecho) {
    case "sancionado":
    case "sancao_tacita":
    case "veto_derrubado":
    case "promulgada":
    case "publicada":
      return "aprovada";
    case "veto_mantido":
      return "arquivada";
    case "autografo_enviado":
    case "vetado":
      return "tram";
    default:
      return null;
  }
}

/** A coluna do quadro de tramitação pelo desfecho: o veto ainda por apreciar volta ao Plenário (é a Câmara que o
 * aprecia); todo o resto saiu das mãos da Câmara — "Concluídas". Null quando o desfecho não decide (vale o rito). */
export function colunaDoDesfecho(desfecho: string | null | undefined): "em-plenario" | "concluidas" | null {
  if (!desfecho || !(desfecho in SITUACAO_POR_DESFECHO)) return null;
  return desfecho === "vetado" ? "em-plenario" : "concluidas";
}

const RESPOSTAS_DO_EXECUTIVO = new Set(["sancionado", "sancao_tacita", "vetado", "veto_mantido", "veto_derrubado"]);

/** O desfecho a partir da rota de pós-aprovação (a ficha interna não recebe `desfecho` na rota da ficha). A mesma regra
 * do SQL da lista (`legislativo/db/proposicao.clj`, `desfecho-depois-do-autografo`): lei publicada, só promulgada, a
 * resposta do Executivo ou a apreciação do veto, o autógrafo ainda sem resposta. Null = não saiu do plenário. */
export function desfechoDaPosAprovacao(pos: PosAprovacaoOut | null | undefined): string | null {
  if (!pos) return null;
  if (pos.norma) return pos.norma.estado === "publicada" ? "publicada" : "promulgada";
  const executivo = pos.tramitacaoExecutiva?.estado;
  if (executivo && RESPOSTAS_DO_EXECUTIVO.has(executivo)) return executivo;
  if (pos.autografo) return "autografo_enviado";
  return null;
}
