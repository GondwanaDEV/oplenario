import type { NextConfig } from "next";

// Guard fail-closed (review seg/react): NEXT_PUBLIC_* é inlinado no bundle do cliente — um token de dev
// embutido vaza p/ qualquer visitante. A build de produção QUEBRA se a var sobreviver (auto-enforça o carry).
if (process.env.NODE_ENV === "production" && process.env.NEXT_PUBLIC_DEV_TOKEN) {
  throw new Error("NEXT_PUBLIC_DEV_TOKEN não deve existir em produção — authn vem da sessão Keycloak (carry F1.4).");
}

// Fail-fast em produção (revisão whole-branch, converge sec#1+ts#1): sem APP_ORIGIN o BFF cai p/
// request.nextUrl.origin (o Host header, spoofável) como âncora de validação do redirect pós-login —
// tornaria o check same-origin tautológico -> open redirect. Sem KEYCLOAK_INTERNAL_URL o callback cai p/
// pkce.baseUrl (valor de cookie) no token-exchange server-side -> SSRF se o cookie for forjado. Ambos são
// obrigatórios em prod; o deploy QUEBRA se faltarem (auto-enforça o carry, mesmo padrão do dev-token acima).
if (process.env.NODE_ENV === "production") {
  if (!process.env.APP_ORIGIN) {
    throw new Error("APP_ORIGIN é obrigatório em produção (âncora do redirect pós-login; sem ela o Host header vira baseline).");
  }
  if (!process.env.KEYCLOAK_INTERNAL_URL) {
    throw new Error("KEYCLOAK_INTERNAL_URL é obrigatório em produção (token-exchange server-side; sem ela cairia no baseUrl do cookie).");
  }
}

// Proxy same-origin /api/* -> backend (§22.10): o front e a API ficam atrás da MESMA origem (em prod, o
// reverse-proxy; em dev, este rewrite). Mata CORS e deixa o SSE fluir pela mesma origem. BACKEND_URL
// sobrescreve o alvo (default = o Pedestal local em :8888, resources/config.edn).
const backend = process.env.BACKEND_URL ?? "http://localhost:8888";

// Validação de host do alvo em produção (review seg MAJOR/MINOR — converte o carry em código): um BACKEND_URL
// comprometido apontando p/ metadata cloud (169.254.169.254) ou faixa interna vira SSRF (pivô lateral) via este
// proxy. Barramos metadata + link-local + RFC1918; loopback é PERMITIDO (é o backend same-host legítimo e o
// default de build/CI). Em k8s o alvo é DNS de service (passa). Fail-fast no build se a env vier hostil.
// CARRY remanescente (path-space): este rewrite repassa QUALQUER /api/* ao backend (inclui /saude) — é o mesmo
// posture do FE.1 (dev-only), mitigado pelo reverse-proxy de prod + auth do Pedestal. Restringir paths = BFF
// allowlist (escopo diferido §15); validar no pipeline de infra que /api/saude não responde da origem pública.
if (process.env.NODE_ENV === "production") {
  let u: URL;
  try { u = new URL(backend); } catch { throw new Error("BACKEND_URL inválida (não é uma URL)."); }
  if (!/^https?:$/.test(u.protocol)) throw new Error("BACKEND_URL deve usar http(s).");
  const h = u.hostname;
  const hostProibido =
    h === "metadata.google.internal" || /^169\.254\./.test(h) ||
    /^10\./.test(h) || /^192\.168\./.test(h) || /^172\.(1[6-9]|2[0-9]|3[01])\./.test(h);
  if (hostProibido) throw new Error(`BACKEND_URL com host não permitido em produção: ${h}`);
}

const nextConfig: NextConfig = {
  // standalone: runtime da imagem de produção carrega só o server.js + deps podadas, sem
  // precisar de node_modules completo no container final (§22.9 Eixo 5, Docker em todo deploy).
  output: "standalone",
  async rewrites() {
    // `fallback` (não array simples = afterFiles): o proxy catch-all roda DEPOIS de todas as rotas do
    // filesystem, INCLUSIVE as dinâmicas. Sem isso, `/api/:path*` (afterFiles) atropelava o Route Handler
    // dinâmico de SSE (`app/api/sessoes/[id]/plenario/route.ts`) — afterFiles precede rotas dinâmicas.
    // Assim o handler de stream vence p/ o seu path; o resto de /api/* cai no proxy (mata CORS no dev).
    return { fallback: [{ source: "/api/:path*", destination: `${backend}/:path*` }] };
  },
};

export default nextConfig;
