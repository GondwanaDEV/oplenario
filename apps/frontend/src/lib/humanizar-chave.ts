// A chave de cadastro que a tela não conhece (estado novo do backend, tipo de outra versão) não pode ir
// crua para a pessoa: "em_analise_x" não é palavra. Este é o último recurso do rótulo — os valores
// CONHECIDOS continuam vindo da tabela do próprio módulo; só o desconhecido passa por aqui.

/** `aguardando_orgao` → `Aguardando orgao`. Sem valor útil (vazio, só separador, ausente), devolve `vazio`. */
export function humanizarChave(chave: string | null | undefined, vazio = "Não informado"): string {
  const palavras = (chave ?? "").replace(/[_-]+/g, " ").trim().replace(/\s+/g, " ").toLowerCase();
  if (palavras === "") return vazio;
  return palavras.charAt(0).toUpperCase() + palavras.slice(1);
}
