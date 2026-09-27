// ADR-0016 — a descoberta do realm do OPERADOR (`GET ${backend}/operacao/descoberta`), servidor-a-servidor.
// Diferente do login das Casas, o BFF do console NÃO guarda realm/baseUrl/clientId em cookie: busca a descoberta
// de novo no callback e no logout (fonte confiável, sem valor de cookie virando URL). Fail-closed: forma inválida
// ou backend fora do ar -> null.

export interface DescobertaOperacao {
  realm: string;
  baseUrl: string;
  clientId: string;
}

const REALM_OK = /^[a-z0-9-]{1,64}$/;
const CLIENT_OK = /^[A-Za-z0-9_-]{1,64}$/;

export function backendUrl(opts?: { backend?: string }): string {
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }
  return opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
}

export async function buscarDescobertaOperacao(
  backend: string,
  f: typeof fetch = fetch,
): Promise<DescobertaOperacao | null> {
  try {
    const r = await f(`${backend}/operacao/descoberta`, { cache: "no-store" });
    if (!r.ok) return null;
    const d = (await r.json()) as Record<string, unknown>;
    const realm = d.realm;
    const baseUrl = d["base-url"];
    const clientId = d["client-id"];
    if (typeof realm !== "string" || !REALM_OK.test(realm)) return null;
    if (typeof clientId !== "string" || !CLIENT_OK.test(clientId)) return null;
    if (typeof baseUrl !== "string") return null;
    const u = new URL(baseUrl);
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    return { realm, baseUrl, clientId };
  } catch {
    return null;
  }
}

/** O cookie do console. Outro nome que o `sessao` das Casas: um nunca abre o outro (ADR-0016). */
export const COOKIE_OPERACAO = "sessao_operacao";
export const SEGREDO_VALIDO = /^[A-Za-z0-9_-]{1,128}$/;

/** Só caminhos do próprio console viram destino depois do login (nunca um host externo nem uma área de Casa). */
export function destinoDoConsole(pedido: string | null): string {
  if (!pedido) return "/operacao";
  const doConsole = pedido === "/operacao" || pedido.startsWith("/operacao/") || pedido.startsWith("/operacao?");
  if (!doConsole || pedido.includes("\\")) return "/operacao";
  if (pedido.startsWith("/operacao/entrar")) return "/operacao";
  return pedido;
}
