// ADR-0024 — POST /api/auth/entrar: a entrada pelo CPF.
//
// O formulário de /entrar (e de /entrar/[ente]) posta o CPF aqui. O BFF pergunta ao backend em quais Câmaras a
// pessoa tem acesso institucional (POST /auth/localizar, CPF no CORPO) e:
//   - uma Câmara (ou a do link /entrar/[ente]) -> 303 direto à tela de senha dela no Keycloak, com `login_hint` = o
//     usuário da pessoa no realm (o campo de usuário vai escondido; ela só digita a senha);
//   - mais de uma -> 303 a /entrar/escolher, com a lista num cookie httpOnly curto (lib/entrada-cpf);
//   - nenhuma, CPF que não confere, limite de tentativas, backend fora -> 303 de volta com `?erro=`.
// O CPF nunca vai para uma URL (log de acesso), nem para cookie, nem para o Keycloak.

import { NextRequest, NextResponse } from "next/server";
import { cpfValido, soDigitos } from "@/lib/cpf";
import { COOKIE_ESCOLHA, ESCOLHA_MAX_AGE_S, codificarEscolha, ehUuid, ipDoCliente } from "@/lib/entrada-cpf";
import { resolveAppOrigin } from "../appOrigin";
import { backendDe, buscarDescoberta, redirecionarAoKeycloak, type OpcoesBackend } from "../pkce";
import { pedidoDeRedirect } from "../redirect";

interface CasaDoBackend {
  "ente-id": string;
  "nome-oficial": string;
}

type Localizado =
  | { tipo: "ok"; casas: { enteId: string; nome: string }[]; hint: string | null }
  | { tipo: "erro"; erro: string };

export async function POST(request: NextRequest): Promise<NextResponse> {
  return entrarPeloCpf(request);
}

async function localizar(cpf: string, ip: string | null, opts?: OpcoesBackend): Promise<Localizado> {
  const f = opts?.fetchImpl ?? fetch;
  const url = `${backendDe(opts)}/auth/localizar`;
  let resp: Response;
  try {
    resp = await f(url, {
      method: "POST",
      headers: { "content-type": "application/json", ...(ip ? { "x-forwarded-for": ip } : {}) },
      body: JSON.stringify({ cpf }),
      cache: "no-store",
    });
  } catch {
    return { tipo: "erro", erro: "indisponivel" };
  }
  if (resp.status === 400) return { tipo: "erro", erro: "cpf-invalido" };
  if (resp.status === 429) return { tipo: "erro", erro: "muitas-tentativas" };
  if (!resp.ok) return { tipo: "erro", erro: "indisponivel" };
  const corpo = (await resp.json().catch(() => null)) as { casas?: unknown; "login-hint"?: unknown } | null;
  if (!corpo || !Array.isArray(corpo.casas)) return { tipo: "erro", erro: "indisponivel" };
  const casas = (corpo.casas as CasaDoBackend[])
    .filter((c) => ehUuid(c?.["ente-id"]) && typeof c?.["nome-oficial"] === "string")
    .map((c) => ({ enteId: c["ente-id"], nome: c["nome-oficial"] }));
  if (casas.length === 0) return { tipo: "ok", casas: [], hint: null };
  // Casa sem um usuário conferido não tem como seguir: resposta fora do formato, falha fechado.
  if (!ehUuid(corpo["login-hint"])) return { tipo: "erro", erro: "indisponivel" };
  return { tipo: "ok", casas, hint: corpo["login-hint"] };
}

export async function entrarPeloCpf(request: NextRequest, opts?: OpcoesBackend): Promise<NextResponse> {
  const origin = resolveAppOrigin(request);
  const form = await request.formData().catch(() => null);
  const campo = (nome: string) => {
    const v = form?.get(nome);
    return typeof v === "string" ? v : "";
  };
  const cpf = soDigitos(campo("cpf"));
  // O `ente` vem do formulário de /entrar/[ente]: só UUID (nunca vira pedaço de caminho torto).
  const enteDoLink = ehUuid(campo("ente")) ? campo("ente") : null;
  const redirectPath = pedidoDeRedirect(campo("redirect") || null, origin);

  const voltar = (erro: string, onde = enteDoLink ? `/entrar/${enteDoLink}` : "/entrar") => {
    const url = new URL(onde, origin);
    url.searchParams.set("erro", erro);
    if (redirectPath) url.searchParams.set("redirect", redirectPath);
    return NextResponse.redirect(url, 303);
  };

  if (!cpfValido(cpf)) return voltar("cpf-invalido");

  const r = await localizar(cpf, ipDoCliente(request.headers), opts);
  if (r.tipo === "erro") return voltar(r.erro);
  if (r.casas.length === 0 || !r.hint) return voltar("sem-acesso");

  const candidatas = enteDoLink ? r.casas.filter((c) => c.enteId === enteDoLink) : r.casas;
  if (candidatas.length === 0) return voltar("sem-acesso-nesta");

  if (candidatas.length > 1) {
    const resp = NextResponse.redirect(new URL("/entrar/escolher", origin), 303);
    resp.cookies.set(COOKIE_ESCOLHA, codificarEscolha({ hint: r.hint, redirectPath, casas: candidatas }), {
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      maxAge: ESCOLHA_MAX_AGE_S,
      path: "/",
    });
    return resp;
  }

  const descoberta = await buscarDescoberta(candidatas[0].enteId, opts);
  if (!descoberta) return voltar("indisponivel");
  return redirecionarAoKeycloak({ origin, descoberta, redirectPath, loginHint: r.hint, status: 303 });
}
