import { describe, expect, it } from "vitest";
import { derivarTramitacao, descreverFaixa, faixaDoRito, rotularEstado } from "./tramitacao-vista";
import type { RitoDaMateriaOut } from "./contrato-legislativo.gen";

// Task 0.5 (Fatia A2.0, Portal do Cidadão). Vocabulário real confirmado por grep — ver o mapa documentado
// no topo de tramitacao-vista.ts: `estado` de proposicao é :string LIVRE, template-driven POR CÂMARA
// (F3.3), não um enum fechado em código. Os nomes usados aqui (protocolada/em_comissoes/segundo_turno/
// aprovada) são o rito ILUSTRATIVO que aparece nos fixtures reais de teste do backend
// (tramitacao_db_test.clj, portal_test.clj) + a faixa de 5 estágios do design-system. Fail-closed é o
// contrato para qualquer estado fora deste mapa (tenant real terá vocabulário próprio via template).

describe("derivarTramitacao", () => {
  it("protocolada -> estágio Protocolo ativo, resto pendente", () => {
    const r = derivarTramitacao("protocolada");
    expect(r.estagios).toEqual([
      { rotulo: "Protocolo", situacao: "ativo" },
      { rotulo: "Comissões", situacao: "pendente" },
      { rotulo: "1º turno", situacao: "pendente" },
      { rotulo: "2º turno", situacao: "pendente" },
      { rotulo: "Sanção", situacao: "pendente" },
    ]);
  });

  it("estado intermediário (2º turno) -> Protocolo/Comissões/1º concluídos, 2º ativo, Sanção pendente", () => {
    const r = derivarTramitacao("segundo_turno");
    expect(r.estagios).toEqual([
      { rotulo: "Protocolo", situacao: "concluido" },
      { rotulo: "Comissões", situacao: "concluido" },
      { rotulo: "1º turno", situacao: "concluido" },
      { rotulo: "2º turno", situacao: "ativo" },
      { rotulo: "Sanção", situacao: "pendente" },
    ]);
  });

  it("aprovada -> todos concluídos, rotuloSituacao 'Aprovado'", () => {
    const r = derivarTramitacao("aprovada");
    expect(r.estagios.every((e) => e.situacao === "concluido")).toBe(true);
    expect(r.rotuloSituacao).toBe("Aprovado");
  });

  // DECISÃO REVISTA (07/09/2026, caminhada de prontidão de apresentação). Estado fora do vocabulário
  // ilustrativo: o rótulo da situação é HUMANIZADO, nunca a chave crua (com o rito real da Casa de
  // demonstração, `aguardando_pauta` aparecia como `AGUARDANDO_PAUTA`). REVISTA DE NOVO (05/10/2026): a
  // faixa também não pode apontar etapa — a anterior marcava "Protocolo" ativo para QUALQUER estado
  // desconhecido, e a matéria que esperava pauta aparecia "no Protocolo". Agora é um bloco neutro.
  it("fail-closed: estado fora do vocabulário -> faixa neutra 'Em tramitação' (nenhuma etapa do rito marcada), sem throw, rótulo HUMANIZADO", () => {
    expect(() => derivarTramitacao("xpto_desconhecido")).not.toThrow();
    const r = derivarTramitacao("xpto_desconhecido");
    expect(r.estagios).toEqual([{ rotulo: "Em tramitação", situacao: "ativo" }]);
    expect(r.rotuloSituacao).toBe("Xpto desconhecido");
  });

  it("estado desconhecido nunca marca Protocolo (nem nenhuma etapa nomeada do rito) como ativo", () => {
    for (const estado of ["xpto_desconhecido", "em_revisao_redacao", "2a_leitura", "sancionado", ""]) {
      const nomes = derivarTramitacao(estado).estagios.map((e) => e.rotulo);
      expect(nomes).not.toContain("Protocolo");
    }
  });

  // Chaves REAIS do rito ordinário da Casa de demonstração (apps/backend/demo/acervo.clj `estados-rito`).
  it("aguardando_pauta (chave real da demo): Protocolo e Comissões concluídos, nenhuma etapa em andamento — nunca volta ao Protocolo", () => {
    const r = derivarTramitacao("aguardando_pauta");
    expect(r.estagios).toEqual([
      { rotulo: "Protocolo", situacao: "concluido" },
      { rotulo: "Comissões", situacao: "concluido" },
      { rotulo: "1º turno", situacao: "pendente" },
      { rotulo: "2º turno", situacao: "pendente" },
      { rotulo: "Sanção", situacao: "pendente" },
    ]);
    expect(r.rotuloSituacao).toBe("Aguardando pauta");
  });

  it("só 'protocolada' tem o Protocolo como etapa ativa, entre as chaves reais da demo", () => {
    const ativas = (estado: string) => derivarTramitacao(estado).estagios.filter((e) => e.situacao === "ativo").map((e) => e.rotulo);
    expect(ativas("protocolada")).toEqual(["Protocolo"]);
    for (const estado of ["em_comissoes", "aguardando_pauta", "em_pauta", "aprovada", "arquivada"]) {
      expect(ativas(estado)).not.toContain("Protocolo");
    }
  });

  it("o estado real do rito da Casa não vaza cru — foi o defeito #9/#10 do ledger", () => {
    expect(derivarTramitacao("aguardando_pauta").rotuloSituacao).toBe("Aguardando pauta");
  });

  it("nenhum rótulo de situação contém underscore, em nenhum estado", () => {
    // Detector: é o underscore que denuncia chave de enum na tela. Se um estado novo entrar e vazar,
    // este teste reprova sem precisar que alguém lembre de olhar a tela.
    for (const estado of ["protocolada", "em_comissoes", "aguardando_pauta", "em_pauta", "aprovada",
                          "arquivada", "primeiro_turno", "segundo_turno", "em_sancao", "estado_novo_qualquer"]) {
      expect(derivarTramitacao(estado).rotuloSituacao).not.toMatch(/_/);
    }
  });
});

// Task de review A2.1 (item 3, a11y) — `descreverFaixa` monta o rótulo ARIA completo da AzulejoFaixa.
// `role="img"` esconde os <text> por-estágio do SVG dos leitores de tela; sem isto, AT perde a
// progressão concluído/atual/pendente que usuários videntes veem no grafismo. Helper puro, testado
// isoladamente e reusado pelo caller (DestaqueTramitacao) em vez de string-building inline.
describe("descreverFaixa", () => {
  it("estado intermediário -> lista concluídos, nomeia o atual, lista os pendentes", () => {
    const estagios = derivarTramitacao("segundo_turno").estagios;
    expect(descreverFaixa("PL 042/2026", estagios)).toBe(
      "Tramitação de PL 042/2026: concluídos Protocolo, Comissões, 1º turno; atual 2º turno; pendente Sanção.",
    );
  });

  it("protocolada (início) -> sem concluídos, só atual + pendentes", () => {
    const estagios = derivarTramitacao("protocolada").estagios;
    expect(descreverFaixa("PL 001/2026", estagios)).toBe(
      "Tramitação de PL 001/2026: atual Protocolo; pendente Comissões, 1º turno, 2º turno, Sanção.",
    );
  });

  it("aprovada -> tudo concluído, sem cláusula de atual nem de pendente", () => {
    const estagios = derivarTramitacao("aprovada").estagios;
    expect(descreverFaixa("PL 007/2026", estagios)).toBe(
      "Tramitação de PL 007/2026: concluídos Protocolo, Comissões, 1º turno, 2º turno, Sanção.",
    );
  });

  it("fail-closed (faixa neutra, estado desconhecido) -> só o bloco único 'Em tramitação', sem throw", () => {
    const estagios = derivarTramitacao("xpto-desconhecido").estagios;
    expect(() => descreverFaixa("PL 099/2026", estagios)).not.toThrow();
    expect(descreverFaixa("PL 099/2026", estagios)).toBe("Tramitação de PL 099/2026: atual Em tramitação.");
  });

  it("aguardando_pauta -> concluídos Protocolo e Comissões, sem atual", () => {
    const estagios = derivarTramitacao("aguardando_pauta").estagios;
    expect(descreverFaixa("PL 010/2026", estagios)).toBe(
      "Tramitação de PL 010/2026: concluídos Protocolo, Comissões; pendente 1º turno, 2º turno, Sanção.",
    );
  });
});

// Item 2 do lote 05/10: ficha, lista, quadro e painel da Mesa leem o rótulo da MESMA função.
describe("rotularEstado", () => {
  it("é o mesmo rótulo que a faixa mostra, para as chaves reais da demo", () => {
    for (const estado of ["protocolada", "em_comissoes", "aguardando_pauta", "em_pauta", "aprovada", "arquivada"]) {
      expect(rotularEstado(estado)).toBe(derivarTramitacao(estado).rotuloSituacao);
    }
  });

  it("humaniza o que não conhece, sem a chave crua", () => {
    expect(rotularEstado("sancionado")).toBe("Sancionado");
    expect(rotularEstado("em_revisao_redacao")).toBe("Em revisao redacao");
  });
});

// A faixa "Onde está a matéria" vem do RITO da Casa (rota da ficha), não de uma lista de nomes no front. O vocabulário
// das fixtures é o de uma Casa fora da demo: nenhum nome abaixo consta do mapa fixo de `derivarTramitacao`.
const etapa = (chave: string, rotulo: string, terminal = false) => ({ chave, rotulo, terminal });

const ritoEmLinha = (atual: string): RitoDaMateriaOut => {
  const linha = [etapa("entrada", "Entrada"), etapa("instrucao", "Instrução"), etapa("plenario_unico", "Plenário único")];
  const rejeitada = etapa("rejeitada", "Rejeitada", true);
  const a = [...linha, rejeitada].find((e) => e.chave === atual) ?? null;
  return {
    ordemUnica: true,
    etapas: atual === "rejeitada" ? [...linha, rejeitada] : linha,
    atual: a,
    anteriores: null,
    proximas: [],
  };
};

describe("faixaDoRito", () => {
  it("com ordem única: as etapas são as do rito, na ordem dele, com o nome que a Casa deu", () => {
    const f = faixaDoRito(ritoEmLinha("instrucao"));
    expect(f?.estagios).toEqual([
      { chave: "entrada", rotulo: "Entrada", situacao: "concluido" },
      { chave: "instrucao", rotulo: "Instrução", situacao: "ativo" },
      { chave: "plenario_unico", rotulo: "Plenário único", situacao: "pendente" },
    ]);
  });

  it("estado que o mapa fixo do front não conhece: a faixa MARCA a etapa (antes era um bloco neutro sem posição)", () => {
    expect(derivarTramitacao("instrucao").estagios).toEqual([{ rotulo: "Em tramitação", situacao: "ativo" }]);
    const f = faixaDoRito(ritoEmLinha("instrucao"));
    expect(f?.estagios.map((e) => e.situacao)).toEqual(["concluido", "ativo", "pendente"]);
  });

  it("nunca usa as etapas ilustrativas do design system (Protocolo, 1º turno, Sanção…) quando o rito é da Casa", () => {
    const rotulos = faixaDoRito(ritoEmLinha("plenario_unico"))?.estagios.map((e) => e.rotulo) ?? [];
    for (const antigo of ["Protocolo", "Comissões", "1º turno", "2º turno", "Sanção"]) {
      expect(rotulos).not.toContain(antigo);
    }
  });

  it("desfecho (terminal) como etapa atual: o processo acabou — tudo concluído, a faixa fecha no desfecho", () => {
    const f = faixaDoRito(ritoEmLinha("rejeitada"));
    expect(f?.estagios.map((e) => e.rotulo)).toEqual(["Entrada", "Instrução", "Plenário único", "Rejeitada"]);
    expect(f?.estagios.every((e) => e.situacao === "concluido")).toBe(true);
  });

  it("sem ordem única: só o entorno — anteriores concluídas, atual, próximas possíveis marcadas como alternativa", () => {
    const rito: RitoDaMateriaOut = {
      ordemUnica: false,
      etapas: [],
      atual: etapa("b", "Análise B"),
      anteriores: [etapa("a", "Recebida")],
      proximas: [etapa("c", "Via C"), etapa("d", "Via D")],
    };
    expect(faixaDoRito(rito)?.estagios).toEqual([
      { chave: "a", rotulo: "Recebida", situacao: "concluido" },
      { chave: "b", rotulo: "Análise B", situacao: "ativo" },
      { chave: "c", rotulo: "Via C", situacao: "pendente", alternativa: true },
      { chave: "d", rotulo: "Via D", situacao: "pendente", alternativa: true },
    ]);
  });

  it("sem ordem única e sem histórico confiável (anteriores nulo): não afirma por onde passou", () => {
    const rito: RitoDaMateriaOut = {
      ordemUnica: false,
      etapas: [],
      atual: etapa("b", "Análise B"),
      anteriores: null,
      proximas: [etapa("c", "Via C")],
    };
    expect(faixaDoRito(rito)?.estagios.map((e) => [e.rotulo, e.situacao])).toEqual([
      ["Análise B", "ativo"],
      ["Via C", "pendente"],
    ]);
  });

  it("uma só próxima possível não é 'alternativa': é o passo seguinte", () => {
    const rito: RitoDaMateriaOut = {
      ordemUnica: false,
      etapas: [],
      atual: etapa("b", "Análise B"),
      anteriores: [],
      proximas: [etapa("c", "Via C")],
    };
    expect(faixaDoRito(rito)?.estagios[1]).toEqual({ chave: "c", rotulo: "Via C", situacao: "pendente" });
  });

  it("sem rito, ou rito que não declara a etapa atual: null — quem chama mantém o comportamento anterior", () => {
    expect(faixaDoRito(null)).toBeNull();
    expect(faixaDoRito(undefined)).toBeNull();
    expect(faixaDoRito({ ...ritoEmLinha("instrucao"), atual: null })).toBeNull();
  });

  it("ordem única mas atual fora da linha (resposta incoerente): null, nunca marca a etapa errada", () => {
    expect(faixaDoRito({ ...ritoEmLinha("instrucao"), atual: etapa("outra", "Outra") })).toBeNull();
  });

  it("não lança com listas vazias", () => {
    expect(() => faixaDoRito({ ordemUnica: true, etapas: [], atual: etapa("x", "X"), anteriores: null, proximas: [] })).not.toThrow();
  });
});

describe("descreverFaixa com o rito da Casa", () => {
  it("lê concluídas, atual e pendente pelo nome da Casa", () => {
    const f = faixaDoRito(ritoEmLinha("instrucao"));
    expect(descreverFaixa("PL 5/2026", f?.estagios ?? [])).toBe(
      "Tramitação de PL 5/2026: concluídos Entrada; atual Instrução; pendente Plenário único.",
    );
  });

  it("alternativas viram 'próximas possíveis', nunca 'pendente' em fila", () => {
    const f = faixaDoRito({
      ordemUnica: false,
      etapas: [],
      atual: etapa("b", "Análise B"),
      anteriores: [etapa("a", "Recebida")],
      proximas: [etapa("c", "Via C"), etapa("d", "Via D")],
    });
    expect(descreverFaixa("PL 5/2026", f?.estagios ?? [])).toBe(
      "Tramitação de PL 5/2026: concluídos Recebida; atual Análise B; próximas possíveis Via C, Via D.",
    );
  });
});
