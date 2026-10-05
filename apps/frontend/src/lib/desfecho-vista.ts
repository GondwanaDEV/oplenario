// O desfecho da matéria depois do plenário, no portal (docs/16, retriagem linha 18). Nenhum desses atos move o
// `estado` do rito, então a ficha pública dizia "Aguardando pauta" de matéria sancionada e até publicada como lei.
//
// O selo só muda a partir do AUTÓGRAFO: a votação em plenário fica na frase "Última votação em plenário: …" da ficha
// (#157), e o selo continua no rito — numa matéria de dois turnos, "Aguardando pauta" depois do 1º turno é verdade.
// Depois do autógrafo não há ambiguidade: a matéria saiu do plenário e foi ao Executivo.
//
// Vocabulário FECHADO (o rito do Executivo é enum no backend, não texto livre por Casa); os mesmos atos entram na
// linha do tempo pública com os rótulos de `transparencia/logic/desfecho.clj`.

import type { EstagioTramitacao } from "./tramitacao-vista";

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
