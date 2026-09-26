import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import PaginaNovoRequerimento from "./page";

const nav = vi.hoisted(() => ({ push: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: nav.push, back: vi.fn() }) }));
vi.mock("@/lib/use-subscricao", () => ({
  useColegas: () => ({
    colegas: [
      { id: "v-bia", nome: "Bia Lima", partido: "PSB" },
      { id: "v-caio", nome: "Caio Reis", partido: null },
    ],
    estado: "pronto",
  }),
}));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const previa = vi.fn();
const protocolar = vi.fn();
const enviarParaSubscricao = vi.fn();
const useNovoMock = vi.fn();
vi.mock("@/lib/use-novo-requerimento", () => ({ useNovoRequerimento: (...a: unknown[]) => useNovoMock(...a) }));

const modelos = [
  { id: "m1", nome: "Requerimento de informação", campos: ["destinatario", "justificativa"] },
  { id: "m2", nome: "Requerimento de voto de pesar", campos: ["falecido"] },
];

function montar(over: Record<string, unknown> = {}) {
  useNovoMock.mockReturnValue({ modelos, estadoModelos: "pronto", estado: "ocioso", erro: null, previa, protocolar, enviarParaSubscricao, ...over });
  return render(<PaginaNovoRequerimento />);
}

afterEach(() => {
  cleanup();
  previa.mockReset();
  protocolar.mockReset();
  enviarParaSubscricao.mockReset();
  nav.push.mockReset();
});

function preencher() {
  fireEvent.click(screen.getByLabelText("Requerimento de informação"));
  fireEvent.change(screen.getByLabelText("Ementa"), { target: { value: "Informações sobre a obra X" } });
  fireEvent.change(screen.getByLabelText("Destinatário"), { target: { value: "Secretaria de Obras" } });
  fireEvent.change(screen.getByLabelText("Justificativa"), { target: { value: "Transparência." } });
}

describe("Novo requerimento — passo 1 (escrever)", () => {
  it("os campos do formulário são os do modelo escolhido", () => {
    montar();
    expect(screen.queryByLabelText("Destinatário")).toBeNull();
    fireEvent.click(screen.getByLabelText("Requerimento de voto de pesar"));
    expect(screen.getByLabelText("Nome de quem faleceu")).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Requerimento de informação"));
    expect(screen.getByLabelText("Destinatário")).toBeTruthy();
    expect(screen.getByLabelText("Justificativa").tagName).toBe("TEXTAREA");
  });

  it("'Ver o texto formatado' fica bloqueado até tudo estar preenchido", () => {
    montar();
    const botao = screen.getByRole("button", { name: /Ver o texto formatado/ }) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
    preencher();
    expect(botao.disabled).toBe(false);
  });

  it("sem modelos cadastrados: explica que a Casa precisa cadastrar", () => {
    montar({ modelos: [] });
    expect(screen.getByText(/A Casa ainda não cadastrou modelos de requerimento/)).toBeTruthy();
  });
});

describe("Novo requerimento — passo 2 (revisar e assinar)", () => {
  it("mostra o texto do servidor, assina e protocola; recibo com número e selo provisório honesto", async () => {
    previa.mockResolvedValue("REQUERIMENTO\n\nAna Prado requer…");
    protocolar.mockResolvedValue({
      proposicaoId: "p1", ano: 2026, sequencial: 7, urnLex: "urn", estado: "protocolada", assinaturaAlgoritmo: "STUB-ICP-v0",
    });
    montar();
    preencher();
    fireEvent.click(screen.getByRole("button", { name: /Ver o texto formatado/ }));
    await waitFor(() => expect(previa).toHaveBeenCalledWith({
      modeloId: "m1", campos: { destinatario: "Secretaria de Obras", justificativa: "Transparência." },
    }));
    const papel = await screen.findByRole("region", { name: "Documento a assinar" });
    expect(papel.textContent).toContain("Ana Prado requer…");

    fireEvent.click(screen.getByRole("button", { name: "Revisar e assinar" }));
    fireEvent.click(screen.getByRole("button", { name: /Confirmar e protocolar/ }));
    await waitFor(() => expect(protocolar).toHaveBeenCalledWith({
      modeloId: "m1", ementa: "Informações sobre a obra X",
      campos: { destinatario: "Secretaria de Obras", justificativa: "Transparência." },
    }));
    expect(await screen.findByText(/Requerimento nº 7\/2026 protocolado/)).toBeTruthy();
    expect(screen.getByText(/selo provisório/)).toBeTruthy();
  });

  it("campos de outro modelo não vazam para o envio", async () => {
    previa.mockResolvedValue("T");
    montar();
    fireEvent.click(screen.getByLabelText("Requerimento de voto de pesar"));
    fireEvent.change(screen.getByLabelText("Nome de quem faleceu"), { target: { value: "Fulano" } });
    preencher(); // troca para o de informação
    fireEvent.click(screen.getByRole("button", { name: /Ver o texto formatado/ }));
    await waitFor(() => expect(previa).toHaveBeenCalled());
    expect(previa.mock.calls[0][0].campos).not.toHaveProperty("falecido");
  });

  it("'Editar' volta ao formulário com o que foi preenchido", async () => {
    previa.mockResolvedValue("T");
    montar();
    preencher();
    fireEvent.click(screen.getByRole("button", { name: /Ver o texto formatado/ }));
    await screen.findByRole("region", { name: "Documento a assinar" });
    fireEvent.click(screen.getByRole("button", { name: "Editar" }));
    expect((screen.getByLabelText("Destinatário") as HTMLInputElement).value).toBe("Secretaria de Obras");
  });

  it("erro ao protocolar aparece na folha de confirmação", async () => {
    previa.mockResolvedValue("T");
    protocolar.mockRejectedValue(new Error("x"));
    const { rerender } = montar();
    preencher();
    fireEvent.click(screen.getByRole("button", { name: /Ver o texto formatado/ }));
    await screen.findByRole("region", { name: "Documento a assinar" });
    fireEvent.click(screen.getByRole("button", { name: "Revisar e assinar" }));
    useNovoMock.mockReturnValue({ modelos, estadoModelos: "pronto", estado: "erro", erro: "falha no servidor", previa, protocolar });
    rerender(<PaginaNovoRequerimento />);
    expect(screen.getByText(/Não foi possível protocolar: falha no servidor/)).toBeTruthy();
  });
});

describe("Novo requerimento — coletivo (fatia 2c)", () => {
  it("com coautor: não abre a folha de assinatura; envia para subscrição e vai para a proposta", async () => {
    previa.mockResolvedValue("TEXTO FORMATADO");
    enviarParaSubscricao.mockResolvedValue({ id: "p-1" });
    montar();
    preencher();
    fireEvent.change(screen.getByLabelText("Adicionar colega"), { target: { value: "bia" } });
    fireEvent.click(screen.getByRole("button", { name: /Bia Lima/ }));
    expect(screen.getByRole("list", { name: "Coautores convidados" }).textContent).toContain("Bia Lima");
    fireEvent.click(screen.getByRole("button", { name: /Ver o texto formatado/ }));
    await screen.findByText("TEXTO FORMATADO");
    expect(screen.queryByRole("button", { name: "Revisar e assinar" })).toBeNull();
    expect(screen.getByText(/Antes do protocolo, as subscrições/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Enviar para subscrição" }));
    await waitFor(() =>
      expect(enviarParaSubscricao).toHaveBeenCalledWith({
        modeloId: "m1",
        campos: { destinatario: "Secretaria de Obras", justificativa: "Transparência." },
        ementa: "Informações sobre a obra X",
        coautores: ["v-bia"],
      }),
    );
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringContaining("/requerimento/proposta/p-1")));
  });

  it("tirar o coautor volta ao fluxo individual", async () => {
    previa.mockResolvedValue("TEXTO");
    montar();
    preencher();
    fireEvent.change(screen.getByLabelText("Adicionar colega"), { target: { value: "caio" } });
    fireEvent.click(screen.getByRole("button", { name: /Caio Reis/ }));
    fireEvent.click(screen.getByRole("button", { name: "Tirar Caio Reis" }));
    fireEvent.click(screen.getByRole("button", { name: /Ver o texto formatado/ }));
    await screen.findByText("TEXTO");
    expect(screen.getByRole("button", { name: "Revisar e assinar" })).toBeTruthy();
  });
});
