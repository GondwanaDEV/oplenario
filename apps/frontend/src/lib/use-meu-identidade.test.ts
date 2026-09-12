import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useMeuIdentidade } from "./use-meu-identidade";

describe("useMeuIdentidade", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it("busca /api/meu/identidade e cameliza", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production"); // modo real: token truthy já basta pra buscar
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ nome: "Marina Alencar Freire", papeis: ["secretario"] }) }) as Response
    ) as unknown as typeof fetch;

    const { result } = renderHook(() => useMeuIdentidade("tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados).toEqual({ nome: "Marina Alencar Freire", papeis: ["secretario"] });

    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/meu/identidade");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("modo real + token null (sessão via cookie) -> ainda busca (nunca aborta em modo real)", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ nome: "Roberta Costa Aguiar", papeis: [] }) }) as Response
    ) as unknown as typeof fetch;

    const { result } = renderHook(() => useMeuIdentidade(null));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.nome).toBe("Roberta Costa Aguiar");
  });

  it("modo dev sem token -> 'erro' sem chamar fetch (fail-closed)", () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "test");
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuIdentidade(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("resposta não-ok -> estado 'erro', nunca inventa nome", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(async () => ({ ok: false, status: 401 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuIdentidade("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.dados).toBeNull();
  });

  it("corpo malformado (sem :nome, ex.: um `global.fetch` mockado por OUTRA página) -> 'erro', nunca crasha", async () => {
    // Achado real: page tests não relacionados (calendario/tramitacao/proposicoes/...) mockam
    // `global.fetch` pra SEU PRÓPRIO endpoint; TopoInterno agora busca /api/meu/identidade por conta
    // própria e recebia de volta o JSON do outro domínio — sem esta validação, `dados.nome` chegava
    // `undefined` até `nome.split(...)` em topo.tsx e derrubava a página inteira.
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ sessoes: [], prazos: [] }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuIdentidade("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.dados).toBeNull();
  });
});
