import { describe, expect, it } from "vitest";
import {
  DIREITOS_LGPD,
  TIPOS_MANIFESTACAO,
  corpoEsic,
  corpoLgpd,
  corpoManifestacao,
  direitoDaUrl,
  rotuloDaLista,
  rotuloEstado,
} from "./formularios-cidadao";

describe("formulários do cidadão — o que vai ao backend", () => {
  it("os 5 tipos de manifestação e os 5 direitos LGPD batem com os enums do backend", () => {
    expect(TIPOS_MANIFESTACAO.map((t) => t.valor)).toEqual(["reclamacao", "denuncia", "sugestao", "elogio", "solicitacao"]);
    expect(DIREITOS_LGPD.map((d) => d.valor)).toEqual([
      "acessar",
      "corrigir",
      "eliminar",
      "com_quem_compartilhado",
      "revogar_consentimento",
    ]);
  });

  it("e-SIC: apara e exige assunto e descrição; respeita os limites", () => {
    expect(corpoEsic({ assunto: "  Contratos  ", descricao: " Quero a lista. " })).toEqual({
      ok: true,
      corpo: { assunto: "Contratos", descricao: "Quero a lista." },
    });
    expect(corpoEsic({ assunto: " ", descricao: "x" })).toEqual({ ok: false, campo: "assunto" });
    expect(corpoEsic({ assunto: "a".repeat(501), descricao: "x" })).toEqual({ ok: false, campo: "assunto" });
    expect(corpoEsic({ assunto: "a", descricao: "" })).toEqual({ ok: false, campo: "descricao" });
  });

  it("LGPD: detalhe é opcional (em branco não vai)", () => {
    expect(corpoLgpd({ tipo: "acessar", detalhe: "  " })).toEqual({ ok: true, corpo: { tipo: "acessar" } });
    expect(corpoLgpd({ tipo: "corrigir", detalhe: " Meu endereço " })).toEqual({
      ok: true,
      corpo: { tipo: "corrigir", detalhe: "Meu endereço" },
    });
    expect(corpoLgpd({ tipo: "", detalhe: "" })).toEqual({ ok: false, campo: "tipo" });
  });

  it("ouvidoria: anônima vai como boolean", () => {
    expect(corpoManifestacao({ tipo: "elogio", assunto: "Atendimento", descricao: "Muito bom.", anonima: true })).toEqual({
      ok: true,
      corpo: { tipo: "elogio", assunto: "Atendimento", descricao: "Muito bom.", anonima: true },
    });
    expect(corpoManifestacao({ tipo: "x", assunto: "a", descricao: "b", anonima: false })).toEqual({ ok: false, campo: "tipo" });
  });

  it("estado e tipo que a tela não conhece saem em palavras, nunca a chave do backend", () => {
    expect(rotuloEstado("aguardando_orgao")).toBe("Aguardando orgao");
    expect(rotuloEstado("")).toBe("Situação não informada");
    expect(rotuloEstado("em_analise")).toBe("Em análise");
    expect(rotuloDaLista(TIPOS_MANIFESTACAO, "pedido_especial")).toBe("Pedido especial");
    expect(rotuloDaLista(DIREITOS_LGPD, "portabilidade_dos_dados")).toBe("Portabilidade dos dados");
    expect(rotuloDaLista(DIREITOS_LGPD, "acessar")).toBe(DIREITOS_LGPD.find((d) => d.valor === "acessar")!.rotulo);
  });

  it("o direito pedido na URL (balcão LGPD) só vale se existir", () => {
    expect(direitoDaUrl("eliminar")).toBe("eliminar");
    expect(direitoDaUrl("inventado")).toBe("");
    expect(direitoDaUrl(undefined)).toBe("");
  });
});
