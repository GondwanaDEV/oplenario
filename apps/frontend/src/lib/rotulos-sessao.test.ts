import { describe, expect, it } from "vitest";
import { nomeDaSessao, nomeDaSessaoNoCabecalho, nomeFase, nomeTipoFala, nomeTipoSessao } from "./rotulos-sessao";

describe("nomeTipoSessao — o tipo da sessao no cabecalho do telao", () => {
  // Valores da FONTE — CHECK de `sessoes.sessao.tipo_sessao`:
  //   CHECK (tipo_sessao IN ('ordinaria','extraordinaria','solene','secreta','especial','audiencia_publica'))
  it.each([
    ["ordinaria", "ordinária"],
    ["extraordinaria", "extraordinária"],
    ["solene", "solene"],
    ["secreta", "secreta"],
    ["especial", "especial"],
    ["audiencia_publica", "audiência pública"],
  ])("%s -> %s", (chave, rotulo) => {
    expect(nomeTipoSessao(chave)).toBe(rotulo);
  });

  it("fail-closed: tipo desconhecido devolve a propria chave, nunca lanca", () => {
    expect(nomeTipoSessao("tipo_de_regimento_novo")).toBe("tipo_de_regimento_novo");
  });

  it("nulo/vazio -> string vazia", () => {
    expect(nomeTipoSessao(null)).toBe("");
    expect(nomeTipoSessao(undefined)).toBe("");
  });
});

describe("nomeFase — a fase do rito no telao", () => {
  // Mesmo motivo do tipo de sessao acima: o backend transporta a CHAVE (`ordem_do_dia`). A tribuna do
  // plenario renderizava essa chave crua — e como o CSS da faixa poe em maiuscula, o publico via
  // "ORDEM_DO_DIA" no painel. O mapa ja existia DENTRO de plenario/page.tsx (NOME_FASE), aplicado so'
  // aos itens de pauta; a tribuna nao o chamava. Trazer para ca' da' uma fonte unica e testavel, no
  // modulo que ja e' o dono dos rotulos deste dominio.
  //
  // Valores da FONTE (contrato gerado, lib/contrato-sessoes.gen.ts, campo `fase`):
  //   "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao"
  it.each([
    ["expediente", "Expediente"],
    ["grande_expediente", "Grande Expediente"],
    ["ordem_do_dia", "Ordem do Dia"],
    ["explicacoes_pessoais", "Explicações Pessoais"],
    ["tribuna_livre_cidadao", "Tribuna Livre"],
  ])("%s -> %s", (chave, rotulo) => {
    expect(nomeFase(chave)).toBe(rotulo);
  });

  it("fail-closed: fase desconhecida devolve a propria chave, nunca lanca", () => {
    expect(nomeFase("fase_nova_de_algum_regimento")).toBe("fase_nova_de_algum_regimento");
  });

  it("nulo/vazio -> string vazia (mesma convencao de nomeTipoSessao)", () => {
    expect(nomeFase(null)).toBe("");
    expect(nomeFase(undefined)).toBe("");
  });
});

describe("nomeTipoFala — o tipo da fala na tribuna", () => {
  // Terceiro enum do mesmo domínio que vazava para o telão. "principal" enganava por coincidir com
  // português correto; "pela_ordem"/"questao_de_ordem" saíam com underscore à vista do público.
  //
  // Valores da FONTE — `sessoes/logic.clj`, `(def tipos-fala ...)`:
  //   #{"principal" "aparte" "pela_ordem" "questao_de_ordem" "explicacao_pessoal" "comunicado"}
  it.each([
    ["principal", "Fala principal"],
    ["aparte", "Aparte"],
    ["pela_ordem", "Pela ordem"],
    ["questao_de_ordem", "Questão de ordem"],
    ["explicacao_pessoal", "Explicação pessoal"],
    ["comunicado", "Comunicado"],
  ])("%s -> %s", (chave, rotulo) => {
    expect(nomeTipoFala(chave)).toBe(rotulo);
  });

  it("fail-closed: tipo desconhecido devolve a propria chave, nunca lanca", () => {
    expect(nomeTipoFala("tipo_de_fala_novo")).toBe("tipo_de_fala_novo");
  });

  it("nulo/vazio -> string vazia", () => {
    expect(nomeTipoFala(null)).toBe("");
    expect(nomeTipoFala(undefined)).toBe("");
  });
});

describe("nomeDaSessao — o nome da sessao numerada em todas as telas", () => {
  it("sessao comum: numero ordinal + Sessao + tipo com inicial maiuscula", () => {
    expect(nomeDaSessao(15, "ordinaria")).toBe("15ª Sessão Ordinária");
    expect(nomeDaSessao(2, "extraordinaria")).toBe("2ª Sessão Extraordinária");
  });

  it("audiencia publica nao vira 'Sessao Audiencia publica': e' o nome do cabecalho da Mesa", () => {
    expect(nomeDaSessao(2, "audiencia_publica")).toBe("Audiência pública nº 2");
  });

  it("tipo desconhecido sai pela chave, sem sumir", () => {
    expect(nomeDaSessao(1, "itinerante")).toBe("1ª Sessão Itinerante");
  });
});

describe("nomeDaSessaoNoCabecalho — o nome da sessão no cabeçalho da chamada, da ata e da transcrição", () => {
  it("como o Comando da Mesa: tipo em palavras e número", () => {
    expect(nomeDaSessaoNoCabecalho({ tipoSessao: "ordinaria", numeroSequencial: 15 })).toBe("Sessão ordinária nº 15");
    expect(nomeDaSessaoNoCabecalho({ tipoSessao: "extraordinaria", numeroSequencial: 2 })).toBe("Sessão extraordinária nº 2");
  });

  it("a audiência pública tem nome próprio", () => {
    expect(nomeDaSessaoNoCabecalho({ tipoSessao: "audiencia_publica", numeroSequencial: 3 })).toBe("Audiência pública nº 3");
  });

  it("sem número ou sem tipo, nada (não se inventa nome)", () => {
    expect(nomeDaSessaoNoCabecalho({ tipoSessao: "ordinaria", numeroSequencial: null })).toBeNull();
    expect(nomeDaSessaoNoCabecalho({ tipoSessao: null, numeroSequencial: 4 })).toBeNull();
    expect(nomeDaSessaoNoCabecalho(null)).toBeNull();
    expect(nomeDaSessaoNoCabecalho(undefined)).toBeNull();
  });
});
