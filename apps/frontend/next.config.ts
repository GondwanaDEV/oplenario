import type { NextConfig } from "next";

// Guard fail-closed (review seg/react): NEXT_PUBLIC_* é inlinado no bundle do cliente — um token de dev
// embutido vaza p/ qualquer visitante. A build de produção QUEBRA se a var sobreviver (auto-enforça o carry).
if (process.env.NODE_ENV === "production" && process.env.NEXT_PUBLIC_DEV_TOKEN) {
  throw new Error("NEXT_PUBLIC_DEV_TOKEN não deve existir em produção — authn vem da sessão Keycloak (carry F1.4).");
}

// Proxy same-origin /api/* -> backend (§22.10): o front e a API ficam atrás da MESMA origem (em prod, o
// reverse-proxy; em dev, este rewrite). Mata CORS e deixa o SSE fluir pela mesma origem. BACKEND_URL
// sobrescreve o alvo (default = o Pedestal local em :8888, resources/config.edn).
// CARRY de deploy (review seg MINOR-1): validar BACKEND_URL no pipeline com allowlist de host (sem IPs de
// RFC1918/metadata cloud) — este rewrite serviria de open-proxy se a var fosse injetada com alvo hostil.
const backend = process.env.BACKEND_URL ?? "http://localhost:8888";

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backend}/:path*` }];
  },
};

export default nextConfig;
