import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { BalcaoEsic } from "./balcao-esic";

// Task 2.1 (Fatia A2.2, Portal do Cidadão) — porte de portal-cidadao.html:519-584. Sem o `.pedido` fixo
// de demonstração da tela-fonte (dado fabricado) — o formulário "Acompanhar pelo número" busca o status
// REAL via buscarPublico; "Abrir um pedido" é em-breve honesto (fluxo autenticado deferido).

describe("BalcaoEsic", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra o eyebrow, o título e o lead", () => {
    render(<BalcaoEsic ente="fortaleza" />);
    expect(screen.getByText("e-SIC · Lei de Acesso à Informação").textContent).toBe(
      "e-SIC · Lei de Acesso à Informação",
    );
    expect(screen.getByRole("heading", { name: "Acesso à informação" })).toBeTruthy();
  });

  it("não mostra nenhum pedido antes de buscar (sem dado fabricado)", () => {
    render(<BalcaoEsic ente="fortaleza" />);
    expect(screen.queryByText(/^Pedido nº/)).toBeNull();
  });

  it("'Abrir um pedido' é em-breve honesto (fluxo autenticado deferido)", () => {
    render(<BalcaoEsic ente="fortaleza" />);
    expect(screen.getByText("Abrir um pedido").textContent).toBe("Abrir um pedido");
    expect(screen.getByText(/exige identificação/i)).toBeTruthy();
  });

  it("busca por protocolo -> chama a rota real e mostra o status (faixa + situação)", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ protocolo: "2026/00488", estado: "em_analise", "dias-restantes": 9 }),
    })) as unknown as typeof fetch;

    render(<BalcaoEsic ente="fortaleza" />);
    fireEvent.change(screen.getByLabelText(/acompanhar pelo número/i), { target: { value: "2026/00488" } });
    fireEvent.click(screen.getByRole("button", { name: /acompanhar/i }));

    await waitFor(() => expect(screen.getByText(/pedido nº 2026\/00488/i)).toBeTruthy());
    // "Em análise" aparece 2x: no rótulo de situação (.tipo-obj) e no estágio ativo da AzulejoFaixa.
    expect(screen.getAllByText("Em análise").length).toBeGreaterThanOrEqual(2);

    const [url] = (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [string];
    expect(url).toBe("/api/portal/casa/fortaleza/esic/acompanhar/2026%2F00488");
  });

  it("protocolo não encontrado -> mensagem honesta, sem inventar um pedido, e sem o rótulo 'em breve'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 })) as unknown as typeof fetch;

    render(<BalcaoEsic ente="fortaleza" />);
    fireEvent.change(screen.getByLabelText(/acompanhar pelo número/i), { target: { value: "0000/00000" } });
    fireEvent.click(screen.getByRole("button", { name: /acompanhar/i }));

    await waitFor(() => expect(screen.getByText(/não encontramos nenhum pedido/i)).toBeTruthy());
    expect(screen.queryByText(/^Pedido nº/)).toBeNull();
    // Review A2.2 (item 4): "não encontrado" NÃO é mais um <EmBreve> — o aria-label fixo daquele
    // componente ("{titulo}: em breve") rendia o absurdo "Pedido não encontrado: em breve" (não é um
    // recurso futuro, é o desfecho de uma busca real).
    expect(screen.queryByText("Pedido não encontrado")?.textContent).toBe("Pedido não encontrado");
    expect(screen.queryByLabelText(/pedido não encontrado: em breve/i)).toBeNull();
  });

  it("o resultado da busca vive dentro de uma única live region (role=status, aria-live=polite)", () => {
    const { container } = render(<BalcaoEsic ente="fortaleza" />);
    const regioes = container.querySelectorAll('[aria-live="polite"]');
    expect(regioes.length).toBe(1);
    expect(regioes[0].getAttribute("role")).toBe("status");
  });

  it("o estado 'buscando' também é anunciado dentro da live region (não só o desfecho)", async () => {
    let resolverFetch!: (v: unknown) => void;
    global.fetch = vi.fn(
      () =>
        new Promise((resolve) => {
          resolverFetch = resolve;
        }),
    ) as unknown as typeof fetch;

    const { container } = render(<BalcaoEsic ente="fortaleza" />);
    fireEvent.change(screen.getByLabelText(/acompanhar pelo número/i), { target: { value: "2026/00488" } });
    fireEvent.click(screen.getByRole("button", { name: /acompanhar/i }));

    const regiao = container.querySelector('[aria-live="polite"]');
    expect(regiao?.textContent).toMatch(/buscando/i);

    resolverFetch({ ok: true, json: async () => ({ protocolo: "2026/00488", estado: "em_analise", "dias-restantes": 9 }) });
    await waitFor(() => expect(screen.getByText(/pedido nº 2026\/00488/i)).toBeTruthy());
  });

  it("submeter enquanto já busca (2º Enter) não dispara uma segunda chamada (guarda de reentrância)", async () => {
    let resolverFetch!: (v: unknown) => void;
    const fetchMock = vi.fn(
      () =>
        new Promise((resolve) => {
          resolverFetch = resolve;
        }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<BalcaoEsic ente="fortaleza" />);
    const campo = screen.getByLabelText(/acompanhar pelo número/i);
    fireEvent.change(campo, { target: { value: "2026/00488" } });
    const formulario = campo.closest("form") as HTMLFormElement;
    fireEvent.submit(formulario);
    fireEvent.submit(formulario);

    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolverFetch({ ok: true, json: async () => ({ protocolo: "2026/00488", estado: "em_analise", "dias-restantes": 9 }) });
    await waitFor(() => expect(screen.getByText(/pedido nº 2026\/00488/i)).toBeTruthy());
  });

  it("submeter vazio não dispara busca (sem fetch)", () => {
    const fetchMock = vi.fn();
    global.fetch = fetchMock as unknown as typeof fetch;
    render(<BalcaoEsic ente="fortaleza" />);
    fireEvent.click(screen.getByRole("button", { name: /acompanhar/i }));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
