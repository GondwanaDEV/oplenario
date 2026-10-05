import { describe, expect, it } from "vitest";
import { cpfValido, soDigitos } from "./cpf";
import { codificarEscolha, lerEscolha, ipDoCliente } from "./entrada-cpf";

const HINT = "0eabd6df-d0cc-40bb-a0ca-043027cb3a1f";
const A = "10000000-0000-0000-0000-000000000001";
const B = "20000000-0000-0000-0000-000000000002";

describe("cpfValido (o mesmo digito verificador do backend)", () => {
  it("aceita com e sem mascara", () => {
    expect(cpfValido("529.982.247-25")).toBe(true);
    expect(cpfValido("52998224725")).toBe(true);
  });
  it("recusa digito errado, os 11 iguais e tamanho errado", () => {
    expect(cpfValido("52998224724")).toBe(false);
    expect(cpfValido("111.111.111-11")).toBe(false);
    expect(cpfValido("123")).toBe(false);
    expect(cpfValido("")).toBe(false);
  });
  it("soDigitos tira a mascara", () => {
    expect(soDigitos(" 529.982.247-25 ")).toBe("52998224725");
  });
});

describe("a escolha de Câmara (quem tem acesso a mais de uma)", () => {
  const escolha = {
    hint: HINT,
    redirectPath: "/tramitacao",
    casas: [
      { enteId: A, nome: "Câmara Municipal de Baturité" },
      { enteId: B, nome: "Câmara Municipal de Russas" },
    ],
  };

  it("vai e volta pelo cookie", () => {
    expect(lerEscolha(codificarEscolha(escolha))).toEqual(escolha);
  });

  it("cookie ausente, quebrado ou adulterado -> null (a pessoa recomeça pelo CPF)", () => {
    expect(lerEscolha(undefined)).toBeNull();
    expect(lerEscolha("nao-e-json")).toBeNull();
    expect(lerEscolha(JSON.stringify({ ...escolha, hint: "nao-e-uuid" }))).toBeNull();
    expect(lerEscolha(JSON.stringify({ ...escolha, casas: [{ enteId: "x", nome: "y" }] }))).toBeNull();
    expect(lerEscolha(JSON.stringify({ ...escolha, casas: [] }))).toBeNull();
  });

  it("um destino que não é do próprio site não sobrevive à leitura", () => {
    const lida = lerEscolha(codificarEscolha({ ...escolha, redirectPath: "//evil.example" }));
    expect(lida?.redirectPath).toBeNull();
  });
});

describe("ipDoCliente — o IP que a borda (o proxy) pôs no X-Forwarded-For", () => {
  const h = (xff?: string) => new Headers(xff ? { "x-forwarded-for": xff } : {});
  it("o último da lista: o que o proxy acrescentou; os anteriores o cliente escreve o que quiser", () => {
    expect(ipDoCliente(h("203.0.113.7"))).toBe("203.0.113.7");
    expect(ipDoCliente(h("1.2.3.4, 203.0.113.7"))).toBe("203.0.113.7");
    expect(ipDoCliente(h("2001:db8::1"))).toBe("2001:db8::1");
  });
  it("sem cabeçalho ou com lixo -> null (nada é repassado ao backend)", () => {
    expect(ipDoCliente(h())).toBeNull();
    expect(ipDoCliente(h("<script>"))).toBeNull();
  });
});
