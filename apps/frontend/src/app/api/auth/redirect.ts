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
export function resolveRedirectPath(candidate: string | null, origin: string): string {
  if (!candidate) return "/";
  try {
    const resolved = new URL(candidate, origin);
    if (resolved.origin !== origin) return "/";
    // Devolve a forma CANONICALIZADA (pathname+search+hash já resolvidos pelo parser WHATWG), não
    // o texto bruto de `candidate` — o texto bruto é o que foi validado, não necessariamente o que
    // é seguro redirecionar; usar a forma resolvida fecha a distância entre "o que validamos" e "o
    // que usamos" (T9 callback consome isto do cookie pkce, onde o texto bruto nunca foi
    // re-examinado por outro código).
    return `${resolved.pathname}${resolved.search}${resolved.hash}`;
  } catch {
    return "/";
  }
}
