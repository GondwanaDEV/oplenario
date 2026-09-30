import { describe, expect, it } from "vitest";
import {
  avisoDeAlteracao,
  descreverRegra,
  estadoDoBotao,
  lerAntecedencia,
  mensagemDeRecusa,
  quando,
  seloDaPublicacao,
  textoDoAviso,
} from "./publicacao-pauta-vista";
import type { PublicacaoPautaOut } from "./contrato-sessoes.gen";

// ADR-0019 fatia 3: o view-model de publicar a pauta — o selo, o "alterada desde", os avisos em frase e o motivo do
// botão desabilitado, na ordem em que a pessoa resolve.

const T = "2026-10-06T17:30:00Z";

const pub = (extra: Partial<PublicacaoPautaOut> = {}): PublicacaoPautaOut => ({
  sessaoId: "s1",
  regra: { quemPublica: "secretaria", antecedenciaMinimaHoras: null, configurada: false },
  podePublicar: true,
  republicacao: false,
  itensNaPauta: 3,
  versoes: [],
  alteradaDesdeAPublicacao: false,
  avisos: [],
  avisosIndisponiveis: false,
  ...extra,
});

describe("publicacao-pauta-vista", () => {
  it("o selo diz a versão e quando, ou que ainda não foi publicada", () => {
    expect(seloDaPublicacao(null)).toBe("Pauta ainda não publicada");
    expect(seloDaPublicacao({ versao: 3, publicadaEm: T })).toBe(`Publicada v3 em ${quando(T)}`);
    expect(quando(T)).toMatch(/^\d{2}\/\d{2}\/2026 às \d{2}:\d{2}$/);
  });

  it("os avisos viram frase com o número da matéria; sem resumo, um rótulo honesto", () => {
    const proposicao = { tipo: "projeto_lei", ano: 2026, sequencial: 12, ementa: "x" };
    expect(textoDoAviso({ tipo: "sem-parecer-comissao", proposicao, pareceresEmAndamento: 1 })).toBe(
      "PL 12/2026: sem parecer da comissão (1 em andamento).",
    );
    expect(textoDoAviso({ tipo: "pedido-juridico-pendente", proposicao })).toBe("PL 12/2026: pedido de parecer jurídico ainda pendente.");
    expect(textoDoAviso({ tipo: "sem-parecer-comissao" })).toBe("Matéria da pauta: sem parecer da comissão.");
    expect(textoDoAviso({ tipo: "antecedencia-nao-cumprida", minimoHoras: 48, horasReais: 24 })).toBe(
      "Fora da antecedência mínima da Casa: 48 h exigidas, faltam 24 h para o início.",
    );
    expect(textoDoAviso({ tipo: "antecedencia-nao-cumprida", minimoHoras: 48, horasReais: -2 })).toMatch(/a sessão já começou/);
  });

  it("o botão: a regra da Casa primeiro, depois a pauta vazia, depois 'não mudou'", () => {
    expect(estadoDoBotao(pub())).toEqual({ habilitado: true, motivo: null });
    expect(estadoDoBotao(pub({ podePublicar: false, motivo: "Pela regra desta Casa, quem publica a pauta é o Presidente da Câmara." })))
      .toEqual({ habilitado: false, motivo: "Pela regra desta Casa, quem publica a pauta é o Presidente da Câmara." });
    expect(estadoDoBotao(pub({ itensNaPauta: 0 })).habilitado).toBe(false);
    const ultima = { versao: 2, tipoVersao: "republicacao" as const, publicadaEm: T, itens: 3 };
    expect(estadoDoBotao(pub({ ultima })).motivo).toBe("A pauta não mudou desde a publicação v2.");
    expect(estadoDoBotao(pub({ ultima, alteradaDesdeAPublicacao: true })).habilitado).toBe(true);
  });

  it("'alterada desde a publicação v3' só quando há publicação e a pauta viva mudou", () => {
    const ultima = { versao: 3, tipoVersao: "republicacao" as const, publicadaEm: T, itens: 3 };
    expect(avisoDeAlteracao({ ultima, alteradaDesdeAPublicacao: true })).toBe("Alterada desde a publicação v3");
    expect(avisoDeAlteracao({ ultima, alteradaDesdeAPublicacao: false })).toBeNull();
    expect(avisoDeAlteracao({ ultima: null, alteradaDesdeAPublicacao: false })).toBeNull();
  });

  it("a regra em uma linha", () => {
    expect(descreverRegra({ quemPublica: "presidente", antecedenciaMinimaHoras: 24, configurada: true })).toBe(
      "Nesta Casa, publica o Presidente da Câmara; antecedência mínima de 24 h antes da sessão.",
    );
    expect(descreverRegra({ quemPublica: "secretaria", antecedenciaMinimaHoras: null, configurada: false })).toBe(
      "Nesta Casa, publica a secretaria legislativa; sem antecedência mínima definida.",
    );
  });

  it("as recusas do servidor viram frase por motivo", () => {
    expect(mensagemDeRecusa(403)).toMatch(/regra desta Casa/);
    expect(mensagemDeRecusa(409, "justificativa-obrigatoria")).toBe("Republicar exige dizer o que mudou.");
    expect(mensagemDeRecusa(409, "pauta-mudou")).toMatch(/mudou enquanto/);
    expect(mensagemDeRecusa(409, undefined, "sessao ja fechada")).toBe("sessao ja fechada");
  });

  it("a antecedência digitada: vazio = sem regra; 1..720 inteiro; o resto é inválido", () => {
    expect(lerAntecedencia(" ")).toEqual({ ok: true, valor: null });
    expect(lerAntecedencia("48")).toEqual({ ok: true, valor: 48 });
    expect(lerAntecedencia("0")).toEqual({ ok: false });
    expect(lerAntecedencia("721")).toEqual({ ok: false });
    expect(lerAntecedencia("2.5")).toEqual({ ok: false });
  });
});
