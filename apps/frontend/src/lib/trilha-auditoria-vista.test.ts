import { describe, expect, it } from "vitest";
import {
  CLASSES, FILTRO_INICIAL, decisaoEmPalavras, lacre, objeto, queryDoFiltro, quem, seloCurto, verbo, type RegistroTrilha,
} from "./trilha-auditoria-vista";

const base: RegistroTrilha = {
  seq: 7, em: "2026-09-29T15:32:07Z", acao: "legislativo/despachar", classe: "escrita", decisao: "permitido",
  ator: { tipo: "pessoa", nome: "Maria Secretária", papeis: ["secretario"], via: null },
  recurso: { tipo: "proposicao", id: "30000000-0000-0000-0000-000000000003", rotulo: "PL 7/2026" },
  campos: ["relator"], canal: "web", ip: "189.45.x.x", selo: "a7f39c21ffff", seloAnterior: "b2e84d70eeee",
};

describe("trilha de auditoria — a lógica pura", () => {
  it("o filtro vira a query do servidor, com o dia civil da Casa (Fortaleza)", () => {
    // 29/09 às 01:00 UTC ainda é 28/09 em Fortaleza (UTC-3)
    const agora = new Date("2026-09-29T01:00:00Z");
    expect(queryDoFiltro(FILTRO_INICIAL, agora)).toBe("desde=2026-08-30");
    expect(queryDoFiltro({ periodo: "7", ator: "cidadao", classe: "negacao", objeto: "sessoes" }, agora, 120)).toBe(
      "desde=2026-09-22&ator=cidadao&classe=negacao&objeto=sessoes&antes-de=120");
    expect(queryDoFiltro({ ...FILTRO_INICIAL, periodo: "ano" }, agora)).toBe("desde=2026-01-01");
    expect(queryDoFiltro({ ...FILTRO_INICIAL, periodo: "tudo" }, agora)).toBe("");
  });

  it("o verbo sai da classe e do nome da ação — e o que não se classifica é 'Registrou', nunca um chute", () => {
    expect(verbo(base).rotulo).toBe("Aprovou");
    expect(verbo({ ...base, acao: "legislativo/protocolar-proposicao" }).rotulo).toBe("Criou");
    expect(verbo({ ...base, acao: "sessoes/reordenar-pauta" }).rotulo).toBe("Editou");
    expect(verbo({ ...base, acao: "sessoes/retirar-item" }).rotulo).toBe("Removeu");
    expect(verbo({ ...base, acao: "paineis/xyz" }).rotulo).toBe("Registrou");
    expect(verbo({ ...base, classe: "negacao", decisao: "negado" }).rotulo).toBe("Negado");
    expect(verbo({ ...base, classe: "entrada", acao: "identidade/sessao" }).rotulo).toBe("Entrou");
    expect(verbo({ ...base, classe: "leitura_sensivel", acao: "auditoria/exportar" }).rotulo).toBe("Exportou");
    expect(verbo({ ...base, decisao: "falhou" }).rotulo).toBe("Não concluiu");
  });

  it("o objeto usa o rótulo do ato; sem rótulo, o tipo do recurso; sem recurso, a ação em palavras", () => {
    expect(objeto(base)).toEqual({ titulo: "PL 7/2026", detalhe: "despachar · Proposições e tramitação" });
    expect(objeto({ ...base, recurso: { tipo: "sessao", id: "abcdef12-0000", rotulo: null } }).titulo).toBe("Sessão abcdef12");
    expect(objeto({ ...base, recurso: null, acao: "identidade/conceder-acesso" }).titulo).toBe(
      "conceder acesso · Acessos e identidade");
    expect(objeto({ ...base, decisao: "negado" }).detalhe).toMatch(/barrado pela política/);
  });

  it("o resumo da ação que o servidor grava (ADR-0017 1-C) é o título, sem mudança de tela: o rótulo vem pronto, em palavras", () => {
    // o handler não deu rótulo do objeto; o servidor gravou o rótulo do ato ("Concedeu um acesso à Casa")
    const concedeu: RegistroTrilha = { ...base, acao: "identidade/conceder-acesso", recurso: { tipo: null, id: null, rotulo: "Concedeu um acesso à Casa" } };
    expect(objeto(concedeu).titulo).toBe("Concedeu um acesso à Casa");
    // o registro antigo, sem rótulo e sem recurso, segue legível como sempre (a corrente não se reescreve)
    expect(objeto({ ...concedeu, recurso: null }).titulo).toBe("conceder acesso · Acessos e identidade");
  });

  it("a frase do ato não ganha um segundo verbo na etiqueta, e o detalhe só situa o módulo", () => {
    const ato: RegistroTrilha = { ...base, acao: "sessoes/definir-tempos-regimentais",
      recurso: { tipo: null, id: null, rotulo: "Definiu os tempos regimentais da tribuna" } };
    expect(verbo(ato).rotulo).toBe("Ato");
    expect(objeto(ato)).toEqual({ titulo: "Definiu os tempos regimentais da tribuna", detalhe: "Sessões e atas" });
    // a cor segue a natureza da ação
    expect(verbo({ ...ato, acao: "identidade/revogar-acesso", recurso: { tipo: null, id: null, rotulo: "Revogou um acesso à Casa" } }))
      .toEqual({ rotulo: "Ato", tom: "removeu" });
    // rótulo de objeto continua com o verbo da ação
    expect(verbo(base).rotulo).toBe("Aprovou");
    // negação e falha seguem com a etiqueta própria
    expect(verbo({ ...ato, decisao: "falhou" }).rotulo).toBe("Não concluiu");
  });

  it("tipo de recurso que a tela não conhece não vira título com pedaço de UUID", () => {
    const antigo: RegistroTrilha = { ...base, acao: "sessoes/transicionar", recurso: { tipo: "id", id: "17d4218b-cfa8-4c29", rotulo: null } };
    expect(objeto(antigo).titulo).toBe("transicionar · Sessões e atas");
    expect(objeto(antigo).titulo).not.toMatch(/17d4218b/);
  });

  it("quem: pessoa pelo papel; cidadão pseudonimizado; agente com 'via'", () => {
    expect(quem(base.ator)).toEqual({ nome: "Maria Secretária", papel: "Secretaria", iniciais: "MS" });
    expect(quem({ tipo: "cidadao", nome: "Cidadão #a1b2c3", papeis: [], via: null })).toMatchObject({
      papel: "Portal do cidadão", iniciais: "#" });
    expect(quem({ tipo: "agente", nome: null, papeis: [], via: "conferencia-normativa" })).toMatchObject({
      nome: "Agente institucional", papel: "via agente conferencia-normativa" });
  });

  it("o lacre nunca diz 'íntegra' sem ter conferido", () => {
    expect(lacre(null, "carregando").titulo).toBe("Conferindo a cadeia…");
    expect(lacre(null, "erro").titulo).toBe("Não foi possível conferir agora");
    expect(lacre({ integra: true, total: 3, cabeca: "x", quebraEm: null, selosDoDia: [] }, "pronto").titulo).toBe("Cadeia íntegra");
    const q = lacre({ integra: false, total: 3, cabeca: "x", quebraEm: 4, selosDoDia: [] }, "pronto");
    expect([q.titulo, q.quebrada]).toEqual(["Cadeia quebrada", true]);
    expect(q.texto).toMatch(/nº 4/);
    expect(seloCurto("a7f39c21ffff")).toBe("a7f3·9c21");
  });

  it("a escrita iniciada sem desfecho registrado aparece em palavras — nunca como 'Não concluído' nem como enum", () => {
    const orfa: RegistroTrilha = { ...base, decisao: "sem_desfecho", recurso: { tipo: "proposicao", id: "30000000-0000", rotulo: null } };
    expect(verbo(orfa)).toEqual({ rotulo: "Sem desfecho", tom: "negado" });
    expect(objeto(orfa).detalhe).toMatch(/ação iniciada, desfecho não registrado/);
    expect(decisaoEmPalavras("sem_desfecho")).toMatch(/^Ação iniciada, desfecho não registrado/);
    expect(decisaoEmPalavras("falhou")).toBe("Não concluído");
    expect(decisaoEmPalavras("permitido")).toBe("Permitido");
    expect(decisaoEmPalavras("algo_novo")).toBe("Não reconhecida (algo_novo)");
    expect(CLASSES.map((c) => c.valor)).toContain("sem_desfecho");
    expect(queryDoFiltro({ ...FILTRO_INICIAL, periodo: "tudo", classe: "sem_desfecho" }, new Date())).toBe("classe=sem_desfecho");
  });

  it("o lacre acusa o desfecho faltando mesmo com a cadeia íntegra", () => {
    const um = lacre({ integra: true, total: 9, cabeca: "x", quebraEm: null, selosDoDia: [], semDesfecho: 1, primeiroSemDesfecho: 4 }, "pronto");
    expect(um.titulo).toBe("Cadeia íntegra, com desfecho faltando");
    expect(um.texto).toMatch(/Uma ação foi iniciada e o desfecho não foi registrado \(a mais antiga é o registro nº 4\)/);
    expect(um.quebrada).toBe(false);
    const tres = lacre({ integra: true, total: 9, cabeca: "x", quebraEm: null, selosDoDia: [], semDesfecho: 3, primeiroSemDesfecho: 2 }, "pronto");
    expect(tres.texto).toMatch(/3 ações foram iniciadas/);
    expect(lacre({ integra: true, total: 9, cabeca: "x", quebraEm: null, selosDoDia: [], semDesfecho: 0, primeiroSemDesfecho: null }, "pronto").titulo)
      .toBe("Cadeia íntegra");
    expect(lacre({ integra: false, total: 9, cabeca: "x", quebraEm: 4, selosDoDia: [], semDesfecho: 2 }, "pronto").titulo)
      .toBe("Cadeia quebrada");
  });
});
