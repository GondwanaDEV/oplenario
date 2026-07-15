import { describe, expect, it } from "vitest";
import {
  validarNovoVereador, validarEditar, validarMandato, validarLicenca, validarConcederAcesso,
} from "./cadastro-vereadores-forms";

describe("validarNovoVereador", () => {
  it("exige nome não-branco", () => {
    expect(validarNovoVereador({ nome: "" }).valido).toBe(false);
    expect(validarNovoVereador({ nome: "   " }).valido).toBe(false);
    expect(validarNovoVereador({ nome: "Helena" }).valido).toBe(true);
  });
  it("mensagem de erro em pt-BR", () => {
    expect(validarNovoVereador({ nome: "" }).erros.nome).toMatch(/nome/i);
  });
});

describe("validarEditar", () => {
  it("exige ao menos um campo preenchido", () => {
    expect(validarEditar({}).valido).toBe(false);
    expect(validarEditar({ nome: "", nomeParlamentar: "" }).valido).toBe(false);
    expect(validarEditar({ nomeParlamentar: "Apelido" }).valido).toBe(true);
  });
  it("nome preenchido não pode ser branco", () => {
    expect(validarEditar({ nome: "  " }).valido).toBe(false);
  });
  it("permite esvaziar o nome parlamentar (limpar o apelido, sem tocar no nome)", () => {
    // Presença do campo (mesmo "") = alteração; distingue de `{}` (nada mudou → inválido).
    expect(validarEditar({ nomeParlamentar: "" }).valido).toBe(true);
  });
});

describe("validarMandato", () => {
  const ok = { legislaturaId: "abc", natureza: "titular", vigenciaInicio: "2025-01-01" };
  it("caminho feliz", () => expect(validarMandato(ok).valido).toBe(true));
  it("exige legislatura", () => expect(validarMandato({ ...ok, legislaturaId: "" }).valido).toBe(false));
  it("exige natureza válida", () => expect(validarMandato({ ...ok, natureza: "" }).valido).toBe(false));
  it("data de início inválida reprova", () => expect(validarMandato({ ...ok, vigenciaInicio: "01/01/2025" }).valido).toBe(false));
  it("data de início com overflow de dia reprova (2025-02-30)", () =>
    expect(validarMandato({ ...ok, vigenciaInicio: "2025-02-30" }).valido).toBe(false));
  it("data de início com overflow de mês reprova (2025-13-40)", () =>
    expect(validarMandato({ ...ok, vigenciaInicio: "2025-13-40" }).valido).toBe(false));
  it("fim antes do início reprova", () =>
    expect(validarMandato({ ...ok, vigenciaFim: "2024-01-01" }).valido).toBe(false));
  it("fim vazio é aceito (mandato em aberto)", () =>
    expect(validarMandato({ ...ok, vigenciaFim: "" }).valido).toBe(true));
});

describe("validarLicenca", () => {
  it("exige início válido", () => {
    expect(validarLicenca({ inicio: "" }).valido).toBe(false);
    expect(validarLicenca({ inicio: "amanhã" }).valido).toBe(false);
    expect(validarLicenca({ inicio: "2026-03-01" }).valido).toBe(true);
  });
  it("data de início com overflow de dia reprova (2025-02-30)", () =>
    expect(validarLicenca({ inicio: "2025-02-30" }).valido).toBe(false));
  it("fim antes do início reprova", () =>
    expect(validarLicenca({ inicio: "2026-03-01", fim: "2026-02-01" }).valido).toBe(false));
  it("fim vazio é aceito (licença em aberto)", () =>
    expect(validarLicenca({ inicio: "2026-03-01", fim: "" }).valido).toBe(true));
});

describe("validarConcederAcesso", () => {
  const ok = { cpf: "52998224725", email: "h@camara.local" };
  it("caminho feliz (CPF com dígito verificador real, e-mail válido)", () =>
    expect(validarConcederAcesso(ok).valido).toBe(true));
  it("aceita CPF formatado com pontuação (o form normaliza antes de enviar)", () =>
    expect(validarConcederAcesso({ ...ok, cpf: "529.982.247-25" }).valido).toBe(true));
  it("reprova CPF com dígito verificador errado (não é só contagem de dígitos)", () =>
    expect(validarConcederAcesso({ ...ok, cpf: "52998224700" }).valido).toBe(false));
  it("reprova os 11-iguais (000..., 111..., formato passa mas não é CPF real)", () =>
    expect(validarConcederAcesso({ ...ok, cpf: "11111111111" }).valido).toBe(false));
  it("reprova CPF com contagem de dígitos errada", () =>
    expect(validarConcederAcesso({ ...ok, cpf: "123" }).valido).toBe(false));
  it("reprova CPF em branco", () =>
    expect(validarConcederAcesso({ ...ok, cpf: "" }).valido).toBe(false));
  it("reprova e-mail sem @ ou sem domínio", () => {
    expect(validarConcederAcesso({ ...ok, email: "sememail" }).valido).toBe(false);
    expect(validarConcederAcesso({ ...ok, email: "h@" }).valido).toBe(false);
  });
  it("reprova e-mail em branco", () =>
    expect(validarConcederAcesso({ ...ok, email: "" }).valido).toBe(false));
});
