import { afterEach, describe, expect, it, vi } from "vitest";
import { modoReal, semCredencial } from "./modo";

// vitest.config.ts declara NEXT_PUBLIC_APP_ENV="test" p/ a suíte inteira (o modo dev é opt-in — ver
// modo.ts). Os casos abaixo sobrepõem por teste; `undefined` no stubEnv APAGA a variável, que é como se
// prova o default (ausente ⇒ modo real).
describe("modoReal", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("true quando NEXT_PUBLIC_APP_ENV está AUSENTE (default seguro: sem env ⇒ modo real)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", undefined);
    expect(modoReal()).toBe(true);
  });

  it("true quando NEXT_PUBLIC_APP_ENV é vazio (variável declarada sem valor)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "");
    expect(modoReal()).toBe(true);
  });

  it("true sob NEXT_PUBLIC_APP_ENV==='production'", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    expect(modoReal()).toBe(true);
  });

  it("true sob um valor não-reconhecido/typo (mesmo fail-safe do idp-para no backend)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "producton");
    expect(modoReal()).toBe(true);
  });

  it("false sob NEXT_PUBLIC_APP_ENV==='dev' (opt-in explícito)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "dev");
    expect(modoReal()).toBe(false);
  });

  it("false sob NEXT_PUBLIC_APP_ENV==='test' (opt-in explícito)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "test");
    expect(modoReal()).toBe(false);
  });

  it("NÃO olha o NODE_ENV: build de dev (next dev) sem a env de modo ⇒ modo real", () => {
    // o bug: `next dev` ⇒ NODE_ENV="development" ⇒ modoReal()=false ⇒ todo hook abortava em
    // semCredencial(null) antes de buscar, mesmo com cookie de sessão Keycloak válido. O modo de AUTH é
    // do deploy, não do build.
    vi.stubEnv("NODE_ENV", "development");
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", undefined);
    expect(modoReal()).toBe(true);
  });

  it("NÃO olha o NODE_ENV: NODE_ENV=production + APP_ENV=dev ⇒ modo dev (a env de modo manda)", () => {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "dev");
    expect(modoReal()).toBe(false);
  });
});

describe("semCredencial", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("dev + token null -> true (aborta, sem como autenticar)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "dev");
    expect(semCredencial(null)).toBe(true);
  });

  it("dev + token presente -> false (segue, Authorization: Bearer)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "dev");
    expect(semCredencial("tok")).toBe(false);
  });

  it("modo real (env ausente) + token null -> false (cookie decide, não aborta)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", undefined);
    expect(semCredencial(null)).toBe(false);
  });

  it("modo real (production) + token null -> false (cookie decide, não aborta)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    expect(semCredencial(null)).toBe(false);
  });

  it("modo real + token presente -> false (segue de qualquer forma)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    expect(semCredencial("tok")).toBe(false);
  });
});
