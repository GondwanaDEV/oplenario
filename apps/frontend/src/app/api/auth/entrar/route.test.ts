import { NextRequest } from "next/server";
import { describe, it, expect, vi } from "vitest";
import { entrarPeloCpf } from "./route";
import { COOKIE_ESCOLHA, lerEscolha } from "@/lib/entrada-cpf";

// ADR-0024 — POST /api/auth/entrar: a pessoa digita o CPF numa tela do O Plenário e cai direto na senha da Câmara
// dela. O BFF pergunta ao backend (POST /auth/localizar) e começa o PKCE com `login_hint` = o usuário dela no realm.

const ORIGIN = "http://localhost:3000";
const CPF = "52998224725";
const HINT = "0eabd6df-d0cc-40bb-a0ca-043027cb3a1f";
const A = "10000000-0000-0000-0000-000000000001";
const B = "20000000-0000-0000-0000-000000000002";

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;

const descoberta = (ente: string) => ({
  "ente-id": ente,
  realm: `ente-${ente}`,
  "base-url": "http://localhost:8090",
  "client-id": "oplenario-web",
});

/** Backend fake: /auth/localizar devolve `localizar`; /auth/descoberta/:ente devolve a descoberta. */
function backend(localizar: { status: number; corpo?: unknown } | "rede") {
  return vi.fn<FetchFn>(async (url) => {
    const u = String(url);
    if (u.endsWith("/auth/localizar")) {
      if (localizar === "rede") throw new TypeError("fetch failed");
      return new Response(JSON.stringify(localizar.corpo ?? {}), { status: localizar.status });
    }
    const m = u.match(/\/auth\/descoberta\/([^/]+)$/);
    if (m) return new Response(JSON.stringify(descoberta(m[1])), { status: 200 });
    throw new Error(`fetch inesperado: ${u}`);
  });
}

function post(campos: Record<string, string>, headers: Record<string, string> = {}) {
  return new NextRequest(new URL("/api/auth/entrar", ORIGIN), {
    method: "POST",
    body: new URLSearchParams(campos),
    headers: { "content-type": "application/x-www-form-urlencoded", ...headers },
  });
}

const umaCasa = { status: 200, corpo: { casas: [{ "ente-id": A, "nome-oficial": "Câmara Municipal de Fortaleza" }], "login-hint": HINT } };
const duasCasas = {
  status: 200,
  corpo: {
    casas: [
      { "ente-id": A, "nome-oficial": "Câmara Municipal de Baturité" },
      { "ente-id": B, "nome-oficial": "Câmara Municipal de Russas" },
    ],
    "login-hint": HINT,
  },
};

const destino = (r: Response) => new URL(r.headers.get("location")!);

describe("POST /api/auth/entrar — a entrada pelo CPF", () => {
  it("uma Câmara: 303 direto ao Keycloak dela, com o usuário já conferido em login_hint", async () => {
    const fetchImpl = backend(umaCasa);
    const r = await entrarPeloCpf(post({ cpf: "529.982.247-25" }), { fetchImpl });

    expect(r.status).toBe(303);
    const loc = destino(r);
    expect(loc.origin + loc.pathname).toBe(`http://localhost:8090/realms/ente-${A}/protocol/openid-connect/auth`);
    expect(loc.searchParams.get("login_hint")).toBe(HINT);
    expect(loc.searchParams.get("code_challenge_method")).toBe("S256");
    expect(r.headers.get("set-cookie")).toMatch(/^pkce=/);
  });

  it("pergunta ao backend com o CPF só em dígitos, no corpo — e repassa o IP que a borda pôs", async () => {
    const fetchImpl = backend(umaCasa);
    await entrarPeloCpf(post({ cpf: "529.982.247-25" }, { "x-forwarded-for": "203.0.113.7, 10.0.0.2" }), { fetchImpl });

    const [url, init] = fetchImpl.mock.calls[0];
    expect(String(url)).toMatch(/\/auth\/localizar$/);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({ cpf: CPF });
    expect(new Headers(init?.headers).get("x-forwarded-for")).toBe("203.0.113.7");
  });

  it("o CPF nunca aparece na URL de destino, em nenhum caminho", async () => {
    for (const resposta of [umaCasa, duasCasas, { status: 200, corpo: { casas: [] } }, { status: 429 }, { status: 500 }] as const) {
      const r = await entrarPeloCpf(post({ cpf: "529.982.247-25" }), { fetchImpl: backend(resposta) });
      expect(r.headers.get("location")).not.toContain(CPF);
      expect(r.headers.get("location")).not.toContain("529.982");
    }
  });

  it("mais de uma Câmara: 303 para escolher, com a lista e o usuário num cookie httpOnly curto", async () => {
    const r = await entrarPeloCpf(post({ cpf: CPF, redirect: "/tramitacao" }), { fetchImpl: backend(duasCasas) });

    expect(r.status).toBe(303);
    expect(destino(r).pathname).toBe("/entrar/escolher");
    const raw = r.headers.get("set-cookie")!;
    expect(raw).toMatch(new RegExp(`^${COOKIE_ESCOLHA}=`));
    expect(raw).toMatch(/HttpOnly/i);
    expect(raw).toMatch(/Max-Age=300/i);
    // o navegador devolve o valor e o Next o decodifica no pedido (como o cookie `pkce`)
    const escolha = lerEscolha(decodeURIComponent(raw.match(new RegExp(`^${COOKIE_ESCOLHA}=([^;]+)`))![1]));
    expect(escolha).toEqual({
      hint: HINT,
      redirectPath: "/tramitacao",
      casas: [
        { enteId: A, nome: "Câmara Municipal de Baturité" },
        { enteId: B, nome: "Câmara Municipal de Russas" },
      ],
    });
  });

  it("vindo do link de uma Câmara (/entrar/[ente]): vai direto a ela, mesmo que o CPF tenha outras", async () => {
    const r = await entrarPeloCpf(post({ cpf: CPF, ente: B }), { fetchImpl: backend(duasCasas) });
    expect(destino(r).pathname).toBe(`/realms/ente-${B}/protocol/openid-connect/auth`);
    expect(destino(r).searchParams.get("login_hint")).toBe(HINT);
  });

  it("vindo do link de uma Câmara onde o CPF não tem acesso: volta à página dela com a explicação", async () => {
    const outra = "30000000-0000-0000-0000-000000000003";
    const r = await entrarPeloCpf(post({ cpf: CPF, ente: outra }), { fetchImpl: backend(duasCasas) });
    expect(destino(r).pathname).toBe(`/entrar/${outra}`);
    expect(destino(r).searchParams.get("erro")).toBe("sem-acesso-nesta");
  });

  it.each([
    ["CPF sem acesso", { status: 200, corpo: { casas: [] } }, "sem-acesso"],
    ["CPF recusado pelo backend", { status: 400 }, "cpf-invalido"],
    ["limite de tentativas", { status: 429 }, "muitas-tentativas"],
    ["backend com erro", { status: 500 }, "indisponivel"],
    ["backend fora do ar", "rede", "indisponivel"],
  ] as const)("%s -> volta a /entrar?erro=%s", async (_nome, resposta, erro) => {
    const r = await entrarPeloCpf(post({ cpf: CPF, redirect: "/tramitacao" }), { fetchImpl: backend(resposta) });
    expect(r.status).toBe(303);
    expect(destino(r).pathname).toBe("/entrar");
    expect(destino(r).searchParams.get("erro")).toBe(erro);
    expect(destino(r).searchParams.get("redirect")).toBe("/tramitacao");
  });

  it("CPF que não confere nem sai do BFF", async () => {
    const fetchImpl = backend(umaCasa);
    const r = await entrarPeloCpf(post({ cpf: "529.982.247-24" }), { fetchImpl });
    expect(destino(r).searchParams.get("erro")).toBe("cpf-invalido");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("um redirect de fora do site é descartado", async () => {
    const r = await entrarPeloCpf(post({ cpf: CPF, redirect: "https://evil.example/x" }), { fetchImpl: backend({ status: 429 }) });
    expect(destino(r).searchParams.get("redirect")).toBeNull();
  });

  it("ente do formulário que não é UUID é ignorado (não vira caminho)", async () => {
    const r = await entrarPeloCpf(post({ cpf: CPF, ente: "../../x" }), { fetchImpl: backend({ status: 200, corpo: { casas: [] } }) });
    expect(destino(r).pathname).toBe("/entrar");
  });

  it("resposta do backend fora do formato (hint que não é UUID) cai em indisponível, sem ir ao Keycloak", async () => {
    const torta = { status: 200, corpo: { casas: [{ "ente-id": A, "nome-oficial": "X" }], "login-hint": "x" } };
    const r = await entrarPeloCpf(post({ cpf: CPF }), { fetchImpl: backend(torta) });
    expect(destino(r).searchParams.get("erro")).toBe("indisponivel");
  });
});
