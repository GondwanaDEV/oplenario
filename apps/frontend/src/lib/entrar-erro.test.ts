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
});
