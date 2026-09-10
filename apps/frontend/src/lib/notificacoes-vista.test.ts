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

  it("agrupa por Hoje / Últimos 7 dias / Antes", () => {
    const v = derivarInbox(
      dados([
        n("a", "2026-07-19T09:00:00Z"), // hoje
        n("b", "2026-07-16T09:00:00Z"), // 3 dias atrás -> esta semana
        n("c", "2026-06-01T09:00:00Z"), // antes
      ]),
      AGORA
    );
    expect(v.grupos.map((g) => g.chave)).toEqual(["hoje", "semana", "antes"]);
    // o rótulo tem de dizer o que o código mede: janela de 7 dias, não semana de calendário.
    expect(v.grupos.map((g) => g.rotulo)).toEqual(["Hoje", "Últimos 7 dias", "Antes"]);
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

  // A guarda real aqui é o `?? ""` da tabela de rotas: tipo SEM rota -> sem link. Sem ela, uma tabela
  // futura devolveria `undefined` e a página renderizaria <Link href={comToken(undefined, …)}> — o link
  // quebrado que este teste diz cobrir. O tipo cru continua chegando à vista: é a ROTA que não existe.
  it("objeto de tipo desconhecido não gera link quebrado (a tabela de rotas falha fechada)", () => {
    const item = { ...n("a", "2026-07-19T09:00:00Z"), objetoTipo: "coisa_nova" };
    const v = derivarInbox(dados([item]), AGORA);
    const vista = v.grupos[0].itens[0];
    expect(vista.href).toBe("");
    expect(typeof vista.href).toBe("string");
    expect(vista.objetoTipo).toBe("coisa_nova");
  });

  it("`quando` é relativo e legível", () => {
    const v = derivarInbox(
      dados([n("a", "2026-07-19T14:00:00Z"), n("b", "2026-07-17T14:00:00Z")]),
      AGORA
    );
    expect(v.grupos[0].itens[0].quando).toBe("há 1h");
    expect(v.grupos[1].itens[0].quando).toBe("há 2 dias");
  });

  // Os quatro ramos de `quandoRelativo` numa tacada — o de MINUTOS é o mais exercido na tela real (todo
  // aviso recém-chegado cai nele) e era o único sem asserção nenhuma; o piso de 1min e o singular
  // "há 1 dia" também não tinham.
  it("`quando` cobre os quatro ramos: minutos (com piso de 1), horas, 1 dia no singular e plural", () => {
    const v = derivarInbox(
      dados([
        n("agora", AGORA), // delta 0 -> o piso, não "há 0min"
        n("min", "2026-07-19T14:40:00Z"), // 20 min
        n("hora", "2026-07-19T14:00:00Z"), // 1 h
        n("umdia", "2026-07-18T13:00:00Z"), // 26 h -> singular
        n("doisdias", "2026-07-17T14:00:00Z"), // 49 h -> plural
      ]),
      AGORA
    );
    const quando = Object.fromEntries(v.grupos.flatMap((g) => g.itens).map((i) => [i.id, i.quando]));
    expect(quando).toEqual({
      agora: "há 1min",
      min: "há 20min",
      hora: "há 1h",
      umdia: "há 1 dia",
      doisdias: "há 2 dias",
    });
  });

  // AGORA = 15:00Z = 12:00 em Fortaleza (UTC−3), dia 19. A fronteira é a MEIA-NOITE DA CASA, não "24h
  // atrás": o aviso das 23h de ontem tem delta de 13h e mesmo assim NÃO é de hoje.
  it("'Hoje' é o dia de calendário da Casa, não uma janela móvel de 24h", () => {
    const v = derivarInbox(
      dados([
        n("meia-noite-de-hoje", "2026-07-19T03:00:00Z"), // 19/07 00:00 na Casa
        n("ontem-23h59", "2026-07-19T02:59:59Z"), // 18/07 23:59 na Casa — 13h atrás
      ]),
      AGORA
    );
    expect(v.grupos.find((g) => g.chave === "hoje")!.itens.map((i) => i.id)).toEqual([
      "meia-noite-de-hoje",
    ]);
    expect(v.grupos.find((g) => g.chave === "semana")!.itens.map((i) => i.id)).toEqual(["ontem-23h59"]);
  });

  it("o corte dos 7 dias também é por dia de calendário: 6 dias fica, 7 dias sai", () => {
    const v = derivarInbox(
      dados([
        n("seis-dias", "2026-07-13T23:00:00Z"), // 13/07 20:00 na Casa
        n("sete-dias", "2026-07-12T04:00:00Z"), // 12/07 01:00 na Casa
      ]),
      AGORA
    );
    expect(v.grupos.find((g) => g.chave === "semana")!.itens.map((i) => i.id)).toEqual(["seis-dias"]);
    expect(v.grupos.find((g) => g.chave === "antes")!.itens.map((i) => i.id)).toEqual(["sete-dias"]);
  });

  it("`quandoExato` fixa o instante absoluto no fuso da Casa (o relativo defasa, este não)", () => {
    const v = derivarInbox(dados([n("a", "2026-07-19T02:00:00Z")]), AGORA);
    expect(v.grupos[0].itens[0].quandoExato).toBe("18/07/2026 às 23:00");
  });

  it("carimbo ilegível não derruba a tela: cai em 'Antes', sem rótulo inventado e sem lançar", () => {
    const v = derivarInbox(dados([{ ...n("a", "2026-07-19T09:00:00Z"), criadoEm: "sem-data" }]), AGORA);
    expect(v.grupos.map((g) => g.chave)).toEqual(["antes"]);
    expect(v.grupos[0].itens[0].quando).toBe("");
    expect(v.grupos[0].itens[0].quandoExato).toBe("");
  });

  it("naoLidas do servidor vence a contagem local (pode haver mais que o teto de 50)", () => {
    const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")], 73), AGORA);
    expect(v.naoLidas).toBe(73);
    expect(v.vazia).toBe(false);
  });

  // O badge (total, sem teto) e a lista (cortada em 50 no SQL) são universos diferentes. A tela precisa
  // de um número para DIZER isso; sem ele, "10 não lidas" no título com "Não lidas 0" na aba é
  // contradição na cara do vereador.
  it("declara quantas não lidas o servidor conta e a LISTA não tem", () => {
    const v = derivarInbox(
      dados([n("lida", "2026-07-19T09:00:00Z", "2026-07-19T10:00:00Z")], 10),
      AGORA
    );
    expect(v.naoLidas).toBe(10);
    expect(v.filtros.find((f) => f.chave === "nao-lidas")!.quantidade).toBe(0);
    expect(v.naoLidasForaDaLista).toBe(10);
  });

  it("badge e lista concordando -> nada fora da lista (não se inventa aviso de corte)", () => {
    const v = derivarInbox(dados([n("a", "2026-07-19T09:00:00Z")], 1), AGORA);
    expect(v.naoLidasForaDaLista).toBe(0);
  });

  it("total do servidor MENOR que o local (marcação em voo) não vira número negativo", () => {
    const v = derivarInbox(
      dados([n("a", "2026-07-19T09:00:00Z"), n("b", "2026-07-19T09:30:00Z")], 1),
      AGORA
    );
    expect(v.naoLidasForaDaLista).toBe(0);
  });

  it("sem dados, nada fora da lista", () => {
    expect(derivarInbox(null, AGORA).naoLidasForaDaLista).toBe(0);
  });
});

// ---------------------------------------------------------------------------------------------
// Fatia 3 — a barra de filtro. As abas são DERIVADAS do dado; o racional está no topo de
// notificacoes-vista.ts. Cada teste abaixo REPROVA a implementação alternativa (abas fixas).
// ---------------------------------------------------------------------------------------------

function nc(id: string, categoria: string, criadoEm = "2026-07-19T09:00:00Z", lidaEm: string | null = null) {
  return { ...n(id, criadoEm, lidaEm), categoria };
}

describe("derivarInbox · barra de filtro", () => {
  it("lista vazia não ganha barra de filtro (nada a filtrar)", () => {
    const v = derivarInbox(dados([], 0), AGORA);
    expect(v.filtros).toEqual([]);
    expect(v.vazia).toBe(true);
    expect(v.vaziaNoFiltro).toBe(false);
  });

  it("com UMA só categoria presente, a barra é só Tudo + Não lidas — nenhuma aba redundante com Tudo", () => {
    const v = derivarInbox(dados([nc("a", "norma_publicada"), nc("b", "norma_publicada")]), AGORA);
    expect(v.filtros.map((f) => f.chave)).toEqual(["tudo", "nao-lidas"]);
    expect(v.filtros.map((f) => f.rotulo)).toEqual(["Tudo", "Não lidas"]);
  });

  it("NÃO inventa aba para categoria sem produtor (falha/prazo/sessao não aparecem do nada)", () => {
    const v = derivarInbox(dados([nc("a", "norma_publicada")]), AGORA);
    const rotulos = v.filtros.map((f) => f.rotulo).join(" ");
    expect(rotulos).not.toMatch(/Falha|Prazo|Sess|Tramita/i);
  });

  it("com DUAS categorias presentes, nasce uma aba por categoria, em ordem estável de rótulo", () => {
    const v = derivarInbox(dados([nc("a", "sistema"), nc("b", "norma_publicada"), nc("c", "sistema")]), AGORA);
    expect(v.filtros.map((f) => f.chave)).toEqual([
      "tudo",
      "nao-lidas",
      "cat:norma_publicada",
      "cat:sistema",
    ]);
    expect(v.filtros.map((f) => f.rotulo)).toEqual(["Tudo", "Não lidas", "Normas publicadas", "Sistema"]);
    expect(v.filtros.map((f) => f.quantidade)).toEqual([3, 3, 1, 2]);
  });

  it("a ordem das abas NÃO depende da ordem de chegada (barra não se reordena a cada notificação)", () => {
    const a = derivarInbox(dados([nc("x", "sistema"), nc("y", "norma_publicada")]), AGORA);
    const b = derivarInbox(dados([nc("y", "norma_publicada"), nc("x", "sistema")]), AGORA);
    expect(a.filtros.map((f) => f.chave)).toEqual(b.filtros.map((f) => f.chave));
  });

  it("categoria nova (sem rótulo mapeado) vira rótulo legível — não some da barra nem vaza o slug cru", () => {
    const v = derivarInbox(dados([nc("a", "falha_transcricao"), nc("b", "norma_publicada")]), AGORA);
    const nova = v.filtros.find((f) => f.chave === "cat:falha_transcricao");
    expect(nova).toBeDefined();
    expect(nova!.rotulo).toBe("Falha transcricao");
  });

  it("filtro 'nao-lidas' esconde as lidas e preserva o agrupamento temporal", () => {
    const v = derivarInbox(
      dados(
        [
          nc("lida", "norma_publicada", "2026-07-19T09:00:00Z", "2026-07-19T10:00:00Z"),
          nc("nova", "norma_publicada", "2026-07-19T09:30:00Z"),
          nc("antiga", "norma_publicada", "2026-06-01T09:00:00Z"),
        ],
        2
      ),
      AGORA,
      "nao-lidas"
    );
    expect(v.grupos.map((g) => g.chave)).toEqual(["hoje", "antes"]);
    expect(v.grupos.flatMap((g) => g.itens.map((i) => i.id))).toEqual(["nova", "antiga"]);
  });

  it("filtro por categoria mostra só a categoria escolhida", () => {
    const v = derivarInbox(
      dados([nc("a", "sistema"), nc("b", "norma_publicada")]),
      AGORA,
      "cat:sistema"
    );
    expect(v.grupos.flatMap((g) => g.itens.map((i) => i.id))).toEqual(["a"]);
    expect(v.filtroAtivo).toBe("cat:sistema");
  });

  it("a contagem da aba 'Não lidas' é a LOCAL (o que o filtro vai mostrar), não o total do servidor", () => {
    // servidor diz 73 não lidas (teto de 50 corta a lista); só 1 item não lido chegou.
    const v = derivarInbox(
      dados([nc("a", "norma_publicada"), nc("b", "norma_publicada", "2026-07-19T09:00:00Z", "2026-07-19T10:00:00Z")], 73),
      AGORA
    );
    expect(v.filtros.find((f) => f.chave === "nao-lidas")!.quantidade).toBe(1);
    // o badge do cabeçalho continua sendo a verdade do servidor
    expect(v.naoLidas).toBe(73);
  });

  it("filtro que não zera nada, mas não casa nenhum item, é 'vazio no filtro' — não 'inbox vazia'", () => {
    const v = derivarInbox(
      dados([nc("lida", "norma_publicada", "2026-07-19T09:00:00Z", "2026-07-19T10:00:00Z")], 0),
      AGORA,
      "nao-lidas"
    );
    expect(v.grupos).toEqual([]);
    expect(v.vazia).toBe(false);
    expect(v.vaziaNoFiltro).toBe(true);
  });

  it("filtro obsoleto (categoria que sumiu do dado) cai em 'tudo' em vez de esvaziar a tela", () => {
    const v = derivarInbox(dados([nc("a", "norma_publicada")]), AGORA, "cat:categoria_que_sumiu");
    expect(v.filtroAtivo).toBe("tudo");
    expect(v.grupos.flatMap((g) => g.itens.map((i) => i.id))).toEqual(["a"]);
  });

  it("sem filtro explícito o default é 'tudo' (compatível com as chamadas de 2 argumentos)", () => {
    const v = derivarInbox(dados([nc("a", "norma_publicada")]), AGORA);
    expect(v.filtroAtivo).toBe("tudo");
  });
});
