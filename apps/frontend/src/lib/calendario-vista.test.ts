import { describe, expect, it } from "vitest";
import {
  derivarCalendario,
  partesDoDia,
  mesAnterior,
  mesSeguinte,
  type ObrigacaoEmAberto,
} from "./calendario-vista";
import type { SessaoOut } from "./contrato-sessoes.gen";

// A suíte roda em TZ=America/Fortaleza (vitest.config.ts) — UTC−3. Isso é o que dá sentido às asserções
// de fuso abaixo: em UTC elas passariam por acidente.
function sessao(p: Partial<SessaoOut> & { id: string }): SessaoOut {
  return {
    sessaoLegislativaId: "sl-1",
    tipoSessao: "ordinaria",
    numeroSequencial: 12,
    estado: "agendada",
    modalidade: "presencial",
    delibera: true,
    transmitePublica: true,
    geraAtaRegimental: true,
    permiteVotoSecreto: false,
    permiteModalidadeRemota: false,
    agendadaPara: null,
    abertaEm: null,
    encerradaEm: null,
    motivoNaoRealizada: null,
    lockVersion: 1,
    ...p,
  };
}

function obrigacao(p: Partial<ObrigacaoEmAberto> & { id: string }): ObrigacaoEmAberto {
  return {
    templateChave: "remessa_mensal_sim",
    objetoTipo: "ente",
    objetoId: "e-1",
    venceEm: "2026-06-29",
    estado: "pendente",
    ...p,
  };
}

const JUNHO = { ano: 2026, mes: 6 };

describe("derivarCalendario — a grade do mês", () => {
  it("junho de 2026 começa numa segunda: 35 células, da primeira Dom (31/mai) ao último Sáb (04/jul)", () => {
    const v = derivarCalendario({ ...JUNHO, hoje: "2026-06-22", sessoes: [], obrigacoes: [] });
    expect(v.titulo).toBe("Junho de 2026");
    expect(v.celulas).toHaveLength(35);
    expect(v.celulas[0]).toMatchObject({ iso: "2026-05-31", dia: 31, foraDoMes: true });
    expect(v.celulas[1]).toMatchObject({ iso: "2026-06-01", dia: 1, foraDoMes: false });
    expect(v.celulas[30]).toMatchObject({ iso: "2026-06-30", dia: 30, foraDoMes: false });
    expect(v.celulas[34]).toMatchObject({ iso: "2026-07-04", dia: 4, foraDoMes: true });
  });

  it("fevereiro de 2027 (28 dias começando numa segunda) fecha em 5 semanas, não em 6", () => {
    const v = derivarCalendario({ ano: 2027, mes: 2, hoje: "2027-02-01", sessoes: [], obrigacoes: [] });
    expect(v.celulas).toHaveLength(35);
    expect(v.semanas).toHaveLength(5);
    expect(v.titulo).toBe("Fevereiro de 2027");
  });

  it("agosto de 2026 (31 dias começando num SÁBADO) precisa de 6 semanas — e o dia 31 tem de existir", () => {
    // O outro lado do teste acima. Todo mês com contagem assertada era de 35 células; uma quantidade
    // FIXA em 35 passaria em todos eles e comeria o fim de agosto — uma sessão em 31/08 sumiria da tela.
    const v = derivarCalendario({ ano: 2026, mes: 8, hoje: "2026-08-01", sessoes: [], obrigacoes: [] });
    expect(v.celulas).toHaveLength(42);
    expect(v.semanas).toHaveLength(6);
    expect(v.semanas.every((sem) => sem.length === 7)).toBe(true);
    expect(v.celulas[0]).toMatchObject({ iso: "2026-07-26", foraDoMes: true });
    expect(v.celulas.find((c) => c.iso === "2026-08-31")).toMatchObject({ dia: 31, foraDoMes: false });
    expect(v.celulas[41]).toMatchObject({ iso: "2026-09-05", foraDoMes: true });
  });

  it("marca HOJE em exatamente uma célula, e só quando o dia está no mês exibido", () => {
    const v = derivarCalendario({ ...JUNHO, hoje: "2026-06-22", sessoes: [], obrigacoes: [] });
    expect(v.celulas.filter((c) => c.hoje).map((c) => c.iso)).toEqual(["2026-06-22"]);

    const outro = derivarCalendario({ ...JUNHO, hoje: "2026-09-10", sessoes: [], obrigacoes: [] });
    expect(outro.celulas.some((c) => c.hoje)).toBe(false);
  });

  it("navegação de mês vira o ano nas duas pontas", () => {
    expect(mesAnterior({ ano: 2026, mes: 1 })).toEqual({ ano: 2025, mes: 12 });
    expect(mesSeguinte({ ano: 2026, mes: 12 })).toEqual({ ano: 2027, mes: 1 });
    expect(mesAnterior(JUNHO)).toEqual({ ano: 2026, mes: 5 });
    expect(mesSeguinte(JUNHO)).toEqual({ ano: 2026, mes: 7 });
  });
});

describe("derivarCalendario — sessões", () => {
  it("põe a sessão no dia LOCAL, não no dia do instante UTC", () => {
    // 25/06 02:00Z é 24/06 23h em Fortaleza. Um `iso.slice(0,10)` diria 25 — e erraria o dia da sessão.
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [sessao({ id: "s1", numeroSequencial: 16, agendadaPara: "2026-06-25T02:00:00Z" })],
      obrigacoes: [],
    });
    const dia24 = v.celulas.find((c) => c.iso === "2026-06-24");
    const dia25 = v.celulas.find((c) => c.iso === "2026-06-25");
    expect(dia24?.eventos.map((e) => e.id)).toEqual(["sessao:s1"]);
    expect(dia25?.eventos).toEqual([]);
    expect(dia24?.eventos[0].hora).toBe("23h");
  });

  it("rótulo curto e título completo saem do número + tipo, com o tipo em português de tela", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [sessao({ id: "s1", numeroSequencial: 15, agendadaPara: "2026-06-24T17:00:00Z" })],
      obrigacoes: [],
    });
    const e = v.celulas.find((c) => c.iso === "2026-06-24")!.eventos[0];
    expect(e.hora).toBe("14h");
    expect(e.rotulo).toBe("15ª Ordinária");
    expect(e.titulo).toBe("15ª Sessão Ordinária");
    expect(e.tipo).toBe("sessao");
    expect(e.meta).toBe("14h");
    // A guarda do "Plenário" (SessaoOut não tem campo de local) mora em page.test.tsx: `metaDaSessao`
    // compõe hora+estado e NÃO tem entrada capaz de produzir essa string — a asserção aqui não podia
    // reprovar. O defeito plausível é o JSX da página escrever o local estático da maquete.
  });

  it("hora com minutos não vira hora cheia", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [sessao({ id: "s1", agendadaPara: "2026-06-24T12:30:00Z" })],
      obrigacoes: [],
    });
    expect(v.celulas.find((c) => c.iso === "2026-06-24")!.eventos[0].hora).toBe("9h30");
  });

  it("sessão SEM agendada-para não aparece em lugar nenhum (não se inventa data)", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [sessao({ id: "s-sem-data", agendadaPara: null })],
      obrigacoes: [],
    });
    expect(v.celulas.every((c) => c.eventos.length === 0)).toBe(true);
    expect(v.proximos).toEqual([]);
  });

  it("estado diferente de agendada é DITO, não escondido", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [
        sessao({ id: "s1", estado: "nao_realizada", agendadaPara: "2026-06-10T17:00:00Z" }),
        sessao({ id: "s2", estado: "agendada", agendadaPara: "2026-06-24T17:00:00Z" }),
      ],
      obrigacoes: [],
    });
    const naoRealizada = v.celulas.find((c) => c.iso === "2026-06-10")!.eventos[0];
    const agendada = v.celulas.find((c) => c.iso === "2026-06-24")!.eventos[0];
    expect(naoRealizada.meta).toBe("14h · não realizada");
    expect(agendada.meta).toBe("14h");
    // `alerta` é o campo que a CÉLULA da grade renderiza (o `meta` só alimenta a agenda lateral e o
    // aria-label). Sem asserção sobre ele, uma célula que pinta o mesmo ● jade da agendada passa verde.
    expect(naoRealizada.alerta).toBe("não realizada");
    expect(agendada.alerta).toBeNull();
  });

  it("dois eventos no mesmo dia saem ordenados pela hora", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [
        // Os ids são escolhidos para CONTRADIZER a ordem cronológica: "a-tarde" < "z-manha" em ordem
        // alfabética. Com os ids antigos ("manha"/"tarde") a ordem alfabética coincidia com a esperada e
        // o teste não separava "ordenou por hora" de "ordenou por id".
        sessao({ id: "a-tarde", numeroSequencial: 2, agendadaPara: "2026-06-24T23:00:00Z" }),
        sessao({ id: "z-manha", numeroSequencial: 1, agendadaPara: "2026-06-24T12:00:00Z" }),
      ],
      obrigacoes: [],
    });
    expect(v.celulas.find((c) => c.iso === "2026-06-24")!.eventos.map((e) => e.id)).toEqual([
      "sessao:z-manha",
      "sessao:a-tarde",
    ]);
  });

  it("no MESMO dia, o prazo (sem hora) vem depois das sessões com hora", () => {
    // O outro ramo do comparador (`hora === null` vai para o fim do dia) não tinha teste nenhum: em toda
    // a suíte não havia um dia com prazo E sessão juntos. Invertê-lo punha o prazo antes da sessão das 14h.
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [sessao({ id: "s1", agendadaPara: "2026-06-24T17:00:00Z" })],
      obrigacoes: [obrigacao({ id: "o1", venceEm: "2026-06-24" })],
    });
    expect(v.celulas.find((c) => c.iso === "2026-06-24")!.eventos.map((e) => e.id)).toEqual([
      "sessao:s1",
      "prazo:o1",
    ]);
  });
});

describe("derivarCalendario — prazos de compliance", () => {
  it("vence-em é DATE-ONLY: fica no dia escrito, sem recuar por fuso", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [],
      obrigacoes: [obrigacao({ id: "o1", venceEm: "2026-06-01" })],
    });
    expect(v.celulas.find((c) => c.iso === "2026-06-01")!.eventos.map((e) => e.id)).toEqual(["prazo:o1"]);
    expect(v.celulas.find((c) => c.iso === "2026-05-31")!.eventos).toEqual([]);
  });

  it("o prazo não tem hora e leva a chave do template CRUA (não se inventa nome bonito)", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [],
      obrigacoes: [obrigacao({ id: "o1", templateChave: "remessa_mensal_sim" })],
    });
    const e = v.celulas.find((c) => c.iso === "2026-06-29")!.eventos[0];
    expect(e.hora).toBeNull();
    expect(e.tipo).toBe("prazo");
    // o rótulo da GRADE leva o tipo por extenso (a célula não pode ser só cor); o título da agenda
    // lateral segue a chave crua.
    expect(e.rotulo).toBe("Prazo · remessa_mensal_sim");
    expect(e.titulo).toBe("remessa_mensal_sim");
    // O ramo PENDENTE do `meta` não era assertado em lugar nenhum: um `vencida em …` incondicional
    // passava verde e a tela dava falso alarme de prazo do TCE estourado.
    expect(e.meta).toBe("vence em 29/06/2026");
    expect(e.meta).not.toMatch(/vencida/i);
    expect(e.alerta).toBeNull();
  });

  it("obrigação VENCIDA é marcada como vencida na linha de apoio", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [],
      obrigacoes: [obrigacao({ id: "o1", estado: "vencida", venceEm: "2026-06-05" })],
    });
    const e = v.celulas.find((c) => c.iso === "2026-06-05")!.eventos[0];
    expect(e.meta).toBe("vencida em 05/06/2026");
    expect(e.alerta).toBe("vencida");
  });

  it("prazo em outro mês não vaza para a grade exibida", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [],
      obrigacoes: [obrigacao({ id: "o1", venceEm: "2026-08-10" })],
    });
    expect(v.celulas.every((c) => c.eventos.length === 0)).toBe(true);
  });
});

describe("derivarCalendario — a agenda lateral (Próximos)", () => {
  it("lista só o que ainda vem, em ordem, e ATRAVESSA o mês exibido", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [
        sessao({ id: "passada", numeroSequencial: 10, agendadaPara: "2026-06-03T17:00:00Z" }),
        sessao({ id: "depois", numeroSequencial: 16, agendadaPara: "2026-06-26T17:00:00Z" }),
        sessao({ id: "antes", numeroSequencial: 15, agendadaPara: "2026-06-24T17:00:00Z" }),
        sessao({ id: "julho", numeroSequencial: 17, agendadaPara: "2026-07-02T17:00:00Z" }),
      ],
      obrigacoes: [obrigacao({ id: "o1", venceEm: "2026-06-25" })],
    });
    expect(v.proximos.map((e) => e.id)).toEqual([
      "sessao:antes",
      "prazo:o1",
      "sessao:depois",
      "sessao:julho",
    ]);
  });

  it("o evento de HOJE conta como próximo (não some às 00h01)", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-24",
      sessoes: [sessao({ id: "hoje", agendadaPara: "2026-06-24T17:00:00Z" })],
      obrigacoes: [],
    });
    expect(v.proximos.map((e) => e.id)).toEqual(["sessao:hoje"]);
  });

  it("teto de 6 itens na agenda lateral", () => {
    const sessoes = Array.from({ length: 9 }, (_, i) =>
      sessao({ id: `s${i}`, numeroSequencial: i + 1, agendadaPara: `2026-07-${String(i + 1).padStart(2, "0")}T17:00:00Z` }),
    );
    const v = derivarCalendario({ ...JUNHO, hoje: "2026-06-22", sessoes, obrigacoes: [] });
    // O tamanho sozinho não separa `slice(0,6)` de `slice(-6)` nem de `slice(1,7)` — qualquer recorte de
    // 6 passava, inclusive um que comesse justamente a PRÓXIMA sessão da Casa.
    expect(v.proximos.map((e) => e.id)).toEqual([
      "sessao:s0",
      "sessao:s1",
      "sessao:s2",
      "sessao:s3",
      "sessao:s4",
      "sessao:s5",
    ]);
    // e o corte é DECLARADO: 6 exibidos de 6 e 6 de 9 não podem ser indistinguíveis na tela.
    expect(v.proximosOcultos).toBe(3);
  });

  it("sem corte, `proximosOcultos` é 0 (a tela não anuncia um resto que não existe)", () => {
    const v = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [sessao({ id: "s1", agendadaPara: "2026-06-24T17:00:00Z" })],
      obrigacoes: [],
    });
    expect(v.proximos).toHaveLength(1);
    expect(v.proximosOcultos).toBe(0);
  });

  it("sem nada agendado, `vazio` diz a verdade em vez de a tela fingir grade cheia", () => {
    const v = derivarCalendario({ ...JUNHO, hoje: "2026-06-22", sessoes: [], obrigacoes: [] });
    expect(v.vazio).toBe(true);
    expect(v.proximos).toEqual([]);

    const comEvento = derivarCalendario({
      ...JUNHO,
      hoje: "2026-06-22",
      sessoes: [sessao({ id: "s1", agendadaPara: "2026-06-24T17:00:00Z" })],
      obrigacoes: [],
    });
    expect(comEvento.vazio).toBe(false);
  });
});

describe("derivarCalendario — semanas", () => {
  it("as semanas são as MESMAS células, em linhas de 7 (a grade ARIA precisa de linhas)", () => {
    const v = derivarCalendario({ ...JUNHO, hoje: "2026-06-22", sessoes: [], obrigacoes: [] });
    expect(v.semanas).toHaveLength(5);
    expect(v.semanas.every((s) => s.length === 7)).toBe(true);
    expect(v.semanas.flat()).toEqual(v.celulas);
  });
});

describe("derivarCalendario — nome acessível da célula", () => {
  it("cada célula carrega dia + mês + ano por extenso, inclusive as de fora do mês", () => {
    const v = derivarCalendario({ ...JUNHO, hoje: "2026-06-22", sessoes: [], obrigacoes: [] });
    expect(v.celulas[0].rotuloDia).toBe("31 de maio de 2026");
    expect(v.celulas[24].rotuloDia).toBe("24 de junho de 2026");
    expect(v.celulas[34].rotuloDia).toBe("4 de julho de 2026");
  });
});

describe("partesDoDia — a data grande da agenda lateral", () => {
  it("quebra o dia local em número e mês curto, SEM passar por Date (date-only não pode recuar um dia)", () => {
    expect(partesDoDia("2026-06-29")).toEqual({ numero: "29", mesCurto: "jun" });
    // 1º de janeiro é o caso que denuncia recuo por fuso: em UTC−3 um `new Date("2026-01-01")`
    // formatado localmente volta para 31/dez/2025.
    expect(partesDoDia("2026-01-01")).toEqual({ numero: "1", mesCurto: "jan" });
    expect(partesDoDia("2026-12-31")).toEqual({ numero: "31", mesCurto: "dez" });
  });

  it("dia malformado devolve o texto cru em vez de inventar uma data", () => {
    expect(partesDoDia("sem-data")).toEqual({ numero: "sem-data", mesCurto: "" });
  });
});
