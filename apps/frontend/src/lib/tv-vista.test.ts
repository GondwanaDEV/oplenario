import { describe, it, expect } from "vitest";
import type { PautaOut, SessaoOut } from "./contrato";
import { estadoInicial, type EstadoPlenario, type PlacarVotacao } from "./plenario-reducer";
import {
  AQUECIMENTO_MS,
  encerrouAoVivo,
  faseDaTv,
  itensDaPautaTv,
  relogioDaTv,
  seloDaTv,
  subRelogioDaTv,
  tituloDaSessao,
  vistaResultadoTv,
  vistaTribunaTv,
  vistaVotacaoTv,
} from "./tv-vista";

const sessao = (over: Partial<SessaoOut> = {}): SessaoOut => ({
  id: "s1",
  "sessao-legislativa-id": "sl1",
  "tipo-sessao": "ordinaria",
  "numero-sequencial": 15,
  estado: "aberta",
  modalidade: "presencial",
  delibera: true,
  "transmite-publica": true,
  "gera-ata-regimental": true,
  "permite-voto-secreto": true,
  "permite-modalidade-remota": false,
  "agendada-para": "2026-09-23T17:00:00Z",
  "aberta-em": "2026-09-23T16:25:00Z",
  "encerrada-em": null,
  "motivo-nao-realizada": null,
  ...over,
});

const placar = (over: Partial<PlacarVotacao> = {}): PlacarVotacao => ({
  votacaoId: "v1",
  modalidade: "nominal",
  objetoTipo: "proposicao",
  objetoId: "p22",
  proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos" },
  encerrada: false,
  votosNominais: {},
  votosSecretos: 0,
  resultado: null,
  totais: null,
  baseMembros: null,
  ...over,
});

function estado(over: Partial<EstadoPlenario> = {}): EstadoPlenario {
  return { ...estadoInicial(sessao()), ...over };
}

const quorum = (presentes: number, membros: number) => ({
  presentesTotal: presentes,
  presentesPlenario: presentes,
  presentesRemoto: 0,
  membrosDaCasa: membros,
  presencasForaDoRoster: 0,
  semRegistroDePresenca: false,
});

describe("faseDaTv — a fase é derivada do estado, nunca escolhida", () => {
  it("agendada → abertura; suspensa → pausa; encerrada/não realizada/arquivada → encerrada", () => {
    expect(faseDaTv("agendada", null, false)).toBe("abertura");
    expect(faseDaTv("suspensa", placar(), false)).toBe("pausa");
    for (const e of ["encerrada", "nao_realizada", "arquivada"]) expect(faseDaTv(e, null, false)).toBe("encerrada");
  });

  it("aberta: votação aberta → votacao; sem votação ou votação encerrada → em-curso", () => {
    expect(faseDaTv("aberta", placar(), false)).toBe("votacao");
    expect(faseDaTv("aberta", null, false)).toBe("em-curso");
    expect(faseDaTv("aberta", placar({ encerrada: true, resultado: "aprovada" }), false)).toBe("em-curso");
  });

  it("resultado só enquanto a TV segura o veredito, e só com votação encerrada", () => {
    expect(faseDaTv("aberta", placar({ encerrada: true, resultado: "aprovada" }), true)).toBe("resultado");
    expect(faseDaTv("aberta", placar(), true)).toBe("votacao");
  });
});

describe("encerrouAoVivo — o veredito em tela cheia só na transição VISTA AO VIVO", () => {
  const montada = 1_000_000;
  const depois = montada + AQUECIMENTO_MS + 1;
  const aberta = placar();
  const encerrada = placar({ encerrada: true, resultado: "aprovada" });

  it("aberta → encerrada (mesma votação), depois do aquecimento → true", () => {
    expect(encerrouAoVivo(aberta, encerrada, depois, montada)).toBe(true);
  });

  it("durante o aquecimento (replay do canal desde id:1) → false", () => {
    expect(encerrouAoVivo(aberta, encerrada, montada + 500, montada)).toBe(false);
  });

  it("sem ter visto a abertura, ou de outra votação, ou já encerrada antes → false", () => {
    expect(encerrouAoVivo(null, encerrada, depois, montada)).toBe(false);
    expect(encerrouAoVivo(placar({ votacaoId: "v0" }), encerrada, depois, montada)).toBe(false);
    expect(encerrouAoVivo(encerrada, encerrada, depois, montada)).toBe(false);
  });

  it("resultado fora do contrato não dispara", () => {
    expect(encerrouAoVivo(aberta, placar({ encerrada: true, resultado: "empate<script>" }), depois, montada)).toBe(false);
  });
});

describe("moldura", () => {
  it("título: número ordinal + tipo acentuado e capitalizado", () => {
    expect(tituloDaSessao(sessao())).toBe("15ª Sessão Ordinária");
    expect(tituloDaSessao(sessao({ "tipo-sessao": "extraordinaria", "numero-sequencial": 3 }))).toBe("3ª Sessão Extraordinária");
  });

  it("selo por estado; reconexão tem precedência", () => {
    expect(seloDaTv("aberta", "aberta")).toEqual({ rotulo: "Ao vivo", tom: "vivo" });
    expect(seloDaTv("aberta", "reconectando").rotulo).toBe("Reconectando…");
    expect(seloDaTv("suspensa", "aberta").tom).toBe("pausa");
    expect(seloDaTv("agendada", "aberta").rotulo).toBe("Aguardando abertura");
    expect(seloDaTv("encerrada", "aberta").rotulo).toBe("Encerrada");
  });

  it("relógio HH:MM", () => {
    expect(relogioDaTv(Date.parse("2026-09-23T17:37:00Z"))).toMatch(/^\d{2}:\d{2}$/);
  });

  it("sub-relógio: tempo de sessão quando aberta; início previsto quando agendada; null sem dado", () => {
    const agora = Date.parse("2026-09-23T17:37:04Z");
    expect(subRelogioDaTv(sessao(), "aberta", agora)).toBe("sessão há 01:12:04");
    expect(subRelogioDaTv(sessao({ "aberta-em": null }), "agendada", agora)).toMatch(/^início previsto \d{2}:\d{2}$/);
    expect(subRelogioDaTv(sessao({ "aberta-em": null, "agendada-para": null }), "agendada", agora)).toBeNull();
    expect(subRelogioDaTv(sessao({ "aberta-em": null }), "aberta", agora)).toBeNull();
  });
});

describe("itensDaPautaTv", () => {
  const pauta: PautaOut = {
    "sessao-id": "s1",
    itens: [
      { id: "i1", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata", ordem: 1 },
      {
        id: "i2", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p22", ordem: 2,
        proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos" },
      },
      { id: "i3", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p31", ordem: 3 },
    ],
  };

  it("usa sigla/número/ementa do resumo; sem resumo cai no rótulo honesto (nunca o UUID)", () => {
    const [a, b, c] = itensDaPautaTv(pauta, null);
    expect(a).toMatchObject({ sigla: "Leitura", descricao: "Leitura da ata", fase: "Expediente" });
    expect(b.sigla).toMatch(/22\/2026$/);
    expect(b.descricao).toBe("Energia solar em prédios públicos");
    expect(c).toMatchObject({ sigla: "Proposição", descricao: "Matéria da ordem do dia" });
    expect(JSON.stringify([a, b, c])).not.toContain("p31");
  });

  it("marca em votação o item cujo proposicao-id é o objeto da votação ABERTA", () => {
    expect(itensDaPautaTv(pauta, placar()).map((i) => i.emVotacao)).toEqual([false, true, false]);
    expect(itensDaPautaTv(pauta, placar({ encerrada: true })).some((i) => i.emVotacao)).toBe(false);
  });

  it("sem pauta → lista vazia", () => {
    expect(itensDaPautaTv(null, null)).toEqual([]);
  });
});

describe("vistaVotacaoTv", () => {
  const composicao = new Map([
    ["ver-a", { nomeParlamentar: "Helena Past", cargoMesa: null }],
    ["ver-b", { nomeParlamentar: "Antônio Moraes", cargoMesa: null }],
  ]);

  it("nominal: podem votar = presentes do quórum; faltam = presentes − votaram; nomes ordenados", () => {
    const v = vistaVotacaoTv(
      estado({
        quorum: quorum(18, 21),
        composicao,
        placar: placar({ votosNominais: { "ver-a": "sim", "ver-b": "nao", "ver-x": "abstencao" } }),
      }),
    )!;
    expect(v).toMatchObject({ modalidade: "nominal", podemVotar: 18, membrosDaCasa: 21, votaram: 3, faltam: 15, sim: 1, nao: 1, abstencao: 1 });
    expect(v.numero).toMatch(/22\/2026$/);
    expect(v.nominais.map((n) => n.nome)).toEqual(["Antônio Moraes", "Helena Past", "Vereador(a)"]);
  });

  it("sem quórum conhecido: podem votar e faltam ficam null (nunca inventados)", () => {
    const v = vistaVotacaoTv(estado({ placar: placar({ votosNominais: { a: "sim" } }) }))!;
    expect(v.podemVotar).toBeNull();
    expect(v.faltam).toBeNull();
    expect(v.votaram).toBe(1);
  });

  it("secreta em curso: só o contador; nenhuma contagem por voto nem nome (§22.6)", () => {
    const v = vistaVotacaoTv(estado({ quorum: quorum(18, 21), placar: placar({ modalidade: "secreta", votosSecretos: 7 }) }))!;
    expect(v).toMatchObject({ modalidade: "secreta", votaram: 7, faltam: 11, sim: null, nao: null, abstencao: null, nominais: [] });
  });

  it("sem votação aberta → null", () => {
    expect(vistaVotacaoTv(estado())).toBeNull();
    expect(vistaVotacaoTv(estado({ placar: placar({ encerrada: true, resultado: "aprovada" }) }))).toBeNull();
  });

  it("matéria sem resumo: número cai no tipo do objeto, ementa vazia", () => {
    const v = vistaVotacaoTv(estado({ placar: placar({ proposicao: null, objetoTipo: "requerimento" }) }))!;
    expect(v.numero).toBe("Requerimento");
    expect(v.ementa).toBe("");
  });
});

describe("vistaResultadoTv", () => {
  it("encerrada com resultado: veredito + placar final (agregado oficial)", () => {
    const r = vistaResultadoTv(
      estado({ placar: placar({ encerrada: true, resultado: "aprovada", totais: { sim: 14, nao: 3, abstencao: 1 }, votosNominais: { a: "sim" } }) }),
    )!;
    expect(r).toMatchObject({ resultado: "aprovada", sim: 14, nao: 3, abstencao: 1, modalidade: "nominal" });
  });

  it("secreta encerrada: o agregado é público no fim", () => {
    const r = vistaResultadoTv(
      estado({ placar: placar({ modalidade: "secreta", encerrada: true, resultado: "rejeitada", totais: { sim: 5, nao: 12, abstencao: 0 } }) }),
    )!;
    expect(r).toMatchObject({ resultado: "rejeitada", sim: 5, nao: 12, modalidade: "secreta" });
  });

  it("sem encerramento válido → null", () => {
    expect(vistaResultadoTv(estado({ placar: placar() }))).toBeNull();
    expect(vistaResultadoTv(estado({ placar: placar({ encerrada: true, resultado: null }) }))).toBeNull();
  });
});

describe("vistaTribunaTv", () => {
  const orador = { falaId: "f1", oradorId: "ver-a", tipoFala: "principal", fase: "grande_expediente", iniciouEm: "2026-09-23T17:30:00Z" };

  it("nome, iniciais, detalhe e tempo decorrido", () => {
    const t = vistaTribunaTv(
      estado({ oradorAtual: orador, composicao: new Map([["ver-a", { nomeParlamentar: "Helena Past", cargoMesa: "Vice-presidente" }]]) }),
      Date.parse("2026-09-23T17:36:24Z"),
    )!;
    expect(t).toMatchObject({ nome: "Helena Past", iniciais: "HP", detalhe: "Fala principal · Vice-presidente", fase: "Grande Expediente", decorrido: "06:24", pausado: false });
  });

  it("sem nome: rótulo neutro e avatar '—' (nunca o UUID)", () => {
    const t = vistaTribunaTv(estado({ oradorAtual: orador }), Date.parse("2026-09-23T17:31:00Z"))!;
    expect(t.nome).toBe("Orador com a palavra");
    expect(t.iniciais).toBe("—");
    expect(JSON.stringify(t)).not.toContain("ver-a");
  });

  it("pausado quando o último marco é 'pausada'", () => {
    const t = vistaTribunaTv(
      estado({ oradorAtual: orador, marcosCronometro: [{ tipo: "pausada", ocorridoEm: "2026-09-23T17:32:00Z" }] }),
      Date.parse("2026-09-23T17:40:00Z"),
    )!;
    expect(t.pausado).toBe(true);
    expect(t.decorrido).toBe("02:00");
  });

  it("sem orador → null", () => {
    expect(vistaTribunaTv(estado(), Date.now())).toBeNull();
  });
});
