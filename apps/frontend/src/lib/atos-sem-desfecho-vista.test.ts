import { afterEach, describe, expect, it, vi } from "vitest";
import {
  avisoDeCorte, camaraDoAto, lerAtosSemDesfecho, quandoIniciou, quemIniciou, toleranciaEmPalavras, tituloDosAtos,
} from "./atos-sem-desfecho-vista";
import { rotuloAcao } from "./use-operacao";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("atos da Operação sem desfecho (vista)", () => {
  it("o título concorda em número", () => {
    expect(tituloDosAtos(1)).toBe("1 ato iniciado sem desfecho registrado");
    expect(tituloDosAtos(3)).toBe("3 atos iniciados sem desfecho registrado");
    expect(tituloDosAtos(1234)).toMatch(/^1\.234 atos iniciados/);
  });

  it("o corte só é avisado quando houve corte", () => {
    expect(avisoDeCorte({ truncado: false, total: 2, atos: [{}, {}] as never })).toBeNull();
    expect(avisoDeCorte({ truncado: true, total: 83, atos: Array(50).fill({}) as never }))
      .toBe("Mostrando os 50 mais recentes de 83.");
  });

  it("a tolerância sai em palavras", () => {
    expect(toleranciaEmPalavras(120)).toBe("2 minutos");
    expect(toleranciaEmPalavras(60)).toBe("1 minuto");
    expect(toleranciaEmPalavras(90)).toBe("90 segundos");
  });

  it("quem iniciou: o operador, ou a linha de comando; nunca 'pela própria câmara'", () => {
    expect(quemIniciou({ operador: "Rafaela Operação", origem: null })).toBe("Rafaela Operação");
    expect(quemIniciou({ operador: null, origem: "linha-de-comando" })).toBe("linha de comando da Operação");
    expect(quemIniciou({ operador: null, origem: null })).toBe("operador não identificado");
  });

  it("a Câmara pelo nome; sem Câmara na linha, nada; Câmara sem nome no registro, dito sem UUID", () => {
    expect(camaraDoAto({ enteId: "e1", casaNome: "Câmara Municipal de Baturité" })).toBe("Câmara Municipal de Baturité");
    expect(camaraDoAto({ enteId: null, casaNome: null })).toBeNull();
    expect(camaraDoAto({ enteId: "e1", casaNome: null })).toBe("Câmara fora do registro");
  });

  it("a data é a de Fortaleza, não a do navegador nem a de UTC", () => {
    expect(quandoIniciou("2026-10-05T12:31:00Z")).toBe("05/10/2026 09:31");
    expect(quandoIniciou("2026-10-05T02:30:00Z")).toBe("04/10/2026 23:30");
    expect(quandoIniciou("lixo")).toBe("data não registrada");
  });

  it("o fuso de Fortaleza é do código, não do runner: com o processo em UTC o resultado é o mesmo", async () => {
    // o runner dos testes já roda em Fortaleza (vitest.config.ts); sem trocar o fuso do processo a asserção acima
    // não distinguiria "timeZone explícito" de "o fuso que calhou de estar"
    vi.stubEnv("TZ", "UTC");
    vi.resetModules();
    const { quandoIniciou: emUtc } = await import("./atos-sem-desfecho-vista");
    expect(emUtc("2026-10-05T02:30:00Z")).toBe("04/10/2026 23:30");
  });

  it("só lê a resposta que tem a forma esperada", () => {
    expect(lerAtosSemDesfecho(null)).toBeNull();
    expect(lerAtosSemDesfecho({ casas: [] })).toBeNull();
    expect(lerAtosSemDesfecho({ total: 0 })).toBeNull();
    expect(lerAtosSemDesfecho({ total: 0, atos: [], truncado: false, toleranciaSegundos: 120, limite: 50 }))
      .toEqual({ toleranciaSegundos: 120, limite: 50, total: 0, truncado: false, atos: [] });
  });

  it("as três ações que abrem par têm rótulo em palavras (nenhuma chave crua na tela)", () => {
    for (const acao of ["entrada-no-console-iniciada", "ia-orcamento-iniciado", "realm-reprovisionamento-iniciado"]) {
      expect(rotuloAcao(acao)).not.toBe(acao);
    }
    expect(rotuloAcao("entrada-no-console-iniciada")).toBe("Entrada no console: iniciada");
    expect(rotuloAcao("entrou-no-console")).toBe("Entrou no console");
    expect(rotuloAcao("entrada-no-console-falhou")).toBe("Entrada no console: a sessão não abriu");
  });
});
