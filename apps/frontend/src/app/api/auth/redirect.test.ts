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

  it("admin_ente sem outro papel vai para a área dele, não para a tela da cidadã (ADR-0005)", () => {
    expect(destinoPorPapeis(["admin_ente"])).toBe("/administracao");
    expect(destinoPorPapeis(["auditor"])).toBe("/auditoria"); // o controle interno pousa na trilha (ADR-0017)
  });

  it("o jurídico pousa na fila dele, não na tela da cidadã (ADR-0019) — a ordem é secretario, vereador, admin_ente, auditor, juridico", () => {
    expect(destinoPorPapeis(["juridico"])).toBe("/juridico");
    expect(destinoPorPapeis(["auditor", "juridico"])).toBe("/auditoria");
    expect(destinoPorPapeis(["admin_ente", "juridico"])).toBe("/administracao");
    expect(destinoPorPapeis(["vereador", "juridico"])).toBe("/vereador");
    expect(destinoPorPapeis(["secretario", "juridico"])).toBe(DESTINO_POS_LOGIN);
  });

  it("quem acumula admin_ente com um papel de trabalho vai para a home desse papel", () => {
    expect(destinoPorPapeis(["vereador", "admin_ente"])).toBe("/vereador");
    expect(destinoPorPapeis(["secretario", "admin_ente"])).toBe(DESTINO_POS_LOGIN);
  });

  it("sem papel de trabalho é a cidadã — vai para a área dela, com o chrome dela", () => {
    expect(destinoPorPapeis([])).toBe("/acompanhamentos");
  });

  it("papéis desconhecidos (o /eu falhou) NÃO viram a tela da cidadã — cai no início, que se adapta", () => {
    expect(destinoPorPapeis(null)).toBe(DESTINO_POS_LOGIN);
  });
});

// Achado da frente "url-sem-dados" (12/09/2026), refeito sobre a main: `resolved.origin === origin` não basta.
// O parser resolve o dot-segment e o caminho canonicalizado passa a começar com `//` — e quem consome o
// retorno faz `new URL(caminho, origin)`, que lê `//host` como outra origem.
describe("redirect com dot-segment não escapa da origem", () => {
  const HOSTIS = ["/.//evil.example", "/..//evil.example", "/a/..//evil.example", "/%2e//evil.example", "/./\\evil.example"];

  it.each(HOSTIS)("resolveRedirectPath(%s) cai no destino padrão", (candidato) => {
    const caminho = resolveRedirectPath(candidato, ORIGIN);
    expect(caminho).toBe(DESTINO_POS_LOGIN);
    expect(new URL(caminho, ORIGIN).origin).toBe(ORIGIN);
  });

  it.each(HOSTIS)("pedidoDeRedirect(%s) é descartado", (candidato) => {
    expect(pedidoDeRedirect(candidato, ORIGIN)).toBeNull();
  });

  it("caminho legítimo com ponto segue honrado", () => {
    expect(resolveRedirectPath("/atendimento/../atas?x=1", ORIGIN)).toBe("/atas?x=1");
    expect(pedidoDeRedirect("/contas/./abc", ORIGIN)).toBe("/contas/abc");
  });
});
