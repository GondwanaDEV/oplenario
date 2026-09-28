import { describe, expect, it } from "vitest";
import { derivarAta, derivarLivro, paragrafos } from "./livro-atas-vista";
import type { AtaDoLivroOut, LivroAtasOut } from "./contrato-sessoes.gen";

const sessao = { id: "s13", tipoSessao: "ordinaria", numeroSequencial: 13, abertaEm: "2026-09-10T17:00:00Z" };

const livro: LivroAtasOut = {
  atas: [
    { sessao, versao: 2, origemRedacao: "gerada_automaticamente", conteudoSha256: "sha256:ab", publicadaEm: "2026-09-11T12:00:00Z",
      leitura: { modo: "presencial", registradaEm: "2026-09-17T17:00:00Z", ataVersao: 2 } },
    { sessao: { ...sessao, id: "s12", numeroSequencial: 12 }, versao: 1, origemRedacao: "redigida_externamente",
      conteudoSha256: "sha256:cd", publicadaEm: "2026-09-04T12:00:00Z", leitura: null },
  ],
};

const ata = (over: Partial<AtaDoLivroOut> = {}): AtaDoLivroOut => ({
  sessao,
  versao: { versao: 2, origemRedacao: "gerada_automaticamente", conteudoSha256: "sha256:abc123", publicadaEm: "2026-09-11T12:00:00Z",
    motivoRetificacao: "nome do vereador corrigido", publicadaPorNome: "Maria Secretária" },
  texto: "Aos dez dias do mês de setembro...\n\nNada mais havendo a tratar...",
  vigente: true,
  versoes: [
    { versao: 2, origemRedacao: "gerada_automaticamente", conteudoSha256: "sha256:abc123", publicadaEm: "2026-09-11T12:00:00Z",
      motivoRetificacao: "nome do vereador corrigido" },
    { versao: 1, origemRedacao: "gerada_automaticamente", conteudoSha256: "sha256:0", publicadaEm: "2026-09-10T22:00:00Z" },
  ],
  leitura: { modo: "presencial", registradaEm: "2026-09-17T17:00:00Z", ataVersao: 1 },
  ...over,
});

describe("derivarLivro", () => {
  it("uma linha por ata, com a lombada, a versão e se já foi apresentada ao plenário", () => {
    const v = derivarLivro(livro);
    expect(v.vazio).toBeNull();
    expect(v.linhas.map((l) => [l.numero, l.titulo, l.detalhe, l.retificada])).toEqual([
      ["13ª", "13ª Sessão Ordinária", "versão 2 (retificada) · apresentada em plenário", true],
      ["12ª", "12ª Sessão Ordinária", "versão 1 · ainda não apresentada em plenário", false],
    ]);
  });

  it("livro vazio explica quando a ata entra", () => {
    expect(derivarLivro({ atas: [] }).vazio).toMatch(/quando a secretaria a publica/);
  });
});

describe("derivarAta", () => {
  it("o selo diz Publicada — nunca Aprovada (o sistema não registra aprovação da ata)", () => {
    const a = derivarAta(ata());
    expect(a.selo).toMatch(/^Publicada · /);
    expect(JSON.stringify(a)).not.toMatch(/aprovad/i);
  });

  it("a leitura de uma versão anterior à vigente é dita como foi: o plenário ouviu a v1", () => {
    expect(derivarAta(ata()).leitura).toMatch(/Lida em plenário pela secretaria em .*Foi lida a versão 1\./);
  });

  it("versão antiga aberta: aviso de que foi substituída, e ela marcada como a exibida", () => {
    const a = derivarAta(ata({ vigente: false, versao: { ...ata().versoes[1], publicadaPorNome: null } }));
    expect(a.aviso).toBe("Você está lendo a versão 1. Ela foi substituída pela versão 2, a que vale hoje.");
    expect(a.versoes.map((v) => [v.versao, v.exibida])).toEqual([[2, false], [1, true]]);
  });

  it("origem da IA é dita com a revisão humana; o hash sai sem o prefixo", () => {
    const a = derivarAta(ata());
    expect(a.origem).toMatch(/revisado e publicado por uma pessoa/);
    expect(a.integridade).toBe("SHA-256 do texto publicado: abc123");
    expect(a.publicadaPor).toBe("Publicada por Maria Secretária.");
  });

  it("no portal (sem nome) não inventa quem publicou", () => {
    expect(derivarAta(ata({ versao: { ...ata().versao, publicadaPorNome: null } })).publicadaPor).toBeNull();
  });
});

describe("paragrafos", () => {
  it("separa por linha em branco e nunca reescreve o texto", () => {
    expect(paragrafos("  A.\n\n\nB\nC.  ")).toEqual(["A.", "B\nC."]);
  });
});
