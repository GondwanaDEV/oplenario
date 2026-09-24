// Preserva o ?token= dev entre navegacoes internas (<Link>). Sem isto, qualquer <Link> perde o token de
// dev (useAuth le' de useSearchParams() a cada pagina) — carry da Slice 1, agora corrigido porque esta
// fatia introduz o primeiro loop real de navegacao ida-e-volta (lista -> editor -> lista).
export function comToken(href: string, token: string | null): string {
  if (!token) return href;
  // href que já traz querystring (ex.: `/pauta-convocacao?sessao=…`, da Central da Casa) recebe `&`, não um
  // segundo `?` — que faria o token virar parte do valor do parâmetro anterior.
  return `${href}${href.includes("?") ? "&" : "?"}token=${encodeURIComponent(token)}`;
}
