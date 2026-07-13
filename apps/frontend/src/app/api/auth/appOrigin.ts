import type { NextRequest } from "next/server";

/**
 * Origin PÚBLICA (externa) desta instância — usada para montar `redirect_uri` enviado ao Keycloak
 * (authorize no login, token exchange no callback) e para resolver o `redirect` same-origin.
 *
 * `APP_ORIGIN` é a origin canônica pública (setada em deploy atrás de proxy/reverse-proxy); sem ela
 * (dev local, `next dev`), cai para `request.nextUrl.origin`.
 *
 * IMPORTANTE: login (authorize) e callback (T9, token exchange) precisam produzir o MESMO valor —
 * ambos usam este helper para garantir isso byte-a-byte (o Keycloak valida `redirect_uri` por
 * igualdade estrita contra o client registrado — ver phase2-shared-decisions.md §8).
 */
export function resolveAppOrigin(request: NextRequest): string {
  return process.env.APP_ORIGIN ?? request.nextUrl.origin;
}
