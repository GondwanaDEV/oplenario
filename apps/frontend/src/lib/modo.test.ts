import { afterEach, describe, expect, it, vi } from "vitest";
import { modoReal, semCredencial } from "./modo";

describe("modoReal", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("true sob NODE_ENV==='production'", () => {
    vi.stubEnv("NODE_ENV", "production");
    expect(modoReal()).toBe(true);
  });

  it("false sob NODE_ENV==='test' (vitest)", () => {
    vi.stubEnv("NODE_ENV", "test");
    expect(modoReal()).toBe(false);
  });

  it("false sob NODE_ENV==='development'", () => {
    vi.stubEnv("NODE_ENV", "development");
    expect(modoReal()).toBe(false);
  });
});

describe("semCredencial", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("dev (não-produção) + token null -> true (aborta, sem como autenticar)", () => {
    vi.stubEnv("NODE_ENV", "test");
    expect(semCredencial(null)).toBe(true);
  });

  it("dev (não-produção) + token presente -> false (segue, Authorization: Bearer)", () => {
    vi.stubEnv("NODE_ENV", "test");
    expect(semCredencial("tok")).toBe(false);
  });

  it("modo real (produção) + token null -> false (cookie decide, não aborta)", () => {
    vi.stubEnv("NODE_ENV", "production");
    expect(semCredencial(null)).toBe(false);
  });

  it("modo real (produção) + token presente -> false (segue de qualquer forma)", () => {
    vi.stubEnv("NODE_ENV", "production");
    expect(semCredencial("tok")).toBe(false);
  });
});
