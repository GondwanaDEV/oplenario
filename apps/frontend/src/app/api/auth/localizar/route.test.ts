import { describe, expect, it } from "vitest";
import { GET, POST } from "./route";

describe("/api/auth/localizar (ADR-0025)", () => {
  it("não existe no BFF: o proxy nunca leva o navegador direto ao backend", async () => {
    expect((await POST()).status).toBe(404);
    expect((await GET()).status).toBe(404);
  });
});
