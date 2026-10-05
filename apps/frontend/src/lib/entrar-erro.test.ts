import { describe, expect, it } from "vitest";
import { mensagemErroEntrada } from "./entrar-erro";

// Task 12 (Onda D Slice 2, fast-follow) — /entrar precisa surfar `?erro=login` (posto pelo login handler
// fail-closed, app/api/auth/login/route.ts, quando a descoberta do tenant falha) em vez de deixar o
// usuário num beco silencioso com a cópia genérica de sempre.

describe("mensagemErroEntrada", () => {
  it("erro ausente -> null (página renderiza igual a antes, sem regressão)", () => {
    expect(mensagemErroEntrada(undefined)).toBeNull();
  });

  it("erro=login -> mensagem amigável de login malsucedido", () => {
    expect(mensagemErroEntrada("login")).toBe(
      "Não foi possível concluir o login. Verifique o endereço da sua câmara e tente novamente."
    );
  });

  it("qualquer outro valor de erro -> mesma mensagem genérica (não inventa por valor)", () => {
    expect(mensagemErroEntrada("outra-coisa")).toBe(
      "Não foi possível concluir o login. Verifique o endereço da sua câmara e tente novamente."
    );
  });

  it("ADR-0025: cada falha da entrada pelo CPF tem a sua frase — nenhuma repete o CPF", () => {
    for (const erro of ["cpf-invalido", "sem-acesso", "sem-acesso-nesta", "muitas-tentativas", "indisponivel", "escolha"]) {
      const m = mensagemErroEntrada(erro);
      expect(m).toBeTruthy();
      expect(m).not.toBe(mensagemErroEntrada("login"));
      expect(m).not.toMatch(/\d{3}/);
    }
  });
});
