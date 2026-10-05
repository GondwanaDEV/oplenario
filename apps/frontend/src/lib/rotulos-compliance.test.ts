import { describe, expect, it } from "vitest";
import { rotularEstadoRemessa, rotularObrigacao } from "./rotulos-compliance";

describe("rotularEstadoRemessa — o ciclo da remessa ao TCE em palavras", () => {
  // Vocabulário da FONTE: `compliance.logic/estados-remessa` (rascunho -> validada -> submetida ->
  // {aceita | rejeitada}).
  it.each([
    ["rascunho", "Em preparação"],
    ["validada", "Validada, pronta para enviar"],
    ["submetida", "Enviada, aguardando o TCE"],
    ["aceita", "Aceita pelo TCE"],
    ["rejeitada", "Rejeitada pelo TCE"],
  ])("%s -> %s", (estado, rotulo) => {
    expect(rotularEstadoRemessa(estado)).toBe(rotulo);
  });

  it("fail-closed: estado fora do ciclo devolve o próprio valor", () => {
    expect(rotularEstadoRemessa("devolvida")).toBe("devolvida");
  });
});

describe("rotularObrigacao — a obrigação de compliance em palavras", () => {
  it.each([
    ["audiencia_metas_fiscais", "Audiência de metas fiscais (LRF)"],
    ["julgamento_contas_prefeito", "Julgamento das contas do Prefeito"],
    ["remessa_mensal_sim", "Remessa mensal ao SIM (TCE-CE)"],
  ])("%s -> %s", (chave, rotulo) => {
    expect(rotularObrigacao(chave)).toBe(rotulo);
  });

  it("fail-closed: regra desconhecida devolve a própria chave, nunca um nome inventado", () => {
    expect(rotularObrigacao("remessa_bimestral")).toBe("remessa_bimestral");
  });
});
