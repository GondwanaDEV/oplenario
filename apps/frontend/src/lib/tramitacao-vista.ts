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

// Estado de ESPERA entre dois estágios: nenhum está em andamento. Chave = estado; valor = quantos estágios
// (a partir do Protocolo) já se completaram. `aguardando_pauta` é o rito ordinário da Casa de demonstração
// (acervo.clj: em_comissoes -> concluir_comissoes -> aguardando_pauta -> incluir_pauta -> em_pauta): as
// comissões já deram o parecer e a matéria espera entrar numa pauta. Antes ele caía no fail-closed e a
// faixa "Onde está a matéria" voltava para "Protocolo".
const CONCLUIDOS_ATE_POR_ESTADO_DE_ESPERA: Record<string, number> = {
  aguardando_pauta: 2,
};

// ÚNICA fonte de rótulo de estado de proposição — a ficha, a lista, o quadro de tramitação e o painel da
// Mesa leem daqui (`rotularEstado`). Terminais incluídos, para o quadro rotular o cartão sem outro mapa.
const ROTULO_SITUACAO_POR_ESTADO: Record<string, string> = {
  protocolada: "Protocolado",
  em_comissoes: "Em comissões",
  aguardando_pauta: "Aguardando pauta",
  em_pauta: "Em pauta",
  primeiro_turno: "Em 1º turno",
  segundo_turno: "Em 2º turno",
  em_sancao: "Em sanção",
  aprovada: "Aprovado",
  arquivada: "Arquivada",
};

// Estado fora do vocabulário conhecido: a faixa não sabe em que etapa a matéria está e NÃO pode apontar uma
// (já apontou "Protocolo" para matéria que esperava pauta). Um único bloco neutro, sem posição no rito.
const FAIXA_NEUTRA: EstagioTramitacao[] = [{ rotulo: "Em tramitação", situacao: "ativo" }];

// Último recurso de rótulo. `estado` de proposição é string LIVRE, definida por template POR CÂMARA
// (§22.4 / Invariante 4: regra é dado, não código) — então NENHUM mapa fixo em código vai cobrir o
// vocabulário de um tenant real, e o ramo de fallback é o caso COMUM em produção, não a exceção.
// Devolver a chave crua fazia `aguardando_pauta` (e, em caixa alta pelo CSS do painel da Mesa,
// `AGUARDANDO_PAUTA`) aparecer na tela para o usuário final. Humanizar não inventa semântica nenhuma:
// só troca o separador e sobe a inicial, o que é sempre melhor que a chave e nunca é errado.
function humanizarEstado(estado: string): string {
  const limpo = estado.replace(/[_-]+/g, " ").trim();
  if (limpo.length === 0) return estado;
  return limpo.charAt(0).toUpperCase() + limpo.slice(1);
}

/** Rótulo de um estado de proposição, em palavras. Única fonte para ficha, lista, quadro e painel da Mesa. */
export function rotularEstado(estado: string): string {
  return ROTULO_SITUACAO_POR_ESTADO[estado] ?? humanizarEstado(estado);
}

export function derivarTramitacao(estado: string): {
  estagios: EstagioTramitacao[];
  rotuloSituacao: string;
} {
  // terminal de sucesso: todo o rito se completou.
  if (estado === "aprovada") {
    return {
      estagios: ESTAGIOS_BASE.map((rotulo) => ({ rotulo, situacao: "concluido" as const })),
      rotuloSituacao: rotularEstado(estado),
    };
  }

  // terminal de arquivamento: sabemos que passou pelo protocolo, mas não até onde avançou antes de
  // arquivar (o estado sozinho não carrega histórico) — honesto é não fingir progresso além disso.
  if (estado === "arquivada") {
    return {
      estagios: [{ rotulo: "Protocolo", situacao: "concluido" }],
      rotuloSituacao: rotularEstado(estado),
    };
  }

  const concluidosAte = CONCLUIDOS_ATE_POR_ESTADO_DE_ESPERA[estado];
  if (concluidosAte !== undefined) {
    return {
      estagios: ESTAGIOS_BASE.map((rotulo, i) => ({
        rotulo,
        situacao: i < concluidosAte ? ("concluido" as const) : ("pendente" as const),
      })),
      rotuloSituacao: rotularEstado(estado),
    };
  }

  const indiceAtivo = INDICE_ATIVO_POR_ESTADO[estado];
  if (indiceAtivo === undefined) {
    // fail-closed: estado fora do vocabulário ilustrativo (ex. vocabulário real de um tenant via
    // template) — nunca lança e nunca aponta uma etapa: a faixa é só "Em tramitação". O rótulo da situação
    // é HUMANIZADO, nunca a chave crua: degradar não obriga a expor vocabulário de banco ao usuário.
    return { estagios: FAIXA_NEUTRA, rotuloSituacao: rotularEstado(estado) };
  }

  return {
    estagios: ESTAGIOS_BASE.map((rotulo, i) => ({
      rotulo,
      situacao: i < indiceAtivo ? "concluido" : i === indiceAtivo ? "ativo" : "pendente",
    })),
    rotuloSituacao: rotularEstado(estado),
  };
}

// Review A2.1 (item 3, a11y): rótulo ARIA completo da AzulejoFaixa. `role="img"` no SVG esconde os
// <text> por-estágio de leitores de tela — sem isto, AT perde a progressão concluído/atual/pendente
// que usuários videntes veem no grafismo (só sobraria "Tramitação de X: situação."). Agrupa por
// `situacao` e monta uma cláusula por grupo presente; grupos vazios são omitidos (fail-closed da
// faixa mínima cai aqui de graça — 1 único estágio ativo vira só a cláusula "atual").
export function descreverFaixa(ref: string, estagios: EstagioTramitacao[]): string {
  const rotulosPor = (situacao: EstagioTramitacao["situacao"]) =>
    estagios.filter((e) => e.situacao === situacao).map((e) => e.rotulo);

  const concluidos = rotulosPor("concluido");
  const ativos = rotulosPor("ativo");
  const pendentes = rotulosPor("pendente");

  const clausulas: string[] = [];
  if (concluidos.length > 0) clausulas.push(`concluídos ${concluidos.join(", ")}`);
  if (ativos.length > 0) clausulas.push(`${ativos.length > 1 ? "atuais" : "atual"} ${ativos.join(", ")}`);
  if (pendentes.length > 0) clausulas.push(`pendente ${pendentes.join(", ")}`);

  return clausulas.length > 0 ? `Tramitação de ${ref}: ${clausulas.join("; ")}.` : `Tramitação de ${ref}.`;
}
