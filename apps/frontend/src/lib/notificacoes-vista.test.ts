import { describe, expect, it } from "vitest";
import { derivarInbox } from "./notificacoes-vista";
import type { MinhasNotificacoesOut } from "./contrato-paineis.gen";

const AGORA = "2026-07-19T15:00:00Z";

function n(id: string, criadoEm: string, lidaEm: string | null = null) {
  return {
    id,
    categoria: "norma_publicada",
    assunto: `Assunto ${id}`,
    corpo: "Ementa: ...",
    objetoTipo: "proposicao",
    objetoId: `pid-${id}`,
    criadoEm,
    lidaEm,
  };
}

function dados(itens: ReturnType<typeof n>[], naoLidas = itens.length): MinhasNotificacoesOut {
  return { notificacoes: itens, naoLidas } as unknown as MinhasNotificacoesOut;
}

describe("derivarInbox", () => {
  it("sem dados -> estrutura vazia coerente, nunca lança", () => {
    const v = derivarInbox(null, AGORA);
    expect(v.grupos).toEqual([]);
    expect(v.naoLidas).toBe(0);
    expect(v.vazia).toBe(true);
  });

  it("agrupa por Hoje / Esta semana / Antes", () => {
    const v = derivarInbox(
      dados([
        n("a", "2026-07-19T09:00:00Z"), // hoje
        n("b", "2026-07-16T09:00:00Z"), // 3 dias atrás -> esta semana
        n("c", "2026-06-01T09:00:00Z"), // antes
      ]),
      AGORA
    );
    expect(v.grupos.map((g) => g.chave)).toEqual(["hoje", "semana", "antes"]);
    expect(v.grupos.map((g) => g.rotulo)).toEqual(["Hoje", "Esta semana", "Antes"]);
    expect(v.grupos[0].itens.map((i) => i.id)).toEqual(["a"]);
    expect(v.grupos[2].itens.map((i) => i.id)).toEqual(["c"]);
  });

  it("grupo sem itens não aparece (nada de seção vazia na tela)", () => {
    const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")]), AGORA);
    expect(v.grupos).toHaveLength(1);
    expect(v.grupos[0].chave).toBe("hoje");
  });

  it("preserva a ordem do servidor dentro do grupo (mais recentes primeiro)", () => {
    const v = derivarInbox(
      dados([n("nova", "2026-07-19T14:00:00Z"), n("velha", "2026-07-19T08:00:00Z")]),
      AGORA
    );
    expect(v.grupos[0].itens.map((i) => i.id)).toEqual(["nova", "velha"]);
  });

  it("deriva `lida` da presença do carimbo, não de um booleano do servidor", () => {
    const v = derivarInbox(
      dados([n("lida", "2026-07-19T09:00:00Z", "2026-07-19T10:00:00Z"), n("nova", "2026-07-19T09:30:00Z")], 1),
      AGORA
    );
    const itens = v.grupos[0].itens;
    expect(itens.find((i) => i.id === "lida")!.lida).toBe(true);
    expect(itens.find((i) => i.id === "nova")!.lida).toBe(false);
    expect(v.naoLidas).toBe(1);
  });

  // Carry declarado: /ficha-materia é tela do shell do SERVIDOR e o endpoint por trás
  // (GET /legislativo/proposicoes/:id/ficha) nega 403 ao papel `vereador` — provado ao vivo
  // na Task 12. Enquanto não existir a ficha no shell do vereador, proposição não tem
  // destino acessível: href "" (sem link) em vez de uma âncora que erra na cara do usuário.
  it("proposição ainda não tem destino acessível ao vereador (href vazio, não um 403)", () => {
    const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")]), AGORA);
    expect(v.grupos[0].itens[0].href).toBe("");
  });

  it("objeto de tipo desconhecido não gera link quebrado", () => {
    const item = { ...n("a", "2026-07-19T09:00:00Z"), objetoTipo: "coisa_nova" };
    const v = derivarInbox(dados([item]), AGORA);
    expect(v.grupos[0].itens[0].href).toBe("");
  });

  it("`quando` é relativo e legível", () => {
    const v = derivarInbox(
      dados([n("a", "2026-07-19T14:00:00Z"), n("b", "2026-07-17T14:00:00Z")]),
      AGORA
    );
    expect(v.grupos[0].itens[0].quando).toBe("há 1h");
    expect(v.grupos[1].itens[0].quando).toBe("há 2 dias");
  });

  it("naoLidas do servidor vence a contagem local (pode haver mais que o teto de 50)", () => {
    const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")], 73), AGORA);
    expect(v.naoLidas).toBe(73);
    expect(v.vazia).toBe(false);
  });
});
