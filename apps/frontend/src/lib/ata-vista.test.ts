import { describe, expect, it } from "vitest";
import { faltaParaPublicar, linhaDaVersao, mensagemDeErroAta, origemDaRedacao, semAta, TETO_TEXTO_ATA } from "./ata-vista";
import type { AtaVersaoOut } from "./contrato-sessoes.gen";

const v = (over: Partial<AtaVersaoOut> = {}): AtaVersaoOut => ({
  id: "a1", versao: 1, origemRedacao: "redigida_externamente", conteudoSha256: "sha256:ab",
  publicadaEm: "2026-09-26T21:04:00Z", publicadaPorNome: "Maria Souza", ...over,
});

describe("ata-vista", () => {
  it("diz de onde veio a redação", () => {
    expect(origemDaRedacao(v())).toBe("Redigida pela Casa");
    expect(origemDaRedacao(v({ origemRedacao: "gerada_automaticamente" }))).toMatch(/rascunho da IA, revisado por uma pessoa/);
  });

  it("linha da versão com e sem nome (nunca mostra id)", () => {
    expect(linhaDaVersao(v({ versao: 2 }))).toMatch(/^Versão 2 · publicada por Maria Souza em 26\/09\/2026 às \d\d:04$/);
    expect(linhaDaVersao(v({ publicadaPorNome: null }))).toMatch(/^Versão 1 · publicada em /);
  });

  it("sem ata: só quando a sessão não pode ter e nada foi publicado", () => {
    expect(semAta({ podeTerAta: true, atual: null })).toBeNull();
    expect(semAta({ podeTerAta: false, atual: null })).toMatch(/não foi encerrada/);
    expect(semAta({ podeTerAta: false, atual: { versao: v(), texto: "x" } })).toBeNull();
  });

  it("o que falta para publicar", () => {
    expect(faltaParaPublicar({ texto: "  ", motivo: "", retificando: false })).toMatch(/texto da ata/);
    expect(faltaParaPublicar({ texto: "Ata.", motivo: "", retificando: false })).toBeNull();
    expect(faltaParaPublicar({ texto: "Ata.", motivo: " ", retificando: true })).toMatch(/motivo da retificação/);
    expect(faltaParaPublicar({ texto: "Ata.", motivo: "erro", retificando: true })).toBeNull();
    expect(faltaParaPublicar({ texto: "a".repeat(TETO_TEXTO_ATA + 1), motivo: "", retificando: false })).toMatch(/limite/);
  });

  it("recusa do servidor vira frase da tela", () => {
    expect(mensagemDeErroAta(409, "esta sessao nao tem ata")).toBe("esta sessao nao tem ata");
    expect(mensagemDeErroAta(403, undefined)).toMatch(/permissão/);
    expect(mensagemDeErroAta(500, "stack")).toMatch(/Nada foi alterado/);
  });
});
