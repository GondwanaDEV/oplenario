// Rótulos de exibição do domínio de sessões. O backend transporta o ENUM (`ordinaria`, `extraordinaria`,
// …) — sem acento e em minúscula, porque é chave, não texto de tela. O telão vinha renderizando a chave
// crua ("Sessão ordinaria nº 1"), o que numa Casa brasileira é erro de português visível ao público no
// painel do plenário.
//
// Os valores vêm do CHECK de `sessoes.sessao.tipo_sessao` (a FONTE), não de memória:
//   CHECK (tipo_sessao IN ('ordinaria','extraordinaria','solene','secreta','especial'))
// Mesma mecânica de `NOME_TIPO_ITEM` que a tela do plenário já usa para os itens de pauta — é a
// convenção da casa para enum de domínio virando texto, não um conceito novo.
//
// Fallback: chave desconhecida devolve a própria chave. Uma sessão de tipo novo aparece com o nome
// técnico (feio, mas verdadeiro) em vez de sumir da tela.
const NOME_TIPO_SESSAO: Record<string, string> = {
  ordinaria: "ordinária",
  extraordinaria: "extraordinária",
  solene: "solene",
  secreta: "secreta",
  especial: "especial",
};

export function nomeTipoSessao(tipo: string | null | undefined): string {
  if (!tipo) return "";
  return NOME_TIPO_SESSAO[tipo] ?? tipo;
}

// A FASE do rito. Mesma mecânica e mesmo motivo do tipo de sessão acima: o backend transporta a chave
// (`ordem_do_dia`), e a tribuna do plenário renderizava essa chave crua — com o CSS pondo em
// maiúscula, o painel exibia "ORDEM_DO_DIA" ao público. O mapa vivia local em plenario/page.tsx
// (NOME_FASE) e só era aplicado aos itens de pauta; a tribuna não o chamava. Uma fonte só, aqui.
//
// Valores da FONTE — o contrato gerado (lib/contrato-sessoes.gen.ts, campo `fase`):
//   "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao"
const NOME_FASE: Record<string, string> = {
  expediente: "Expediente",
  grande_expediente: "Grande Expediente",
  ordem_do_dia: "Ordem do Dia",
  explicacoes_pessoais: "Explicações Pessoais",
  tribuna_livre_cidadao: "Tribuna Livre",
};

export function nomeFase(fase: string | null | undefined): string {
  if (!fase) return "";
  return NOME_FASE[fase] ?? fase;
}
