import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

const estado = vi.hoisted(() => ({
  papeis: [] as string[],
  fase: "pronto" as "carregando" | "pronto" | "erro",
  caminho: "/inicio",
  busca: "",
}));

vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tok", papeis: estado.papeis }),
  usePapeis: () => ({ papeis: estado.papeis, estado: estado.fase }),
}));
vi.mock("next/navigation", () => ({
  usePathname: () => estado.caminho,
  useSearchParams: () => new URLSearchParams(estado.busca),
}));

import { MolduraDaClara, useAbrirClara } from "./moldura-da-clara";
import { SUGESTOES_CLARA_CONSULTA, SUGESTOES_CLARA_JURIDICO, SUGESTOES_CLARA_SECRETARIA } from "./clara";

const fetchMock = vi.fn(async () => ({ ok: true, status: 200, text: async () => "", json: async () => ({}) }) as Response);

function montar(publico?: "vereador") {
  render(
    <MolduraDaClara publico={publico}>
      <main>a página</main>
    </MolduraDaClara>,
  );
}

async function perguntar(texto: string) {
  global.fetch = fetchMock as unknown as typeof fetch;
  fetchMock.mockClear();
  fireEvent.click(screen.getByRole("button", { name: /Pergunte à Clara/ }));
  fireEvent.change(await screen.findByLabelText("Sua pergunta"), { target: { value: texto } });
  fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
  await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
  const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
  return JSON.parse(init.body as string);
}

/** A aba (ou o botão) da página que abre a Clara. */
function Aba() {
  const clara = useAbrirClara();
  if (!clara.disponivel) return <p>sem Clara</p>;
  return (
    <button type="button" aria-controls={clara.painelId} aria-expanded={clara.aberta} onClick={(e) => clara.abrir("expandido", e.currentTarget)}>
      Assistente
    </button>
  );
}

describe("MolduraDaClara", () => {
  afterEach(() => {
    cleanup();
    estado.busca = "";
    delete document.documentElement.dataset.clara;
  });

  it("a Clara é de toda a Casa: secretaria, vereador, jurídico, controle interno e administração", () => {
    for (const papeis of [["secretario"], ["vereador"], ["secretario", "admin_ente"], ["juridico"], ["auditor"], ["admin_ente"]]) {
      Object.assign(estado, { papeis, fase: "pronto", caminho: "/proposicoes" });
      montar();
      expect(screen.getByRole("button", { name: /Pergunte à Clara/ })).toBeTruthy();
      expect(document.querySelector(".clara-moldura")?.hasAttribute("data-clara-ativa")).toBe(true);
      cleanup();
    }
  });

  it("sem papel da Casa, nada de Clara; a página continua na moldura", () => {
    for (const papeis of [[], ["cidadao"]]) {
      Object.assign(estado, { papeis, fase: "pronto", caminho: "/caixa" });
      montar();
      expect(screen.queryByRole("button", { name: /Pergunte à Clara/ })).toBeNull();
      expect(screen.getByText("a página").closest(".clara-moldura")?.hasAttribute("data-clara-ativa")).toBe(false);
      cleanup();
    }
  });

  it("discreta (as telas da sessão): a moldura marca, e o botão segue com o nome acessível e o atalho no title", () => {
    Object.assign(estado, { papeis: ["secretario"], fase: "pronto", caminho: "/sessoes/s1/conduzir" });
    render(
      <MolduraDaClara discreta>
        <main>a página</main>
      </MolduraDaClara>,
    );
    expect(document.querySelector(".clara-moldura")?.hasAttribute("data-clara-discreta")).toBe(true);
    const lancador = screen.getByRole("button", { name: "Pergunte à Clara" });
    expect(lancador.getAttribute("title")).toBe("Pergunte à Clara (Ctrl + /)");
    // o seletor do CSS (`.clara-moldura[data-clara-discreta] ~ .ast-lancador`) pede o botão como irmão da moldura
    expect(lancador.matches(".clara-moldura[data-clara-discreta] ~ .ast-lancador")).toBe(true);
    cleanup();
    Object.assign(estado, { caminho: "/proposicoes" });
    montar();
    expect(document.querySelector(".clara-moldura")?.hasAttribute("data-clara-discreta")).toBe(false);
  });

  it("enquanto os papéis carregam, nada de Clara", () => {
    Object.assign(estado, { papeis: ["secretario"], fase: "carregando", caminho: "/inicio" });
    montar();
    expect(screen.queryByRole("button", { name: /Pergunte à Clara/ })).toBeNull();
  });

  it("nas telas internas o corpo não manda o público: o core escolhe pelos papéis", async () => {
    for (const papeis of [["vereador"], ["auditor"], ["secretario", "vereador"]]) {
      Object.assign(estado, { papeis, fase: "pronto", caminho: "/proposicoes" });
      montar();
      expect(await perguntar("oi, tudo bem?")).toEqual({ pergunta: "oi, tudo bem?" });
      cleanup();
    }
  });

  it("quem só consulta vê perguntas de leitura; a secretaria, as dela", () => {
    Object.assign(estado, { papeis: ["juridico"], fase: "pronto", caminho: "/juridico" });
    montar();
    fireEvent.click(screen.getByRole("button", { name: /Pergunte à Clara/ }));
    for (const s of SUGESTOES_CLARA_JURIDICO) expect(screen.getByRole("button", { name: s })).toBeTruthy();
    expect(screen.getByText(/Só consulta o sistema/)).toBeTruthy();
    cleanup();
    // o controle interno e a administração não leem a matéria pela tela: a Clara não a oferece
    for (const papeis of [["auditor"], ["admin_ente"]]) {
      Object.assign(estado, { papeis });
      montar();
      fireEvent.click(screen.getByRole("button", { name: /Pergunte à Clara/ }));
      for (const s of SUGESTOES_CLARA_CONSULTA) expect(screen.getByRole("button", { name: s })).toBeTruthy();
      expect(screen.queryByText(/Situação de matérias/)).toBeNull();
      expect(screen.queryByRole("button", { name: /PL 11\/2026|contas do Prefeito/ })).toBeNull();
      cleanup();
    }
    Object.assign(estado, { papeis: ["secretario", "juridico"] });
    montar();
    fireEvent.click(screen.getByRole("button", { name: /Pergunte à Clara/ }));
    for (const s of SUGESTOES_CLARA_SECRETARIA) expect(screen.getByRole("button", { name: s })).toBeTruthy();
    expect(screen.queryByRole("button", { name: SUGESTOES_CLARA_JURIDICO[3] })).toBeNull();
  });

  it("o controle interno: a Clara diz que não lê a trilha nem as conversas, e aponta a Auditoria", () => {
    Object.assign(estado, { papeis: ["auditor"], fase: "pronto", caminho: "/auditoria" });
    montar();
    fireEvent.click(screen.getByRole("button", { name: /Pergunte à Clara/ }));
    expect(screen.getByText(/A Clara não lê a trilha nem as conversas da Casa/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Auditoria" }).getAttribute("href")).toBe("/auditoria/clara?token=tok");
    cleanup();
    // quem também é secretaria pergunta como secretaria: a linha não aparece
    Object.assign(estado, { papeis: ["secretario", "auditor"] });
    montar();
    fireEvent.click(screen.getByRole("button", { name: /Pergunte à Clara/ }));
    expect(screen.queryByText(/A Clara não lê a trilha/)).toBeNull();
  });

  it("no app do vereador: pergunta com o conjunto do vereador mesmo para quem também é secretaria", async () => {
    Object.assign(estado, { papeis: ["secretario", "vereador"], fase: "pronto", caminho: "/vereador" });
    montar("vereador");
    expect(await perguntar("oi, tudo bem?")).toEqual({ pergunta: "oi, tudo bem?", publico: "vereador" });
  });

  it("a aba abre a Clara expandida no campo, diz que está aberta, e Esc recolhe e devolve o foco à aba", async () => {
    Object.assign(estado, { papeis: ["vereador"], fase: "pronto", caminho: "/vereador" });
    render(
      <MolduraDaClara publico="vereador">
        <Aba />
      </MolduraDaClara>,
    );
    const aba = screen.getByRole("button", { name: "Assistente" });
    expect(aba.getAttribute("aria-expanded")).toBe("false");
    const painel = document.getElementById(aba.getAttribute("aria-controls") ?? "");
    expect(painel?.tagName).toBe("ASIDE");
    aba.focus();
    fireEvent.click(aba);
    expect(document.documentElement.dataset.clara).toBe("expandido");
    expect(document.activeElement).toBe(screen.getByLabelText("Sua pergunta"));
    // o tamanho mora no <html data-clara>; quem abre o lê por um MutationObserver (avisa depois do microtask)
    await vi.waitFor(() => expect(aba.getAttribute("aria-expanded")).toBe("true"));
    // de novo, já expandida: só volta ao campo
    aba.focus();
    fireEvent.click(aba);
    expect(document.activeElement).toBe(screen.getByLabelText("Sua pergunta"));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(document.documentElement.dataset.clara).toBe("recolhido");
    expect(document.activeElement).toBe(aba);
    await vi.waitFor(() => expect(aba.getAttribute("aria-expanded")).toBe("false"));
  });

  it("aberta pelo botão da própria Clara, o foco volta a ele (não à aba de antes)", () => {
    Object.assign(estado, { papeis: ["vereador"], fase: "pronto", caminho: "/vereador" });
    render(
      <MolduraDaClara publico="vereador">
        <Aba />
      </MolduraDaClara>,
    );
    const aba = screen.getByRole("button", { name: "Assistente" });
    fireEvent.click(aba);
    fireEvent.keyDown(document, { key: "Escape" });
    const lancador = screen.getByRole("button", { name: /Pergunte à Clara/ });
    fireEvent.click(lancador);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(document.activeElement).toBe(lancador);
  });

  it("sem a Clara na tela (papéis carregando), a aba não aparece", () => {
    Object.assign(estado, { papeis: ["vereador"], fase: "carregando", caminho: "/vereador" });
    render(
      <MolduraDaClara publico="vereador">
        <Aba />
      </MolduraDaClara>,
    );
    expect(screen.getByText("sem Clara")).toBeTruthy();
  });

  it("?clara=expandida (o endereço antigo /vereador/assistente) abre a Clara expandida e sai da URL", async () => {
    window.history.replaceState(null, "", "/vereador?clara=expandida&token=tok");
    const trocar = vi.spyOn(window.history, "replaceState");
    Object.assign(estado, { papeis: ["vereador"], fase: "pronto", caminho: "/vereador", busca: "clara=expandida&token=tok" });
    await act(async () => montar("vereador"));
    expect(document.documentElement.dataset.clara).toBe("expandido");
    expect(document.activeElement).toBe(screen.getByLabelText("Sua pergunta"));
    expect(trocar).toHaveBeenCalledWith(null, "", "/vereador?token=tok");
    expect(window.location.search).toBe("?token=tok");
    trocar.mockRestore();
    window.history.replaceState(null, "", "/");
  });
});
