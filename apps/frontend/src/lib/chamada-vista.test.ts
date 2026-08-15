import { describe, it, expect } from "vitest";
import {
  ordenarLinhas,
  agruparLinhas,
  contarLocal,
  precedenciaDeEstado,
  diffParaLote,
  aplicarOtimista,
  reverterOtimista,
  podeEditar,
  lerJustificativa,
  TETO_LOTE_PRESENCA,
  type MarcacoesPendentes,
} from "./chamada-vista";
import type { LinhaChamadaOut, ChamadaQuorumOut } from "./contrato-sessoes.gen";

// ---- fixture mínima, fiel ao wire (camelCase — contrato-sessoes.gen.ts) ----

const linha = (over: Partial<LinhaChamadaOut> = {}): LinhaChamadaOut => ({
  vereadorId: "v1",
  nome: "Fulano da Silva",
  nomeParlamentar: "Fulano",
  partido: "PXX",
  cargoMesa: null,
  estado: "ausente",
  inconsistenciaCadastro: false,
  semAssento: false,
  desde: null,
  fonte: null,
  registradoEm: null,
  justificativa: null,
  ...over,
});

// ---------- 1. ordenarLinhas ----------

describe("ordenarLinhas", () => {
  it("'servidor' é a IDENTIDADE — devolve na ordem recebida (default)", () => {
    const linhas = [linha({ vereadorId: "c" }), linha({ vereadorId: "a" }), linha({ vereadorId: "b" })];
    expect(ordenarLinhas(linhas, "servidor").map((l) => l.vereadorId)).toEqual(["c", "a", "b"]);
    // sem 2º argumento também é 'servidor' — o cliente não inventa rito por default
    expect(ordenarLinhas(linhas).map((l) => l.vereadorId)).toEqual(["c", "a", "b"]);
  });

  it("'alfabetica' ordena por nome parlamentar (cai para nome se ausente), estável no empate", () => {
    const linhas = [
      linha({ vereadorId: "1", nomeParlamentar: "Beto", nome: "Roberto" }),
      linha({ vereadorId: "2", nomeParlamentar: "Ana", nome: "Ana Maria" }),
      linha({ vereadorId: "3", nomeParlamentar: null, nome: "Zeca" }),
      linha({ vereadorId: "4", nomeParlamentar: "Ana", nome: "Ana Beatriz" }), // empate com id=2
    ];
    const out = ordenarLinhas(linhas, "alfabetica").map((l) => l.vereadorId);
    expect(out).toEqual(["2", "4", "1", "3"]); // Ana(2), Ana(4, estável=ordem original), Beto(1), Zeca(3)
  });

  it("'partido' agrupa por sigla, estável no empate", () => {
    const linhas = [
      linha({ vereadorId: "1", partido: "PXX" }),
      linha({ vereadorId: "2", partido: "PAA" }),
      linha({ vereadorId: "3", partido: "PAA" }),
    ];
    expect(ordenarLinhas(linhas, "partido").map((l) => l.vereadorId)).toEqual(["2", "3", "1"]);
  });

  it("'estado' segue a precedência declarada, estável no empate", () => {
    const linhas = [
      linha({ vereadorId: "1", estado: "licenciado" }),
      linha({ vereadorId: "2", estado: "presente-plenario" }),
      linha({ vereadorId: "3", estado: "ausente" }),
      linha({ vereadorId: "4", estado: "presente-plenario" }), // empate com id=2
    ];
    expect(ordenarLinhas(linhas, "estado").map((l) => l.vereadorId)).toEqual(["2", "4", "3", "1"]);
  });

  it("nunca muta o array recebido", () => {
    const linhas = [linha({ vereadorId: "b" }), linha({ vereadorId: "a" })];
    const congelado = [...linhas];
    ordenarLinhas(linhas, "alfabetica");
    expect(linhas).toEqual(congelado);
  });

  it("preserva desde/registradoEm intactos — o PAR sobrevive ao módulo", () => {
    const l = linha({ desde: "2026-05-21T21:55:00Z", registradoEm: "2026-05-21T22:10:00Z" });
    const [out] = ordenarLinhas([l], "alfabetica");
    expect(out.desde).toBe("2026-05-21T21:55:00Z");
    expect(out.registradoEm).toBe("2026-05-21T22:10:00Z");
  });
});

// ---------- 2. agruparLinhas ----------

describe("agruparLinhas", () => {
  it("separa mesa/casa/licenciados/foraDaComposicao, cada linha em EXATAMENTE um grupo", () => {
    const linhas = [
      linha({ vereadorId: "presidente", cargoMesa: "presidente" }),
      linha({ vereadorId: "comum", cargoMesa: null, estado: "presente-plenario" }),
      linha({ vereadorId: "licenciado", cargoMesa: null, estado: "licenciado" }),
      linha({ vereadorId: "orfao", semAssento: true, estado: "presente-plenario" }),
    ];
    const g = agruparLinhas(linhas);
    expect(g.mesa.map((l) => l.vereadorId)).toEqual(["presidente"]);
    expect(g.casa.map((l) => l.vereadorId)).toEqual(["comum"]);
    expect(g.licenciados.map((l) => l.vereadorId)).toEqual(["licenciado"]);
    expect(g.foraDaComposicao.map((l) => l.vereadorId)).toEqual(["orfao"]);
    // exatamente 4 linhas ao todo, nenhuma duplicada nem perdida
    const total = g.mesa.length + g.casa.length + g.licenciados.length + g.foraDaComposicao.length;
    expect(total).toBe(4);
  });

  it("semAssento GANHA de tudo — mesmo com cargoMesa ou estado licenciado, vai para foraDaComposicao", () => {
    const g1 = agruparLinhas([linha({ semAssento: true, cargoMesa: "presidente" })]);
    expect(g1.foraDaComposicao).toHaveLength(1);
    expect(g1.mesa).toHaveLength(0);

    const g2 = agruparLinhas([linha({ semAssento: true, estado: "licenciado" })]);
    expect(g2.foraDaComposicao).toHaveLength(1);
    expect(g2.licenciados).toHaveLength(0);
  });
});

// ---------- 3. contarLocal — NÃO é o quórum ----------

describe("contarLocal", () => {
  it("conta presentes por modalidade e o total, localmente", () => {
    const linhas = [
      linha({ vereadorId: "1", estado: "presente-plenario" }),
      linha({ vereadorId: "2", estado: "presente-plenario" }),
      linha({ vereadorId: "3", estado: "presente-remoto" }),
      linha({ vereadorId: "4", estado: "ausente" }),
      linha({ vereadorId: "5", estado: "licenciado" }),
    ];
    expect(contarLocal(linhas)).toEqual({ presentesPlenario: 2, presentesRemoto: 1, presentesTotal: 3 });
  });

  it("DIVERGE do quorum do servidor por construção — contarLocal não reconcilia com ChamadaQuorumOut", () => {
    // cenário real: o operador marcou 3 presentes na tela, mas o servidor (que já viu um evento
    // 'sem-assento' fora do roster) publica um numerador diferente. contarLocal não tenta bater.
    const linhas = [
      linha({ vereadorId: "1", estado: "presente-plenario" }),
      linha({ vereadorId: "2", estado: "presente-plenario" }),
      linha({ vereadorId: "3", estado: "presente-remoto" }),
    ];
    const quorumDoServidor: ChamadaQuorumOut = {
      presentesPlenario: 2,
      presentesRemoto: 1,
      presentesTotal: 4, // inclui 1 linha sem-assento que não está nem em `linhas` desta amostra
      membrosDaCasa: 9,
      presencasForaDoRoster: 1,
    };
    const local = contarLocal(linhas);
    expect(local.presentesTotal).toBe(3);
    expect(local.presentesTotal).not.toBe(quorumDoServidor.presentesTotal);
    // a função não devolve nem consulta membrosDaCasa — não é campo do retorno de contarLocal
    expect(local).not.toHaveProperty("membrosDaCasa");
  });
});

// ---------- 4. precedenciaDeEstado ----------

describe("precedenciaDeEstado", () => {
  it("é a lista dos 6 estados, como DADO exportado", () => {
    expect(precedenciaDeEstado).toEqual([
      "presente-plenario",
      "presente-remoto",
      "ausente-justificado",
      "ausente-justificativa-pendente",
      "ausente",
      "licenciado",
    ]);
  });

  it("'justificativa pendente de decisão' NUNCA colapsa com falta injustificada", () => {
    const iPendente = precedenciaDeEstado.indexOf("ausente-justificativa-pendente");
    const iAusente = precedenciaDeEstado.indexOf("ausente");
    expect(iPendente).not.toBe(-1);
    expect(iAusente).not.toBe(-1);
    expect(iPendente).not.toBe(iAusente);
    expect(iPendente).toBeLessThan(iAusente); // pendente vem ANTES — ainda não é falta
  });
});

// ---------- 5. diffParaLote ----------

describe("diffParaLote", () => {
  it("só entram linhas cujo estado REALMENTE mudou", () => {
    const linhas = [
      linha({ vereadorId: "1", estado: "ausente" }),
      linha({ vereadorId: "2", estado: "presente-plenario" }),
    ];
    const marcacoes: MarcacoesPendentes = {
      "1": { estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" }, // mudou
      "2": { estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" }, // marcar de novo o MESMO estado
    };
    const r = diffParaLote(linhas, marcacoes);
    expect(r.ok).toBe(true);
    if (!r.ok) throw new Error("unreachable");
    expect(r.registros).toHaveLength(1);
    expect(r.registros[0]).toEqual({
      vereadorId: "1",
      tipo: "entrada",
      modalidade: "plenario",
      ocorridoEm: "2026-05-21T22:00:00Z",
    });
  });

  it("linhas licenciado e semAssento NUNCA entram no lote, mesmo se marcadas", () => {
    const linhas = [
      linha({ vereadorId: "1", estado: "licenciado" }),
      linha({ vereadorId: "2", estado: "ausente", semAssento: true }),
    ];
    const marcacoes: MarcacoesPendentes = {
      "1": { estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" },
      "2": { estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" },
    };
    const r = diffParaLote(linhas, marcacoes);
    expect(r.ok).toBe(true);
    if (!r.ok) throw new Error("unreachable");
    expect(r.registros).toHaveLength(0);
  });

  it("presente-plenario -> presente-remoto vira 'mudanca_modalidade' (não saída+entrada)", () => {
    const linhas = [linha({ vereadorId: "1", estado: "presente-plenario" })];
    const marcacoes: MarcacoesPendentes = {
      "1": { estadoAlvo: "presente-remoto", desde: "2026-05-21T22:05:00Z" },
    };
    const r = diffParaLote(linhas, marcacoes);
    if (!r.ok) throw new Error("unreachable");
    expect(r.registros[0]).toMatchObject({ tipo: "mudanca_modalidade", modalidade: "remoto" });
  });

  it("presente -> ausente vira 'saida', carregando a modalidade que a linha tinha", () => {
    const linhas = [linha({ vereadorId: "1", estado: "presente-remoto" })];
    const marcacoes: MarcacoesPendentes = {
      "1": { estadoAlvo: "ausente", desde: "2026-05-21T23:00:00Z" },
    };
    const r = diffParaLote(linhas, marcacoes);
    if (!r.ok) throw new Error("unreachable");
    expect(r.registros[0]).toMatchObject({ tipo: "saida", modalidade: "remoto" });
  });

  it("ausente-justificado -> presente-plenario vira 'entrada'", () => {
    const linhas = [linha({ vereadorId: "1", estado: "ausente-justificado" })];
    const marcacoes: MarcacoesPendentes = {
      "1": { estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" },
    };
    const r = diffParaLote(linhas, marcacoes);
    if (!r.ok) throw new Error("unreachable");
    expect(r.registros[0]).toMatchObject({ tipo: "entrada", modalidade: "plenario" });
  });

  it("respeita o teto de 200 linhas do servidor — acima disso, erro explícito, nunca truncar", () => {
    const linhas = Array.from({ length: TETO_LOTE_PRESENCA + 1 }, (_, i) => linha({ vereadorId: `v${i}`, estado: "ausente" }));
    const marcacoes: MarcacoesPendentes = Object.fromEntries(
      linhas.map((l) => [l.vereadorId, { estadoAlvo: "presente-plenario" as const, desde: "2026-05-21T22:00:00Z" }]),
    );
    const r = diffParaLote(linhas, marcacoes);
    expect(r.ok).toBe(false);
    if (r.ok) throw new Error("unreachable");
    expect(r.erro).toMatch(/200/);
  });

  it("registra o instante do FATO (desde), não um relógio interno — retroativo é aceito sem questionar", () => {
    const linhas = [linha({ vereadorId: "1", estado: "ausente" })];
    const marcacoes: MarcacoesPendentes = {
      "1": { estadoAlvo: "presente-plenario", desde: "2026-05-21T20:00:00Z" }, // duas horas atrás
    };
    const r = diffParaLote(linhas, marcacoes);
    if (!r.ok) throw new Error("unreachable");
    expect(r.registros[0].ocorridoEm).toBe("2026-05-21T20:00:00Z");
  });

  it("linha sem marcação pendente não gera registro", () => {
    const linhas = [linha({ vereadorId: "1", estado: "ausente" }), linha({ vereadorId: "2", estado: "ausente" })];
    const marcacoes: MarcacoesPendentes = { "1": { estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" } };
    const r = diffParaLote(linhas, marcacoes);
    if (!r.ok) throw new Error("unreachable");
    expect(r.registros.map((x) => x.vereadorId)).toEqual(["1"]);
  });
});

// ---------- 6. aplicarOtimista / reverterOtimista ----------

describe("aplicarOtimista / reverterOtimista", () => {
  it("marca todas as linhas editáveis como presente-plenario, exceto licenciado/semAssento", () => {
    const linhas = [
      linha({ vereadorId: "1", estado: "ausente" }),
      linha({ vereadorId: "2", estado: "licenciado" }),
      linha({ vereadorId: "3", estado: "ausente", semAssento: true }),
    ];
    const { marcacoes } = aplicarOtimista(linhas, {}, "2026-05-21T22:00:00Z");
    expect(marcacoes["1"]).toEqual({ estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" });
    expect(marcacoes["2"]).toBeUndefined();
    expect(marcacoes["3"]).toBeUndefined();
  });

  it("reverterOtimista restaura o estado EXATO anterior, linha a linha — inclusive 'sem marcação nenhuma'", () => {
    const linhas = [linha({ vereadorId: "1", estado: "ausente" }), linha({ vereadorId: "2", estado: "ausente" })];
    // vereador 2 já tinha uma marcação pendente ANTES da ação em massa; vereador 1 não tinha nenhuma
    const marcacoesAntes: MarcacoesPendentes = {
      "2": { estadoAlvo: "ausente-justificado" as never, desde: "2026-05-21T21:00:00Z" },
    };
    const { marcacoes: depois, snapshot } = aplicarOtimista(linhas, marcacoesAntes, "2026-05-21T22:00:00Z");
    expect(depois["1"]).toEqual({ estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" });
    expect(depois["2"]).toEqual({ estadoAlvo: "presente-plenario", desde: "2026-05-21T22:00:00Z" });

    const revertido = reverterOtimista(depois, snapshot);
    expect(revertido["1"]).toBeUndefined(); // não existia antes -> some, não vira um valor inventado
    expect(revertido["2"]).toEqual(marcacoesAntes["2"]); // volta EXATAMENTE ao que era
  });

  it("não altera marcações de linhas fora do lote em massa (ex.: já marcadas manualmente antes)", () => {
    const linhas = [linha({ vereadorId: "1", estado: "ausente" })];
    const marcacoesAntes: MarcacoesPendentes = {
      "outra-linha-fora-desta-chamada": { estadoAlvo: "presente-remoto", desde: "2026-05-21T21:30:00Z" },
    };
    const { marcacoes } = aplicarOtimista(linhas, marcacoesAntes, "2026-05-21T22:00:00Z");
    expect(marcacoes["outra-linha-fora-desta-chamada"]).toEqual(marcacoesAntes["outra-linha-fora-desta-chamada"]);
  });
});

// ---------- 7. podeEditar ----------

describe("podeEditar", () => {
  it("allowlist: agendada, aberta, suspensa são editáveis", () => {
    expect(podeEditar("agendada")).toBe(true);
    expect(podeEditar("aberta")).toBe(true);
    expect(podeEditar("suspensa")).toBe(true);
  });

  it("encerrada/arquivada/nao_realizada NÃO são editáveis", () => {
    expect(podeEditar("encerrada")).toBe(false);
    expect(podeEditar("arquivada")).toBe(false);
    expect(podeEditar("nao_realizada")).toBe(false);
  });

  it("um estado DESCONHECIDO/novo cai em NÃO-editável — allowlist, nunca complemento (fail-open é do backend, não daqui)", () => {
    expect(podeEditar("um-estado-que-ainda-nao-existe")).toBe(false);
    expect(podeEditar("")).toBe(false);
  });
});

// ---------- carry: narrowing de justificativa (o codegen perdeu a forma) ----------

describe("lerJustificativa", () => {
  it("aceita a forma real do wire ({estado, motivo, decididoEm})", () => {
    const cru = { estado: "pendente", motivo: "atestado médico", decididoEm: null };
    expect(lerJustificativa(cru)).toEqual({ estado: "pendente", motivo: "atestado médico", decididoEm: null });
  });

  it("null passa direto", () => {
    expect(lerJustificativa(null)).toBeNull();
  });

  it("forma inválida (campo faltando/tipo errado) devolve null — fail-closed, não lança", () => {
    expect(lerJustificativa({ estado: "inventado", motivo: "x", decididoEm: null })).toBeNull();
    expect(lerJustificativa({ estado: "pendente", motivo: 123, decididoEm: null })).toBeNull();
    expect(lerJustificativa({ estado: "pendente" })).toBeNull();
  });
});
