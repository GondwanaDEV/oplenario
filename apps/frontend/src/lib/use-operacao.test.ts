import { afterEach, describe, expect, it, vi } from "vitest";
import {
  conferirProvisionar,
  corpoProvisionar,
  cpfValido,
  provisionarCasa,
  quemAtuou,
  reenviarConvite,
  rotuloAcao,
  rotuloEstadoCasa,
  seloCurto,
  type EntradaProvisionar,
} from "./use-operacao";

const OK: EntradaProvisionar = {
  nomeOficial: "Câmara Municipal de Baturité",
  nomeCurto: "",
  uf: "CE",
  municipioIbge: "2302008",
  municipioNome: "Baturité",
  admin: { nome: "Renata Costa", cpf: "529.982.247-25", email: "renata@camara.ce.gov.br" },
};

afterEach(() => vi.unstubAllGlobals());

describe("console do operador — conferência no navegador", () => {
  it("CPF pelo dígito verificador, com ou sem pontuação", () => {
    expect(cpfValido("529.982.247-25")).toBe(true);
    expect(cpfValido("52998224725")).toBe(true);
    expect(cpfValido("52998224724")).toBe(false);
    expect(cpfValido("111.111.111-11")).toBe(false);
    expect(cpfValido("123")).toBe(false);
  });

  it("entrada completa passa; cada campo faltando diz o que fazer", () => {
    expect(conferirProvisionar(OK)).toEqual({});
    const erros = conferirProvisionar({ ...OK, uf: "", municipioIbge: "23", admin: { nome: "R", cpf: "1", email: "x" } });
    expect(Object.keys(erros).sort()).toEqual(["adminCpf", "adminEmail", "adminNome", "municipioIbge", "uf"]);
    expect(erros.adminEmail).toMatch(/convite vai para este e-mail/);
  });

  it("o corpo vai no formato do backend, só com os dígitos do CPF e do IBGE", () => {
    expect(corpoProvisionar({ ...OK, municipioIbge: "230.2008" })).toEqual({
      "nome-oficial": "Câmara Municipal de Baturité",
      "nome-curto": null,
      uf: "CE",
      "municipio-ibge": "2302008",
      "municipio-nome": "Baturité",
      admin: { nome: "Renata Costa", cpf: "52998224725", email: "renata@camara.ce.gov.br" },
    });
  });
});

describe("console do operador — envio", () => {
  it("201 devolve a Casa e se o convite saiu (chaves camelizadas)", async () => {
    const f = vi.fn().mockResolvedValue(new Response(JSON.stringify({ casa: { "ente-id": "e1", estado: "provisionar" }, convite: "enviado" }), { status: 201 }));
    vi.stubGlobal("fetch", f);
    const r = await provisionarCasa(OK, null);
    expect(r).toEqual({ ok: true, dados: { casa: { enteId: "e1", estado: "provisionar" }, convite: "enviado" } });
    expect(f.mock.calls[0][0]).toBe("/api/operacao/casas");
    expect(f.mock.calls[0][1].method).toBe("POST");
  });

  it("409 mostra o porquê que o servidor deu; 401 manda entrar de novo", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ erro: "a Casa ja' passou as maos dela" }), { status: 409 })));
    expect(await reenviarConvite("e1", null)).toEqual({ ok: false, mensagem: "A Casa ja' passou as maos dela." });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{}", { status: 401 })));
    const r = await reenviarConvite("e1", null);
    expect(r.ok).toBe(false);
    expect(!r.ok && r.mensagem).toMatch(/entre de novo com a sua chave/i);
  });

  it("sem rede não lança: devolve a mensagem", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    const r = await reenviarConvite("e1", null);
    expect(!r.ok && r.mensagem).toMatch(/sem conexão/i);
  });
});

describe("console do operador — rótulos", () => {
  it("estado, ação e selo curto", () => {
    expect(rotuloEstadoCasa("provisionar")).toBe("Aguardando 1º admin");
    expect(rotuloEstadoCasa("ativo")).toBe("Ativa");
    expect(rotuloAcao("casa-ativada")).toMatch(/a Casa assumiu/i);
    expect(rotuloAcao("acao-nova")).toBe("acao-nova");
    expect(seloCurto("e4c78a09ffff00001111")).toBe("e4c7·1111");
  });

  it("o orçamento de IA da Casa aparece em palavras, e não como a chave da ação", () => {
    expect(rotuloAcao("ia-orcamento-iniciado")).toBe("Orçamento de IA: definição iniciada");
    expect(rotuloAcao("ia-orcamento-definido")).toBe("Orçamento de IA definido");
    expect(rotuloAcao("realm-reprovisionamento-iniciado")).toBe("Configuração de login: reaplicação iniciada");
    expect(rotuloAcao("realm-reprovisionamento-falhou")).toBe("Configuração de login: a reaplicação falhou");
    expect(rotuloAcao("ia-orcamento-falhou")).toBe("Orçamento de IA: a definição falhou");
  });

  it("sem operador: a linha de comando da Operação não passa por ato da própria câmara", () => {
    expect(quemAtuou({ operador: "Rafaela Operação", detalhe: {} })).toBe("Rafaela Operação");
    expect(quemAtuou({ operador: null, detalhe: { origem: "linha-de-comando" } })).toBe("linha de comando da Operação");
    expect(quemAtuou({ operador: null, detalhe: {} })).toBe("pela própria câmara");
  });
});
