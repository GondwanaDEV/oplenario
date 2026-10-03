import { describe, expect, it } from "vitest";
import {
  dataLegivel,
  fraseDoResultado,
  fraseDosPrecisos,
  heroiDoParecer,
  motivoEmPalavras,
  nDeM,
  ordenarPrestacoes,
  prazosDaPrestacao,
  rotuloEstado,
  situacaoDaLinha,
  textoComoDecide,
  validarNovaPrestacao,
  type FormNovaPrestacao,
} from "./contas-vista";
import type { PrestacaoOut } from "./contrato-contas";

const prest = (extra: Partial<PrestacaoOut> = {}): PrestacaoOut => ({
  id: "pc1", tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", parecerPrevio: "favoravel", estado: "prazo_de_defesa",
  recebidaEm: "2026-10-02", prazoJulgamentoAte: "2026-12-01", quorum: { baseMembros: 21, necessariosParaRejeitar: 14 }, pautavel: false,
  documentos: [], ...extra,
});

describe("o herói do parecer", () => {
  it("favorável com ressalvas: título, complemento e a regra dos 2/3", () => {
    const h = heroiDoParecer("favoravel_com_ressalvas");
    expect(h.titulo).toBe("Favorável à aprovação");
    expect(h.complemento).toBe("com ressalvas e recomendações");
    expect(h.descricao).toMatch(/ressalvas a serem sanadas.*dois terços/);
    expect(h.favoravel).toBe(true);
  });
  it("desfavorável diz a rejeição em palavras (não só na cor)", () => {
    const h = heroiDoParecer("desfavoravel");
    expect(h.titulo).toBe("Desfavorável à aprovação");
    expect(h.favoravel).toBe(false);
  });
});

describe("N de M — 'Como a Câmara decide'", () => {
  it("14 de 21, com a fração do anel e o rótulo acessível", () => {
    const x = nDeM({ baseMembros: 21, necessariosParaRejeitar: 14 })!;
    expect(x.texto).toBe("14 de 21");
    expect(x.fracao).toBeCloseTo(14 / 21);
    expect(x.rotuloAcessivel).toMatch(/14 votos de 21 membros/);
  });
  it("sem quórum do servidor, não inventa número", () => {
    expect(nDeM(null)).toBeNull();
    expect(nDeM({ baseMembros: 0, necessariosParaRejeitar: 0 })).toBeNull();
    expect(fraseDosPrecisos(null)).toMatch(/2\/3 dos membros/);
  });
  it("o texto muda com o parecer: favorável → contas aprovadas; desfavorável → rejeitadas", () => {
    const q = { baseMembros: 21, necessariosParaRejeitar: 14 };
    expect(textoComoDecide("favoravel", q)).toMatch(/parecer favorável.*14 dos 21.*ficam aprovadas/);
    expect(textoComoDecide("desfavoravel", q)).toMatch(/parecer desfavorável.*ficam rejeitadas/);
    expect(fraseDosPrecisos(q)).toBe("São precisos 14 votos pela rejeição (2/3 dos 21 membros).");
  });
});

describe("a frase do resultado", () => {
  it("a do servidor prevalece", () => {
    expect(fraseDoResultado(prest({ resultado: "parecer_mantido", fraseResultado: "O parecer prevalece: 12 votos pela rejeição, eram precisos 14." }))).toBe(
      "O parecer prevalece: 12 votos pela rejeição, eram precisos 14.",
    );
  });
  it("sem a do servidor, monta do placar", () => {
    expect(fraseDoResultado(prest({ resultado: "parecer_mantido", votacao: { id: "v", sim: 12, nao: 8, abstencao: 1 } }))).toBe(
      "O parecer prevalece: 12 votos pela rejeição, eram precisos 14.",
    );
    expect(fraseDoResultado(prest({ resultado: "parecer_rejeitado", votacao: { id: "v", sim: 15, nao: 6, abstencao: 0 } }))).toBe(
      "O parecer foi rejeitado: 15 votos pela rejeição, eram precisos 14.",
    );
  });
  it("não julgada = null", () => {
    expect(fraseDoResultado(prest())).toBeNull();
  });
});

describe("prazos e motivo", () => {
  it("antes da notificação o prazo de defesa diz que começa nela; depois, a data", () => {
    expect(prazosDaPrestacao(prest())[0]).toMatchObject({ rotulo: "Prazo de defesa", valor: "começa na notificação" });
    const p = prazosDaPrestacao(prest({ prazoDefesaAte: "2026-10-18", defesaJuntadaEm: "2026-10-10T14:00:00Z" }));
    expect(p[0].valor).toBe("até 18/10/2026");
    expect(p[0].nota).toMatch(/Defesa juntada em 10\/10\/2026/);
    expect(p[1]).toMatchObject({ rotulo: "Prazo para julgar", valor: "até 01/12/2026" });
  });
  it("frase do servidor passa com maiúscula e ponto; código vira palavras; nada cru", () => {
    expect(motivoEmPalavras("o prazo de defesa vai até 20/10/2026")).toBe("O prazo de defesa vai até 20/10/2026.");
    expect(motivoEmPalavras("prazo_de_defesa", { prazoDefesaAte: "2026-10-20" })).toMatch(/^O prazo de defesa vai até 20\/10\/2026\./);
    expect(motivoEmPalavras("aguardando_notificacao")).toMatch(/notificação do responsável/);
    expect(motivoEmPalavras("codigo_desconhecido")).toBe("A matéria ainda não pode ir à pauta.");
    expect(motivoEmPalavras(null)).toBe("A matéria ainda não pode ir à pauta.");
  });
  it("data de calendário não recua um dia", () => {
    expect(dataLegivel("2026-10-18")).toBe("18/10/2026");
    expect(dataLegivel(null)).toBe("");
  });
});

describe("a lista", () => {
  it("estado em palavras e a linha de situação", () => {
    expect(rotuloEstado("julgada")).toBe("Julgada");
    expect(situacaoDaLinha(prest())).toBe("Prazo de defesa · julgar até 01/12/2026");
    expect(situacaoDaLinha(prest({ resultado: "parecer_rejeitado", estado: "julgada" }))).toMatch(/rejeitado/);
    expect(situacaoDaLinha(prest({ tipo: "gestao_camara", estado: "acompanhamento" }))).toMatch(/Acompanhamento/);
  });
  it("governo primeiro, exercício mais recente primeiro", () => {
    const ord = ordenarPrestacoes([
      { tipo: "gestao_camara", exercicio: 2025 },
      { tipo: "governo_prefeito", exercicio: 2023 },
      { tipo: "governo_prefeito", exercicio: 2024 },
    ]);
    expect(ord.map((p) => `${p.tipo}:${p.exercicio}`)).toEqual(["governo_prefeito:2024", "governo_prefeito:2023", "gestao_camara:2025"]);
  });
});

describe("o formulário de registro", () => {
  const f: FormNovaPrestacao = {
    tipo: "governo_prefeito", exercicio: "2024", responsavel: "José Sarto", recebidaEm: "2026-10-02", processoTce: "",
    parecerPrevio: "favoravel", comissaoAutoraId: "c1", situacaoTce: "",
  };
  it("completo passa", () => {
    expect(validarNovaPrestacao(f, "2026-10-03")).toEqual({});
  });
  it("governo exige parecer e comissão autora; a Mesa não", () => {
    const e = validarNovaPrestacao({ ...f, parecerPrevio: "", comissaoAutoraId: "" }, "2026-10-03");
    expect(e.parecerPrevio).toMatch(/parecer prévio/);
    expect(e.comissaoAutoraId).toMatch(/Decreto Legislativo/);
    expect(validarNovaPrestacao({ ...f, tipo: "gestao_camara", parecerPrevio: "", comissaoAutoraId: "" }, "2026-10-03")).toEqual({});
  });
  it("exercício, responsável e data conferidos", () => {
    const e = validarNovaPrestacao({ ...f, exercicio: "2030", responsavel: " ", recebidaEm: "2026-11-01" }, "2026-10-03");
    expect(e.exercicio).toMatch(/de 1988 a 2026/);
    expect(e.responsavel).toBe("Informe o Prefeito daquele exercício.");
    expect(e.recebidaEm).toMatch(/futuro/);
  });
});
