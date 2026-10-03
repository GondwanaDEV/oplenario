import { describe, expect, it } from "vitest";
import { rotularObrigacao } from "./rotulos-compliance";

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
