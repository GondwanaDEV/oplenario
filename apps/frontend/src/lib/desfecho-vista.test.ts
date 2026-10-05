import { describe, expect, it } from "vitest";
import { categoriaDoDesfecho, rotuloDoAto, situacaoDoDesfecho } from "./desfecho-vista";
import { derivarTramitacao } from "./tramitacao-vista";

// docs/16, retriagem linhas 18 e 30: o desfecho depois do plenário (aprovação, autógrafo, sanção/veto, lei)

describe("situacaoDoDesfecho", () => {
  it("matéria aprovada deixa de dizer 'Aguardando pauta'", () => {
    expect(derivarTramitacao("aguardando_pauta").rotuloSituacao).toBe("Aguardando pauta");
    expect(derivarTramitacao("aguardando_pauta", "aprovada").rotuloSituacao).toBe("Aprovada em plenário");
  });

  it("a lei publicada fecha a faixa inteira", () => {
    const r = situacaoDoDesfecho("publicada");
    expect(r?.rotuloSituacao).toBe("Virou lei");
    expect(r?.estagios.every((e) => e.situacao === "concluido")).toBe(true);
  });

  it("à espera do Executivo, a Sanção está em andamento", () => {
    const r = situacaoDoDesfecho("autografo_enviado");
    expect(r?.rotuloSituacao).toBe("Enviada ao Executivo");
    expect(r?.estagios.map((e) => e.situacao)).toEqual(["concluido", "concluido", "concluido", "concluido", "ativo"]);
  });

  it("sem desfecho ou desfecho desconhecido, a situação vem do rito", () => {
    expect(situacaoDoDesfecho(null)).toBeNull();
    expect(situacaoDoDesfecho("ato_de_uma_versao_futura")).toBeNull();
    expect(derivarTramitacao("em_comissoes", "ato_de_uma_versao_futura").rotuloSituacao).toBe("Em comissões");
  });
});

describe("rotuloDoAto", () => {
  it("cada ato em palavras, com o número quando há", () => {
    expect(rotuloDoAto({ ato: "aprovada" })).toBe("Aprovada em plenário");
    expect(rotuloDoAto({ ato: "aprovada", redacaoFinal: true })).toBe("Redação final aprovada em plenário");
    expect(rotuloDoAto({ ato: "autografo_enviado", numero: 8, ano: 2026 })).toBe("Autógrafo nº 8/2026 enviado ao Executivo");
    expect(rotuloDoAto({ ato: "vetado" })).toBe("Vetada pelo Executivo");
    expect(rotuloDoAto({ ato: "veto_derrubado" })).toBe("Veto derrubado pela Câmara");
    expect(rotuloDoAto({ ato: "promulgada", tipoNorma: "lei", numero: 5, ano: 2026 })).toBe("Promulgação: Lei nº 5/2026");
    expect(rotuloDoAto({ ato: "publicada", tipoNorma: "emenda_lom", numero: 1, ano: 2026 })).toBe(
      "Publicação: Emenda à Lei Orgânica nº 1/2026",
    );
    expect(rotuloDoAto({ ato: "desconhecido" })).toBeNull();
  });
});

describe("categoriaDoDesfecho", () => {
  it("lei ou a caminho dela = aprovada; sem lei = arquivada; à espera do Executivo = tram", () => {
    expect(categoriaDoDesfecho("publicada")).toBe("aprovada");
    expect(categoriaDoDesfecho("rejeitada")).toBe("arquivada");
    expect(categoriaDoDesfecho("veto_mantido")).toBe("arquivada");
    expect(categoriaDoDesfecho("vetado")).toBe("tram");
    expect(categoriaDoDesfecho(null)).toBeNull();
  });
});
