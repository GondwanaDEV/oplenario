// Validadores compartilhados dos valores de descoberta do Keycloak (realm/baseUrl/clientId) que chegam
// via cookie httpOnly (`pkce` no callback, `sessao_kc` no logout). Defesa em profundidade: os cookies são
// httpOnly+Secure (escritos pelo próprio BFF a partir da descoberta confiável), mas NUNCA interpolamos um
// valor de cookie numa URL/request server-side sem validar a forma primeiro — um cookie forjado (cookie
// injection, MITM sem TLS, bug futuro de escrita de cookie) não deve virar path-injection no token-exchange
// nem redirect p/ host/esquema arbitrário. Fonte única p/ callback (T9) e logout (T10) — antes cada rota
// tinha (ou faltava) a sua cópia (achado da revisão whole-branch: `as`-cast sem validação no callback).

export const REALM_VALIDO = /^ente-[0-9a-f-]{36}$/i;
export const CLIENT_ID_VALIDO = /^[A-Za-z0-9_-]{1,64}$/;

export function baseUrlValido(v: unknown): v is string {
  if (typeof v !== "string") return false;
  try {
    const u = new URL(v);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}

// Valida os 3 campos públicos de descoberta de um payload de cookie já parseado. Devolve o trio tipado
// (narrowing) ou null (fail-closed) — o caller decide o fallback (paraLogin/logout local).
export function validarDescobertaKc(
  campos: { baseUrl?: unknown; realm?: unknown; clientId?: unknown },
): { baseUrl: string; realm: string; clientId: string } | null {
  const { baseUrl, realm, clientId } = campos;
  if (!baseUrlValido(baseUrl)) return null;
  if (typeof realm !== "string" || !REALM_VALIDO.test(realm)) return null;
  if (typeof clientId !== "string" || !CLIENT_ID_VALIDO.test(clientId)) return null;
  return { baseUrl, realm, clientId };
}
