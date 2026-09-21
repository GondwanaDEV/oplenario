import { describe, expect, it } from "vitest";
import { DESTINO_POS_LOGIN, destinoPorPapeis, pedidoDeRedirect, resolveRedirectPath } from "./redirect";

const ORIGIN = "https://camara.exemplo.br";

describe("pedidoDeRedirect — distingue 'pediu' de 'não pediu'", () => {
  it("sem pedido -> null (deixa o papel decidir)", () => {
    expect(pedidoDeRedirect(null, ORIGIN)).toBeNull();
    expect(pedidoDeRedirect("", ORIGIN)).toBeNull();
  });

  it("pedido same-origin é honrado, já canonicalizado", () => {
    expect(pedidoDeRedirect("/tramitacao?x=1", ORIGIN)).toBe("/tramitacao?x=1");
  });

  it("pedido para outra origin é DESCARTADO (null), não convertido em destino", () => {
    expect(pedidoDeRedirect("https://evil.example/roubado", ORIGIN)).toBeNull();
    // a barra invertida normaliza para `/` em esquemas especiais e escaparia a origin
    expect(pedidoDeRedirect("/\\evil.example", ORIGIN)).toBeNull();
  });

  it("difere de resolveRedirectPath, que nunca devolve null", () => {
    expect(resolveRedirectPath(null, ORIGIN)).toBe(DESTINO_POS_LOGIN);
    expect(pedidoDeRedirect(null, ORIGIN)).toBeNull();
  });
});

describe("destinoPorPapeis — cada persona na sua home", () => {
  it("vereador vai para a home dele, não para o escritório da secretaria", () => {
    expect(destinoPorPapeis(["vereador"])).toBe("/vereador");
  });

  it("secretario vence quem acumula papéis", () => {
    expect(destinoPorPapeis(["vereador", "secretario"])).toBe(DESTINO_POS_LOGIN);
  });

  it("sem papel de trabalho é a cidadã — vai para a área dela, com o chrome dela", () => {
    expect(destinoPorPapeis([])).toBe("/acompanhamentos");
  });

  it("papéis desconhecidos (o /eu falhou) NÃO viram a tela da cidadã — cai no início, que se adapta", () => {
    expect(destinoPorPapeis(null)).toBe(DESTINO_POS_LOGIN);
  });
});
