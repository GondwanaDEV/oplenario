// Rótulos de exibição das OBRIGAÇÕES de compliance (ADR-0021 fatia 3). O painel (`GET /compliance/painel`)
// transporta a CHAVE do template do motor (`audiencia_metas_fiscais`) — chave, não texto de tela. O painel da
// Mesa, o calendário e a central da Casa mostravam essa chave crua.
//
// A chave continua sendo dado (Invariante 4: a regra de compliance é dado, versionada no catálogo do motor);
// este mapa só dá nome de tela às regras que a PLATAFORMA liga em toda Casa. Valores da FONTE — os templates
// que existem hoje:
//   - `remessa_mensal_sim`: a remessa mensal ao SIM do TCE-CE (catálogo da demo, `demo/compliance.clj`);
//   - `audiencia_metas_fiscais` e `julgamento_contas_prefeito`: o gatilho do host
//     (`apps/backend/src/oplenario/gatilho_compliance.clj`).
//
// Fallback: chave desconhecida devolve a própria chave. Uma regra nova aparece com o nome técnico (feio, mas
// verdadeiro) em vez de sumir ou virar um nome plausível inventado.
const NOME_OBRIGACAO: Record<string, string> = {
  remessa_mensal_sim: "Remessa mensal ao SIM (TCE-CE)",
  audiencia_metas_fiscais: "Audiência de metas fiscais (LRF)",
  julgamento_contas_prefeito: "Julgamento das contas do Prefeito",
};

export function rotularObrigacao(templateChave: string): string {
  return NOME_OBRIGACAO[templateChave] ?? templateChave;
}

// O ciclo da REMESSA ao TCE (`compliance.logic/estados-remessa`, §22.7.8): rascunho -> validada -> submetida ->
// {aceita | rejeitada}. Palavras de tela para o painel da Mesa. Fail-closed como `rotularObrigacao`: estado
// fora do ciclo (vocabulário que cresceu no servidor) aparece com o próprio nome, nunca um rótulo inventado.
const NOME_ESTADO_REMESSA: Record<string, string> = {
  rascunho: "Em preparação",
  validada: "Validada, pronta para enviar",
  submetida: "Enviada, aguardando o TCE",
  aceita: "Aceita pelo TCE",
  rejeitada: "Rejeitada pelo TCE",
};

export function rotularEstadoRemessa(estado: string): string {
  return NOME_ESTADO_REMESSA[estado] ?? estado;
}
