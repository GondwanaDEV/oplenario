/**
 * Só aceita caminho relativo same-origin — nunca URL absoluta, protocol-relative (`//host/...`)
 * nem um valor que o parser WHATWG `URL` resolva para outra origin. Um prefixo de string
 * (`startsWith("/")`) não basta: `/\evil.example` também começa com uma única `/`, mas o parser
 * normaliza `\` para `/` em esquemas especiais e o resultado escapa para `http://evil.example`.
 * A validação precisa olhar para a URL já resolvida, não para o texto bruto.
 *
 * Reusado pelo login (T8) e pelo callback (T9) — defesa em profundidade nos dois pontos onde o
 * `redirect`/`redirectPath` é consumido (phase2-shared-decisions.md §9).
 */
/**
 * Onde cai quem logou sem pedir destino (e para onde um `redirect` inválido/hostil é desviado).
 *
 * Era `/` — a capa pública "front-end em construção", que NÃO é menu de nada: quem logava chegava num beco
 * e só avançava digitando URL na barra de endereço (achado da apresentação guiada, docs/21). `/inicio` é a
 * tela inicial autenticada, que decide a área pela persona. Continua same-origin e relativo: a propriedade
 * de segurança desta função (nunca escapar para outra origin) é a mesma.
 */
export const DESTINO_POS_LOGIN = "/inicio";

export function resolveRedirectPath(candidate: string | null, origin: string): string {
  if (!candidate) return DESTINO_POS_LOGIN;
  try {
    const resolved = new URL(candidate, origin);
    if (resolved.origin !== origin) return DESTINO_POS_LOGIN;
    // Devolve a forma CANONICALIZADA (pathname+search+hash já resolvidos pelo parser WHATWG), não
    // o texto bruto de `candidate` — o texto bruto é o que foi validado, não necessariamente o que
    // é seguro redirecionar; usar a forma resolvida fecha a distância entre "o que validamos" e "o
    // que usamos" (T9 callback consome isto do cookie pkce, onde o texto bruto nunca foi
    // re-examinado por outro código).
    return `${resolved.pathname}${resolved.search}${resolved.hash}`;
  } catch {
    return DESTINO_POS_LOGIN;
  }
}

/**
 * O `redirect` PEDIDO explicitamente por quem iniciou o login — ou `null` quando não houve pedido (ou
 * quando o pedido não é same-origin e foi descartado).
 *
 * Existe separado de `resolveRedirectPath` porque o callback precisa DISTINGUIR "a pessoa pediu /inicio"
 * de "ninguém pediu nada": só no segundo caso ele escolhe o destino pelo papel. `resolveRedirectPath`
 * colapsa os dois em `DESTINO_POS_LOGIN`, o que é o certo para ele (defesa em profundidade, sempre devolve
 * um caminho utilizável) e errado aqui.
 */
export function pedidoDeRedirect(candidate: string | null, origin: string): string | null {
  if (!candidate) return null;
  try {
    const resolved = new URL(candidate, origin);
    if (resolved.origin !== origin) return null; // hostil/externo: descarta e deixa o papel decidir
    return `${resolved.pathname}${resolved.search}${resolved.hash}`;
  } catch {
    return null;
  }
}

/**
 * Para onde cada persona vai quando NÃO pediu destino: a home DELA.
 *
 * O vereador tem uma home própria e mais rica (`/vereador`: pareceres, ciências, sessões) e o chrome do app
 * dele (topo + tabbar) mora no layout do grupo `(vereador)` — mandá-lo para `/inicio`, que vive em
 * `(interno)`, o deixaria numa página sem casca nenhuma e a um clique de distância da tela que ele
 * realmente quer. `secretario` vence quem acumula papéis (é a persona com mais superfície).
 *
 * Quem não tem papel de trabalho cai em `/inicio`, que trata esse caso sem prometer o que não existe.
 */
export function destinoPorPapeis(papeis: string[]): string {
  if (papeis.includes("secretario")) return DESTINO_POS_LOGIN;
  if (papeis.includes("vereador")) return "/vereador";
  return DESTINO_POS_LOGIN;
}
