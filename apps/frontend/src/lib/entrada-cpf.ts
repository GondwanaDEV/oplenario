// ADR-0025 — a entrada pelo CPF, as peças puras.
//
// Quem tem acesso a MAIS DE UMA Câmara escolhe qual depois de digitar o CPF. A lista e o usuário já conferido (o
// identidade-id, `hint`) ficam num cookie httpOnly curto (`entrar_escolha`, 5 min) entre o POST do CPF e o clique na
// Câmara: o CPF nunca vai para a URL nem para o cookie, e o identidade-id nunca vai para a URL do app (só para a do
// Keycloak, como `login_hint`). Cookie adulterado só afeta o próprio login de quem o adulterou: a senha continua
// sendo conferida pelo Keycloak da Casa.

export const COOKIE_ESCOLHA = "entrar_escolha";
export const ESCOLHA_MAX_AGE_S = 300;

export interface CasaParaEntrar {
  enteId: string;
  nome: string;
}

export interface Escolha {
  hint: string;
  redirectPath: string | null;
  casas: CasaParaEntrar[];
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function ehUuid(v: unknown): v is string {
  return typeof v === "string" && UUID.test(v);
}

/** Só caminho relativo do próprio site — nunca `//host`, `/\host` nem URL absoluta. */
function caminhoProprio(v: unknown): string | null {
  if (typeof v !== "string" || !v.startsWith("/") || v.startsWith("//") || v.startsWith("/\\")) return null;
  return v;
}

/** O valor do cookie (JSON puro: o Next codifica o cookie na resposta e decodifica no pedido, como faz com `pkce`). */
export function codificarEscolha(e: Escolha): string {
  return JSON.stringify(e);
}

/** O cookie de volta, conferido campo a campo. Qualquer coisa fora do formato -> null. */
export function lerEscolha(bruto: string | undefined): Escolha | null {
  if (!bruto) return null;
  let v: unknown;
  try {
    v = JSON.parse(bruto);
  } catch {
    return null;
  }
  if (!v || typeof v !== "object") return null;
  const { hint, redirectPath, casas } = v as Record<string, unknown>;
  if (!ehUuid(hint) || !Array.isArray(casas) || casas.length === 0 || casas.length > 50) return null;
  const lidas: CasaParaEntrar[] = [];
  for (const c of casas) {
    if (!c || typeof c !== "object") return null;
    const { enteId, nome } = c as Record<string, unknown>;
    if (!ehUuid(enteId) || typeof nome !== "string" || nome.length === 0 || nome.length > 200) return null;
    lidas.push({ enteId, nome });
  }
  return { hint, redirectPath: caminhoProprio(redirectPath), casas: lidas };
}

/**
 * O IP de quem pediu, como a borda (o proxy reverso) o pôs no `X-Forwarded-For`: o primeiro da lista — a mesma regra
 * do backend (`auditoria.logic/ip-de`). O BFF repassa ao backend para o limite de tentativas por IP valer por pessoa,
 * não pelo servidor do Next. Só IPv4/IPv6 literais; qualquer outra coisa -> null (nada é repassado).
 */
export function ipDoCliente(headers: Headers): string | null {
  const primeiro = headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (!primeiro || !/^[0-9A-Fa-f:.]{2,45}$/.test(primeiro)) return null;
  return primeiro;
}
