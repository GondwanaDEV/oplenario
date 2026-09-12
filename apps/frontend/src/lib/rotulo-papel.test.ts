import { describe, expect, it } from "vitest";
import { rotuloPapel } from "./rotulo-papel";

describe("rotuloPapel", () => {
  it("secretario -> Secretário(a)", () => {
    expect(rotuloPapel(["secretario"])).toBe("Secretário(a)");
  });

  it("vereador -> Vereador(a)", () => {
    expect(rotuloPapel(["vereador"])).toBe("Vereador(a)");
  });

  it("vereador + admin_ente (a persona presidente) -> Administrador(a) do Ente", () => {
    // admin_ente é o papel mais distintivo do par (§22.5.1 — quem administra o ente); não existe papel
    // "presidente" no domínio (cargo de Mesa vive em cadastros.mesa_diretora, não em identidade.vinculo).
    expect(rotuloPapel(["vereador", "admin_ente"])).toBe("Administrador(a) do Ente");
    expect(rotuloPapel(["admin_ente", "vereador"])).toBe("Administrador(a) do Ente");
  });

  it("sem papel nenhum (a cidadã) -> Cidadã(o)", () => {
    expect(rotuloPapel([])).toBe("Cidadã(o)");
  });

  it("null/undefined (ainda carregando) -> Cidadã(o), nunca lança", () => {
    expect(rotuloPapel(null)).toBe("Cidadã(o)");
    expect(rotuloPapel(undefined)).toBe("Cidadã(o)");
  });

  it("papel desconhecido, sem nenhum dos 3 conhecidos -> Cidadã(o) (fail-closed, nunca inventa rótulo)", () => {
    expect(rotuloPapel(["algum_papel_novo"])).toBe("Cidadã(o)");
  });
});
