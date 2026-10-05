// GET /api/auth/conta — leva a pessoa à página de conta do Keycloak da Casa dela ("Personal info"), onde ela
// troca o PRÓPRIO e-mail de acesso. O `admin_ente` não troca o e-mail de quem já entrou (seria tomar a conta);
// quem já entrou troca ali (docs/16, achado B de 05/10/2026). A página é do Keycloak, em inglês.
//
// O DESTINO NUNCA VEM DE FORA. Nada do pedido entra na URL: nem parâmetro, nem `Host`, `X-Forwarded-*`,
// `Referer` ou `Origin` (esta rota não lê nenhum deles). O destino é montado por partes fixas:
//   - BASE: `KEYCLOAK_PUBLIC_URL`, a configuração do servidor que o login e o logout já usam. Ausente, vazia,
//     malformada, com credencial/query/fragmento, ou que não seja https em produção: 404 (fail-closed). Em modo
//     dev (token de dev, sem Keycloak): 404.
//   - ENTE: sai da SESSÃO autenticada, perguntada ao backend (`GET /eu` com o cookie `sessao`) — não da query. A
//     sessão do gov.br (vínculo `cidadao`) não tem conta no realm da Casa: 404.
//   - REALM: o do cookie `sessao_kc` (a descoberta que o login gravou), validado pelo formato exato
//     `ente-<uuid>` e igual ao ente da sessão; entra no caminho já codificado.
//   - O issuer do cookie (`baseUrl`) tem de ter EXATAMENTE a origem configurada (`new URL(x).origin ===`, nunca
//     prefixo nem `includes`): `https://kc.exemplo.com.atacante.net` e `https://kc.exemplo.com@atacante.net`
//     não passam.
// Qualquer passo que não se consiga validar responde 404 em texto — nunca segue adiante, nunca chuta um destino.
import { NextRequest, NextResponse } from "next/server";
import { modoDev } from "@/lib/modo";
import { validarDescobertaKc } from "../kc-cookie";

// Mesma forma do segredo opaco que o logout confere antes de pô-lo num header para o backend.
const SEGREDO_VALIDO = /^[A-Za-z0-9_-]{1,128}$/;
// UUID inteiro (kc-cookie.REALM_VALIDO aceita `[0-9a-f-]{36}` solto; aqui o formato é exato).
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function indisponivel(): NextResponse {
  return new NextResponse(
    "Não foi possível abrir a página de conta desta sessão. Saia do sistema e entre de novo.",
    { status: 404, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } },
  );
}

/** A base do Keycloak da configuração do servidor, ou `null` se não for segura. */
function baseConfigurada(): URL | null {
  const bruto = process.env.KEYCLOAK_PUBLIC_URL?.trim();
  if (!bruto) return null;
  let base: URL;
  try {
    base = new URL(bruto);
  } catch {
    return null;
  }
  if (base.protocol !== "https:") return null; // em produção só https (modo dev já saiu antes)
  if (!base.hostname || base.username || base.password || base.search || base.hash) return null;
  return base;
}

/** A sessão do servidor (não do pedido): o ente e o tipo de vínculo, perguntados ao backend. */
async function sessaoDoBackend(
  segredo: string,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<{ enteId: string; tipoVinculo: string } | null> {
  const backend = opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
  const f = opts?.fetchImpl ?? fetch;
  try {
    const r = await f(`${backend}/eu`, {
      headers: { cookie: `sessao=${segredo}`, accept: "application/json" },
      cache: "no-store",
    });
    if (!r.ok) return null;
    const ator = ((await r.json()) as { ator?: Record<string, unknown> })?.ator;
    const enteId = ator?.["ente-id"];
    const tipoVinculo = ator?.["tipo-vinculo"];
    if (typeof enteId !== "string" || !UUID.test(enteId)) return null;
    if (typeof tipoVinculo !== "string" || tipoVinculo === "") return null;
    return { enteId: enteId.toLowerCase(), tipoVinculo };
  } catch {
    return null;
  }
}

// GET é um wrapper fino com a assinatura EXATA que o Next.js espera (sem 2º parâmetro) — mesmo padrão das
// outras rotas de /api/auth.
export async function GET(request: NextRequest): Promise<NextResponse> {
  return abrirContaDeAcesso(request);
}

export async function abrirContaDeAcesso(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  // `opts` é injeção de dependência só de teste; em produção seria superfície de SSRF (mesmo guard do login).
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }

  // 1. modo dev (token de dev) não tem Keycloak por trás.
  if (modoDev()) return indisponivel();

  // 2. a base é a da configuração do servidor, e tem de ser segura.
  const base = baseConfigurada();
  if (!base) return indisponivel();

  // 3. sem sessão não há conta; o segredo tem de ter a forma conhecida antes de ir num header.
  const segredo = request.cookies.get("sessao")?.value;
  if (!segredo || !SEGREDO_VALIDO.test(segredo)) return indisponivel();

  // 4. a descoberta do login (cookie httpOnly): forma validada e issuer com EXATAMENTE a origem configurada.
  let descoberta: ReturnType<typeof validarDescobertaKc> = null;
  try {
    const bruto = JSON.parse(request.cookies.get("sessao_kc")?.value ?? "");
    if (typeof bruto === "object" && bruto !== null) descoberta = validarDescobertaKc(bruto as Record<string, unknown>);
  } catch {
    return indisponivel();
  }
  if (!descoberta) return indisponivel();
  let issuer: URL;
  try {
    issuer = new URL(descoberta.baseUrl);
  } catch {
    return indisponivel();
  }
  if (issuer.origin !== base.origin) return indisponivel();

  // 5. o ente vem da SESSÃO; quem entrou pelo gov.br (cidadão) não tem conta no realm da Casa.
  const sessao = await sessaoDoBackend(segredo, opts);
  if (!sessao || sessao.tipoVinculo === "cidadao") return indisponivel();

  // 6. o realm do cookie é o da Casa da sessão — formato exato, ente-<uuid>, e igual.
  const realm = descoberta.realm.toLowerCase();
  if (!realm.startsWith("ente-") || !UUID.test(realm.slice("ente-".length)) || realm !== `ente-${sessao.enteId}`) {
    return indisponivel();
  }

  // 7. caminho por partes fixas sobre a base configurada; o realm entra codificado.
  const destino = new URL(base.origin);
  destino.pathname = `${base.pathname.replace(/\/+$/, "")}/realms/${encodeURIComponent(realm)}/account`;
  if (destino.origin !== base.origin) return indisponivel(); // cinto e suspensório

  const resposta = NextResponse.redirect(destino);
  resposta.headers.set("cache-control", "no-store");
  return resposta;
}
