import { describe, expect, it } from "vitest";
import { nomeFase, nomeTipoSessao } from "./rotulos-sessao";

describe("nomeTipoSessao — o tipo da sessao no cabecalho do telao", () => {
  // Valores da FONTE — CHECK de `sessoes.sessao.tipo_sessao`:
  //   CHECK (tipo_sessao IN ('ordinaria','extraordinaria','solene','secreta','especial'))
  it.each([
    ["ordinaria", "ordinária"],
    ["extraordinaria", "extraordinária"],
    ["solene", "solene"],
    ["secreta", "secreta"],
    ["especial", "especial"],
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
