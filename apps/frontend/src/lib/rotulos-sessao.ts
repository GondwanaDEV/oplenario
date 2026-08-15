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
