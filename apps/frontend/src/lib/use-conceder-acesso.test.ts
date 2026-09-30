import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";

// `vi.mock` é hoisted para o topo do arquivo — vale para TODO o arquivo, inclusive os testes de
// `concederAcesso` mais abaixo. Isso não os afeta: esses testes injetam o próprio `fetchFake` direto na
// função pura, nunca passam por `apiFetch`. É só `useConcederAcesso` (que chama `apiFetch` de verdade) que
// depende deste mock.
const fetchMock = vi.hoisted(() => vi.fn());
vi.mock("./api-fetch", () => ({ apiFetch: fetchMock }));

import { concederAcesso, concederJuridico, useConcederAcesso, useConcederJuridico } from "./use-conceder-acesso";

function ok(body: unknown) {
  return { ok: true, status: 201, json: async () => body } as Response;
}
function erro(status: number, body: unknown) {
  return { ok: false, status, json: async () => body } as Response;
}

describe("concederAcesso — a ordem é a garantia de segurança", () => {
  it("chama os 3 passos na ordem, acesso por último", async () => {
    const chamadas: string[] = [];
    const fetchFake = vi.fn(async (url: string) => {
      chamadas.push(url);
      return { ok: true, json: async () => ({ "identidade-id": "id-1" }) } as Response;
    });
    await concederAcesso(
      { vereadorId: "v-1", cpf: "52998224725", nome: "Helena Matos", email: "h@c.local" },
      fetchFake,
    );
    expect(chamadas).toEqual([
      "/api/identidade/identidades",
      "/api/cadastros/vereadores/v-1/identidade",
      "/api/identidade/acessos",
    ]);
  });

  it("para no 1º erro e não concede acesso", async () => {
    const chamadas: string[] = [];
    const fetchFake = vi.fn(async (url: string) => {
      chamadas.push(url);
      if (url.includes("cadastros")) return { ok: false, status: 409, json: async () => ({}) } as Response;
      return { ok: true, json: async () => ({ "identidade-id": "id-1" }) } as Response;
    });
    await expect(
      concederAcesso({ vereadorId: "v-1", cpf: "52998224725", nome: "H", email: "h@c.local" }, fetchFake),
    ).rejects.toThrow();
    expect(chamadas).not.toContain("/api/identidade/acessos");
  });

  it("usa o identidade-id devolvido pelo passo 1 nos corpos dos passos 2 e 3 (não inventa/fixa o id)", async () => {
    // Se o código chamasse os passos fora de ordem, ou usasse um id fixo em vez do devolvido pelo passo 1,
    // este teste pegaria: o corpo do passo 2/3 tem que refletir o "identidade-id" que o passo 1 devolveu de
    // verdade, não um valor hardcoded — prova que o encadeamento é real, não só a sequência de URLs.
    const corpos: Array<Record<string, unknown>> = [];
    const fetchFake = vi.fn(async (_url: string, init?: RequestInit) => {
      corpos.push(init?.body ? JSON.parse(init.body as string) : {});
      return { ok: true, json: async () => ({ "identidade-id": "id-xyz-9" }) } as Response;
    });
    const resultado = await concederAcesso(
      { vereadorId: "v-2", cpf: "52998224725", nome: "H", email: "h@c.local" },
      fetchFake,
    );
    expect(resultado).toEqual({ identidadeId: "id-xyz-9" });
    expect(corpos[1]["identidade-id"]).toBe("id-xyz-9");
    expect(corpos[2]["identidade-id"]).toBe("id-xyz-9");
    expect(corpos[2].tipo).toBe("vereador");
    expect(corpos[2].papeis).toEqual(["vereador"]);
  });

  it("o passo 2 (ligar cadastro) usa PATCH; passos 1 e 3 usam POST", async () => {
    const metodos: Array<string | undefined> = [];
    const fetchFake = vi.fn(async (_url: string, init?: RequestInit) => {
      metodos.push(init?.method);
      return { ok: true, json: async () => ({ "identidade-id": "id-1" }) } as Response;
    });
    await concederAcesso({ vereadorId: "v-1", cpf: "52998224725", nome: "H", email: "h@c.local" }, fetchFake);
    expect(metodos).toEqual(["POST", "PATCH", "POST"]);
  });
});

describe("useConcederAcesso", () => {
  afterEach(() => { fetchMock.mockReset(); });

  it("sem credencial no modo dev (token nulo) -> throw sem chamar apiFetch", async () => {
    const { result } = renderHook(() => useConcederAcesso(null));
    await act(async () => {
      await expect(
        result.current.conceder({ vereadorId: "v-1", cpf: "52998224725", nome: "Helena", email: "h@c.local" }),
      ).rejects.toThrow();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("orquestra os 3 passos via apiFetch com o token do ator, na ordem, e devolve identidadeId", async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ "identidade-id": "id-9" }))
      .mockResolvedValueOnce(ok({ id: "v-1", "identidade-id": "id-9" }))
      .mockResolvedValueOnce(ok({ "vinculo-id": "vin-1", convite: "enviado" }));

    const { result } = renderHook(() => useConcederAcesso("tok-admin"));
    let devolvido: { identidadeId: string } | undefined;
    await act(async () => {
      devolvido = await result.current.conceder({
        vereadorId: "v-1", cpf: "52998224725", nome: "Helena Matos", email: "h@camara.local",
      });
    });

    expect(devolvido).toEqual({ identidadeId: "id-9" });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const [url1, init1] = fetchMock.mock.calls[0];
    const [url2, init2] = fetchMock.mock.calls[1];
    const [url3, init3] = fetchMock.mock.calls[2];
    expect(url1).toBe("/api/identidade/identidades");
    expect(url2).toBe("/api/cadastros/vereadores/v-1/identidade");
    expect(url3).toBe("/api/identidade/acessos");
    // o token do ator viaja em CADA um dos 3 passos (não só no primeiro) — é `apiFetch` quem materializa
    // em Authorization/cookie, mas o hook precisa repassar o token toda vez.
    expect(init1.token).toBe("tok-admin");
    expect(init2.token).toBe("tok-admin");
    expect(init3.token).toBe("tok-admin");
    expect(init2.method).toBe("PATCH");
    expect(result.current.estado).toBe("ocioso");
  });

  it("409 do backend no passo 2 -> estado 'erro' com a mensagem do servidor, e o passo 3 NUNCA é chamado", async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ "identidade-id": "id-9" }))
      .mockResolvedValueOnce(erro(409, { erro: "identidade ja vinculada a outro vereador nesta Casa" }));

    const { result } = renderHook(() => useConcederAcesso("tok-admin"));
    await act(async () => {
      await expect(
        result.current.conceder({ vereadorId: "v-1", cpf: "52998224725", nome: "Helena", email: "h@c.local" }),
      ).rejects.toThrow(/identidade ja vinculada/);
    });

    expect(fetchMock).toHaveBeenCalledTimes(2); // nunca chegou no passo 3 (acessos)
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/identidade ja vinculada/);
  });
});

describe("concederJuridico — identidade, e só então o vínculo de servidor com qualificação e OAB (ADR-0019)", () => {
  afterEach(() => { fetchMock.mockReset(); });

  const entrada = {
    cpf: "52998224725", nome: "Dra. Lúcia Prado", email: "lucia@camara.local", qualificacao: "contratado", oab: "CE 12345",
  };

  it("chama a identidade e depois o acesso, com papeis [juridico], qualificacao e oab no corpo", async () => {
    const chamadas: Array<[string, Record<string, unknown>]> = [];
    const fetchFake = vi.fn(async (url: string, init?: RequestInit) => {
      chamadas.push([url, init?.body ? JSON.parse(init.body as string) : {}]);
      return { ok: true, json: async () => ({ "identidade-id": "id-7" }) } as Response;
    });
    const r = await concederJuridico(entrada, fetchFake);
    expect(r).toEqual({ identidadeId: "id-7" });
    expect(chamadas.map(([u]) => u)).toEqual(["/api/identidade/identidades", "/api/identidade/acessos"]);
    expect(chamadas[1][1]).toEqual({
      "identidade-id": "id-7", tipo: "servidor", papeis: ["juridico"], email: "lucia@camara.local",
      qualificacao: "contratado", oab: "CE 12345",
    });
  });

  it("se a identidade falha, o acesso nunca é concedido", async () => {
    const chamadas: string[] = [];
    const fetchFake = vi.fn(async (url: string) => {
      chamadas.push(url);
      return { ok: false, status: 400, json: async () => ({ erro: "cpf invalido" }) } as Response;
    });
    await expect(concederJuridico(entrada, fetchFake)).rejects.toThrow(/cpf invalido/);
    expect(chamadas).toEqual(["/api/identidade/identidades"]);
  });

  it("useConcederJuridico: o 400 do servidor no acesso vira o erro da tela", async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ "identidade-id": "id-7" }))
      .mockResolvedValueOnce(erro(400, { erro: "oab invalida" }));
    const { result } = renderHook(() => useConcederJuridico("tok-admin"));
    await act(async () => {
      await expect(result.current.conceder(entrada)).rejects.toThrow(/oab invalida/);
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/oab invalida/);
  });
});
