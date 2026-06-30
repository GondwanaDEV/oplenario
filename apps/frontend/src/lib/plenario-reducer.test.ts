import { describe, it, expect } from "vitest";
import { estadoInicial, aplicarEvento, type EstadoPlenario } from "./plenario-reducer";
import type { EventoPlenario, SessaoOut } from "./contrato";

// ---- fixtures mínimas (forma fiel ao wire) ----

const sessao = (over: Partial<SessaoOut> = {}): SessaoOut => ({
  id: "s1",
  "sessao-legislativa-id": "sl1",
  "tipo-sessao": "ordinaria",
  "numero-sequencial": 14,
  estado: "agendada",
  modalidade: "presencial",
  delibera: true,
  "transmite-publica": true,
  "gera-ata-regimental": true,
  "permite-voto-secreto": false,
  "permite-modalidade-remota": false,
  "agendada-para": "2026-05-21T22:00:00Z",
  "aberta-em": null,
  "encerrada-em": null,
  "motivo-nao-realizada": null,
  ...over,
});

// aplica uma sequência de eventos a partir do estado inicial de `s`
const reduzir = (s: SessaoOut, eventos: EventoPlenario[]): EstadoPlenario =>
  eventos.reduce(aplicarEvento, estadoInicial(s));

describe("estadoInicial", () => {
  it("semeia o estado a partir da sessão e começa com tudo vazio", () => {
    const e = estadoInicial(sessao({ estado: "aberta" }));
    expect(e.estado).toBe("aberta");
    expect(e.presentes).toEqual([]);
    expect(e.oradorAtual).toBeNull();
    expect(e.inscritos).toEqual([]);
    expect(e.ultimoSeq).toBe(0);
  });
});

describe("sessao.transicionou", () => {
  it("avança o estado da sessão para o `para` da transição", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "sessao.transicionou", seq: 1, dados: { "sessao-id": "s1", de: "aberta", para: "suspensa" } },
    ]);
    expect(e.estado).toBe("suspensa");
    expect(e.ultimoSeq).toBe(1);
  });
});

describe("presenca.registrada — quórum por conjunto", () => {
  const pres = (seq: number, vereador: string, tipo: string): EventoPlenario => ({
    tipo: "presenca.registrada",
    seq,
    dados: { "sessao-id": "s1", "vereador-id": vereador, tipo, modalidade: "presencial", fonte: "chamada", "ocorrido-em": "2026-05-21T22:01:00Z" },
  });

  it("entrada/retorno/mudanca_modalidade marcam presente; saida remove", () => {
    const e = reduzir(sessao(), [pres(1, "v1", "entrada"), pres(2, "v2", "entrada"), pres(3, "v1", "saida")]);
    expect(e.presentes).toEqual(["v2"]);
  });

  it("é idempotente — entrada repetida do mesmo vereador não duplica (entrega at-least-once)", () => {
    const e = reduzir(sessao(), [pres(1, "v1", "entrada"), pres(2, "v1", "entrada")]);
    expect(e.presentes).toEqual(["v1"]);
  });

  it("retorno após saída recoloca o vereador", () => {
    const e = reduzir(sessao(), [pres(1, "v1", "entrada"), pres(2, "v1", "saida"), pres(3, "v1", "retorno")]);
    expect(e.presentes).toEqual(["v1"]);
  });
});

describe("tribuna — fala iniciada/cronômetro/encerrada", () => {
  const iniciar: EventoPlenario = {
    tipo: "fala.iniciada",
    seq: 1,
    dados: { "fala-id": "f1", "sessao-id": "s1", "orador-id": "v1", "tipo-fala": "discussao", fase: "ordem_do_dia", "iniciou-em": "2026-05-21T22:10:00Z" },
  };

  it("fala.iniciada fixa o orador atual e zera os marcos do cronômetro", () => {
    const e = reduzir(sessao(), [iniciar]);
    expect(e.oradorAtual).toMatchObject({ falaId: "f1", oradorId: "v1", iniciouEm: "2026-05-21T22:10:00Z" });
    expect(e.marcosCronometro).toEqual([]);
  });

  it("fala.cronometro acumula marcos da fala em curso", () => {
    const e = reduzir(sessao(), [
      iniciar,
      { tipo: "fala.cronometro", seq: 2, dados: { "fala-id": "f1", "sessao-id": "s1", tipo: "pausada", "ocorrido-em": "2026-05-21T22:11:00Z" } },
      { tipo: "fala.cronometro", seq: 3, dados: { "fala-id": "f1", "sessao-id": "s1", tipo: "retomada", "ocorrido-em": "2026-05-21T22:11:30Z" } },
    ]);
    expect(e.marcosCronometro.map((m) => m.tipo)).toEqual(["pausada", "retomada"]);
  });

  it("ignora marco de cronômetro de outra fala (não a em curso)", () => {
    const e = reduzir(sessao(), [
      iniciar,
      { tipo: "fala.cronometro", seq: 2, dados: { "fala-id": "fOUTRA", "sessao-id": "s1", tipo: "pausada", "ocorrido-em": "2026-05-21T22:11:00Z" } },
    ]);
    expect(e.marcosCronometro).toEqual([]);
  });

  it("fala.encerrada limpa o orador atual e guarda o tempo usado", () => {
    const e = reduzir(sessao(), [
      iniciar,
      { tipo: "fala.encerrada", seq: 2, dados: { "fala-id": "f1", "sessao-id": "s1", "tempo-segundos": 248, "encerrou-em": "2026-05-21T22:14:08Z" } },
    ]);
    expect(e.oradorAtual).toBeNull();
    expect(e.ultimaFalaEncerrada).toMatchObject({ falaId: "f1", tempoSegundos: 248 });
  });
});

describe("inscritos — fila ordenada", () => {
  const insc = (seq: number, id: string, vereador: string, ordem: number): EventoPlenario => ({
    tipo: "inscricao.registrada",
    seq,
    dados: { "inscricao-id": id, "sessao-id": "s1", "vereador-id": vereador, "origem-inscricao": "manual", fase: "ordem_do_dia", ordem },
  });

  it("registra inscritos mantendo a ordem por `ordem` (não por chegada)", () => {
    const e = reduzir(sessao(), [insc(1, "i2", "v2", 2), insc(2, "i1", "v1", 1)]);
    expect(e.inscritos.map((i) => i.vereadorId)).toEqual(["v1", "v2"]);
  });

  it("desistência remove o inscrito pela inscricao-id", () => {
    const e = reduzir(sessao(), [
      insc(1, "i1", "v1", 1),
      insc(2, "i2", "v2", 2),
      { tipo: "inscricao.desistida", seq: 3, dados: { "inscricao-id": "i1", "sessao-id": "s1" } },
    ]);
    expect(e.inscritos.map((i) => i.vereadorId)).toEqual(["v2"]);
  });

  it("registro repetido da mesma inscrição não duplica (at-least-once)", () => {
    const e = reduzir(sessao(), [insc(1, "i1", "v1", 1), insc(2, "i1", "v1", 1)]);
    expect(e.inscritos).toHaveLength(1);
  });
});

describe("ultimoSeq — rastreia o maior seq visto (Last-Event-ID do resume)", () => {
  it("guarda o maior seq mesmo que um evento fora de ordem chegue depois", () => {
    const e = reduzir(sessao(), [
      { tipo: "sessao.transicionou", seq: 5, dados: { "sessao-id": "s1", de: "agendada", para: "aberta" } },
      { tipo: "sessao.transicionou", seq: 3, dados: { "sessao-id": "s1", de: "aberta", para: "suspensa" } },
    ]);
    expect(e.ultimoSeq).toBe(5);
  });
});

describe("votação ao vivo — placar (§22.6 sigilo)", () => {
  it("votacao.aberta cria o placar zerado com a modalidade (nominal)", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
    ]);
    expect(e.placar).toEqual({
      votacaoId: "vt1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
      votosNominais: {}, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
    });
  });

  it("voto.registrado nominal grava o voto por vereador (quem votou o quê); re-voto sobrescreve", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "sim" } },
      { tipo: "voto.registrado", seq: 3, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd2", voto: "nao" } },
      { tipo: "voto.registrado", seq: 4, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "abstencao" } },
    ]);
    expect(e.placar?.votosNominais).toEqual({ vd1: "abstencao", vd2: "nao" });
    expect(e.placar?.votosSecretos).toBe(0);
  });

  it("voto.registrado secreto SÓ incrementa o contador — nunca expõe identidade (sigilo)", () => {
    const e = reduzir(sessao({ estado: "aberta", "permite-voto-secreto": true }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "secreta", "quorum-tipo": "maioria_absoluta" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "secreta" } },
      { tipo: "voto.registrado", seq: 3, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "secreta" } },
    ]);
    expect(e.placar?.votosSecretos).toBe(2);
    expect(e.placar?.votosNominais).toEqual({});
  });

  it("§22.6 fail-closed: numa votação ABERTA secreta, um voto que chega com vereador-id NÃO o registra — conta anônimo", () => {
    // defesa-em-profundidade: a modalidade que vale é a da ABERTURA, não a do evento individual (que poderia
    // vir adulterado/malformado). A identidade que vazou no fio é DESCARTADA; só o contador anônimo sobe.
    const e = reduzir(sessao({ estado: "aberta", "permite-voto-secreto": true }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "secreta", "quorum-tipo": "maioria_absoluta" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "sim" } },
    ]);
    expect(e.placar?.votosNominais).toEqual({}); // identidade descartada
    expect(e.placar?.votosSecretos).toBe(1); // contado anonimamente
  });

  it("ignora voto de uma votação que não é a corrente (votacao-id diferente)", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "OUTRA", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd9", voto: "sim" } },
    ]);
    expect(e.placar?.votosNominais).toEqual({});
  });

  it("votacao.encerrada marca encerrada + grava resultado/totais/base (agregado público)", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "sim" } },
      { tipo: "votacao.encerrada", seq: 3, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", resultado: "aprovada", modalidade: "nominal",
        "total-sim": 6, "total-nao": 3, "total-abstencao": 1, "base-membros": 11 } },
    ]);
    expect(e.placar?.encerrada).toBe(true);
    expect(e.placar?.resultado).toBe("aprovada");
    expect(e.placar?.totais).toEqual({ sim: 6, nao: 3, abstencao: 1 });
    expect(e.placar?.baseMembros).toBe(11);
    expect(e.placar?.votosNominais).toEqual({ vd1: "sim" }); // preserva o nominal acumulado
  });

  it("votacao.encerrada sem aberta vista (reconexão) constrói o placar do agregado", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.encerrada", seq: 9, dados: {
        "votacao-id": "vt7", "sessao-id": "s1", resultado: "rejeitada", modalidade: "secreta" } },
    ]);
    expect(e.placar?.votacaoId).toBe("vt7");
    expect(e.placar?.encerrada).toBe(true);
    expect(e.placar?.resultado).toBe("rejeitada");
    expect(e.placar?.totais).toEqual({ sim: null, nao: null, abstencao: null });
  });
});
