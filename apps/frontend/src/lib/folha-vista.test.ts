import { describe, expect, it } from "vitest";
import { formatarHash, mensagemDeErroFolha, ordenarVersoes, retryAfterSegundos } from "./folha-vista";
import type { FolhaMetadadosOut } from "./contrato-sessoes.gen";

// Testes do view-model PURO da FOLHA (Etapa 5 fatia 6) — mesma disciplina de chamada-vista.test.ts: sem
// IO, sem React, sem mock de rede. O que este módulo garante:
//   1) a lista de versões sempre aparece com a mais recente primeiro (a folha nova é o que a Mesa quer ver);
//   2) os dois hashes (html/pdf) chegam prefixados `sha256:` do servidor — a tela precisa separar o rótulo
//      do algoritmo do dígito em si para exibir "conferível e copiável" (D-brief), sem reformatar o valor
//      que o jurídico vai comparar bit a bit;
//   3) cada código de erro das 4 rotas (409/413/503/403/404/500) vira uma frase ACIONÁVEL — nunca o corpo
//      cru nem "erro ao salvar".

function folha(over: Partial<FolhaMetadadosOut> = {}): FolhaMetadadosOut {
  return {
    id: "f1",
    versao: 1,
    specVersao: "folha-sessao-v1",
    htmlHash: "sha256:aaaa",
    pdfHash: "sha256:bbbb",
    geradaPor: "u1",
    geradaEm: "2026-08-15T14:00:00Z",
    ...over,
  };
}

describe("ordenarVersoes", () => {
  it("ordena por versão DECRESCENTE — a mais recente primeiro", () => {
    const r = ordenarVersoes([folha({ versao: 1 }), folha({ versao: 3 }), folha({ versao: 2 })]);
    expect(r.map((f) => f.versao)).toEqual([3, 2, 1]);
  });

  it("lista vazia devolve lista vazia, nunca lança", () => {
    expect(ordenarVersoes([])).toEqual([]);
  });

  it("não muta o array de entrada", () => {
    const entrada = [folha({ versao: 1 }), folha({ versao: 2 })];
    const copia = [...entrada];
    ordenarVersoes(entrada);
    expect(entrada).toEqual(copia);
  });
});

describe("formatarHash", () => {
  it("separa o prefixo do algoritmo do dígito, sem alterar nenhum caractere do dígito", () => {
    const r = formatarHash("sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
    expect(r.algoritmo).toBe("sha256");
    expect(r.digest).toBe("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
  });

  it("hash sem prefixo reconhecido devolve o valor inteiro como dígito, algoritmo vazio — nunca lança", () => {
    const r = formatarHash("abcdef");
    expect(r.algoritmo).toBe("");
    expect(r.digest).toBe("abcdef");
  });
});

describe("retryAfterSegundos", () => {
  it("header numérico vira número", () => {
    expect(retryAfterSegundos("5")).toBe(5);
  });
  it("ausente vira null", () => {
    expect(retryAfterSegundos(null)).toBeNull();
    expect(retryAfterSegundos(undefined)).toBeNull();
  });
  it("valor não numérico vira null — nunca NaN vazando pra tela", () => {
    expect(retryAfterSegundos("nunca")).toBeNull();
  });
});

describe("mensagemDeErroFolha", () => {
  it("409 de sessão aberta (D6) explica a regra — nunca mostra 'D6' nem o corpo cru", () => {
    const msg = mensagemDeErroFolha("so' sessao FECHADA tem folha de presenca (D6)", 409);
    expect(msg).toMatch(/encerrada|fechada/i);
    expect(msg).not.toMatch(/D6/);
  });

  it("409 de colisão de versão (segunda corrida) orienta tentar de novo", () => {
    const msg = mensagemDeErroFolha("conflito de versao ao congelar a folha — tente novamente", 409);
    expect(msg).toMatch(/tente novamente/i);
  });

  it("413 de documento grande explica o teto, sem jargão de bytes", () => {
    const msg = mensagemDeErroFolha("documento da folha excede o teto de tamanho", 413);
    expect(msg).toMatch(/tamanho|excede/i);
  });

  it("503 de renderizador saturado usa o Retry-After quando disponível", () => {
    const msg = mensagemDeErroFolha("renderizador de PDF ocupado — tente novamente em instantes", 503, 5);
    expect(msg).toMatch(/5s/);
  });

  it("503 sem Retry-After ainda orienta a esperar, sem inventar um número", () => {
    const msg = mensagemDeErroFolha("renderizador de PDF ocupado — tente novamente em instantes", 503, null);
    expect(msg).not.toMatch(/\ds/);
    expect(msg).toMatch(/instantes|novamente/i);
  });

  it("403 explica o gate de papel — nunca 'autorizacao negada' cru", () => {
    const msg = mensagemDeErroFolha("autorizacao negada", 403);
    expect(msg).not.toBe("autorizacao negada");
    expect(msg).toMatch(/permiss|secretari/i);
  });

  it("404 de versão específica é distinto de 404 de sessão", () => {
    const msgVersao = mensagemDeErroFolha("versao da folha nao encontrada", 404);
    const msgSessao = mensagemDeErroFolha("sessao nao encontrada", 404);
    expect(msgVersao).toMatch(/versão/i);
    expect(msgSessao).toMatch(/sessão/i);
    expect(msgVersao).not.toEqual(msgSessao);
  });

  it("500 de blob ausente (âncora sem binário) não sugere um erro do cliente", () => {
    const msg = mensagemDeErroFolha("folha temporariamente indisponivel", 500);
    expect(msg).toMatch(/indispon[ií]vel|instantes/i);
  });

  it("corpo/erro totalmente inesperado cai num texto genérico com o status — nunca lança", () => {
    const msg = mensagemDeErroFolha(undefined, 418);
    expect(msg).toMatch(/418/);
  });

  // ---- correção da revisão adversarial da fatia 6 (achado 2) ----
  // O 401 é o status que `interceptors/autenticacao` emite (`nega! ctx 401 …`) com QUATRO corpos internos
  // distintos — "sem vinculo ativo" / "sessao invalida" / "token invalido" / "sem credencial". Nenhum deles
  // é frase de operador, e todos são acionáveis pela MESMA ação (entrar de novo). O caso real: secretário
  // com a tela aberta, cookie expira, clica em "Gerar nova versão".
  it("401 de sessão expirada vira frase acionável — nunca o corpo interno do interceptor", () => {
    for (const corpo of ["sem vinculo ativo", "sessao invalida", "token invalido", "sem credencial"]) {
      const msg = mensagemDeErroFolha(corpo, 401);
      expect(msg).not.toBe(corpo);
      expect(msg).not.toMatch(/vinculo|token|credencial/i);
      expect(msg).toMatch(/sess[ãa]o expirou|entre novamente/i);
    }
  });

  // Este é o teste que a versão anterior de `mensagemDeErroFolha` não tinha: o único caso de fallback
  // exercitado passava `undefined` como corpo, então o ramo `return erro || …` NUNCA foi medido com corpo
  // presente — e era exatamente por ali que a string interna do backend chegava ao `role=alert`.
  it("status não mapeado COM corpo cru devolve o genérico — o corpo do backend nunca vai pra tela", () => {
    const msg = mensagemDeErroFolha("json invalido", 400);
    expect(msg).not.toMatch(/json invalido/);
    expect(msg).toMatch(/400/);
  });

  it("500 fora do padrão conhecido também não repassa o corpo cru", () => {
    const msg = mensagemDeErroFolha("NullPointerException em gerador-folha/renderizar", 500);
    expect(msg).not.toMatch(/NullPointer|gerador-folha/);
    expect(msg).toMatch(/500/);
  });
});
