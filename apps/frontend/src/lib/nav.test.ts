import { describe, expect, it } from "vitest";
import { comToken } from "./nav";

describe("comToken", () => {
  it("anexa o token como querystring quando presente", () => {
    expect(comToken("/proposicoes", "abc123")).toBe("/proposicoes?token=abc123");
  });

  it("preserva o href intocado quando o token e' nulo", () => {
    expect(comToken("/proposicoes", null)).toBe("/proposicoes");
  });

  it("URL-encoda o token (claims JSON de dev tem caracteres especiais)", () => {
    expect(comToken("/proposicoes", '{"a":1}')).toBe(`/proposicoes?token=${encodeURIComponent('{"a":1}')}`);
  });

  it("href que já tem querystring recebe o token com &", () => {
    expect(comToken("/pauta-convocacao?sessao=s1", "abc")).toBe("/pauta-convocacao?sessao=s1&token=abc");
  });
});
