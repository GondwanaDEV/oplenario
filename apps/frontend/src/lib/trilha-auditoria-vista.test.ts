import { describe, expect, it } from "vitest";
import {
  FILTRO_INICIAL, lacre, objeto, queryDoFiltro, quem, seloCurto, verbo, type RegistroTrilha,
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
});
