// View-model puro da "faixa de azulejo da tramitação" (Task 0.5, Fatia A2.0 — Portal do Cidadão).
//
// VOCABULÁRIO REAL (confirmado por grep em apps/backend/src/oplenario/legislativo/logic.clj +
// db/tramitacao.clj, 04/07/2026): `estado` de proposicao é `:string` LIVRE — a máquina de tramitação é
// TEMPLATE-DRIVEN POR CÂMARA (F3.3, comentário do model Proposicao: "a maquina fina de tramitacao e'
// template-driven por camara, nao um enum cravado"). Não existe um enum fechado no código para os
// estados de proposição (ao contrário de estados-emenda/estados-votacao/etc., que SÃO enums fixos). Os
// únicos nomes de estado que aparecem nos fixtures reais de teste do backend
// (tramitacao_db_test.clj: "protocolada"→"em_comissoes"→"em_pauta"→"arquivada" [terminal];
// portal_test.clj: "protocolada"→"em_comissoes") são explicitamente marcados "FIXTURE ilustrativa (nao
// regulacao real)" — o regimento real de cada câmara é [GAP] (§22.7.5).
//
// Este view-model, portanto, NÃO pode assumir um vocabulário fechado. O mapa abaixo cobre o rito
// ILUSTRATIVO de 5 estágios que o design-system usa (Protocolo/Comissões/1º turno/2º turno/Sanção,
// portal-cidadao.html:432-453) mais os nomes de fixture observados — é um mapeamento de MELHOR ESFORÇO
// para a demo/seed (Task 1.4), não uma verdade regulatória. QUALQUER estado fora deste mapa cai no
// fallback FAIL-CLOSED da Global Constraint do plano: nunca lança, degrada para a faixa mínima honesta.

export type EstagioTramitacao = { rotulo: string; situacao: "concluido" | "ativo" | "pendente" };

const ESTAGIOS_BASE = ["Protocolo", "Comissões", "1º turno", "2º turno", "Sanção"] as const;

// índice (0-based, em ESTAGIOS_BASE) do estágio ATIVO para cada estado intermediário conhecido.
const INDICE_ATIVO_POR_ESTADO: Record<string, number> = {
  protocolada: 0,
  em_comissoes: 1,
  em_pauta: 2, // "em pauta" = 1º turno em andamento, no rito ilustrativo do design-system
  primeiro_turno: 2,
  segundo_turno: 3,
  em_sancao: 4,
};

const ROTULO_SITUACAO_POR_ESTADO: Record<string, string> = {
  protocolada: "Protocolado",
  em_comissoes: "Em comissões",
  em_pauta: "Em pauta",
  primeiro_turno: "Em 1º turno",
  segundo_turno: "Em 2º turno",
  em_sancao: "Em sanção",
};

const FAIXA_MINIMA: EstagioTramitacao[] = [{ rotulo: "Protocolo", situacao: "ativo" }];

export function derivarTramitacao(estado: string): {
  estagios: EstagioTramitacao[];
  rotuloSituacao: string;
} {
  // terminal de sucesso: todo o rito se completou.
  if (estado === "aprovada") {
    return {
      estagios: ESTAGIOS_BASE.map((rotulo) => ({ rotulo, situacao: "concluido" as const })),
      rotuloSituacao: "Aprovado",
    };
  }

  // terminal de arquivamento: sabemos que passou pelo protocolo, mas não até onde avançou antes de
  // arquivar (o estado sozinho não carrega histórico) — honesto é não fingir progresso além disso.
  if (estado === "arquivada") {
    return {
      estagios: [{ rotulo: "Protocolo", situacao: "concluido" }],
      rotuloSituacao: "Arquivada",
    };
  }

  const indiceAtivo = INDICE_ATIVO_POR_ESTADO[estado];
  if (indiceAtivo === undefined) {
    // fail-closed: estado fora do vocabulário ilustrativo (ex. vocabulário real de um tenant via
    // template) — nunca lança; degrada para a faixa mínima honesta com o estado cru como rótulo.
    return { estagios: FAIXA_MINIMA, rotuloSituacao: estado };
  }

  return {
    estagios: ESTAGIOS_BASE.map((rotulo, i) => ({
      rotulo,
      situacao: i < indiceAtivo ? "concluido" : i === indiceAtivo ? "ativo" : "pendente",
    })),
    rotuloSituacao: ROTULO_SITUACAO_POR_ESTADO[estado] ?? estado,
  };
}
