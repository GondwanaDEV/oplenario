import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PublicarPauta } from "./publicar-pauta";

// ADR-0019 fatia 3: o painel "Publicar a pauta" — selo, avisos (que não bloqueiam), confirmação explícita, a
// justificativa da republicação e o botão desabilitado com o motivo quando a regra da Casa diz que outra pessoa publica.

type Rota = { status?: number; corpo: unknown };
type Chamada = { metodo: string; url: string; body: unknown };

function mockar(rotas: Record<string, Rota | ((c: Chamada) => Rota)>) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const c: Chamada = { metodo: init?.method ?? "GET", url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    chamadas.push(c);
    const r = rotas[`${c.metodo} ${c.url}`];
    const rota = typeof r === "function" ? r(c) : r;
    if (!rota) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = rota.status ?? 200;
    return { ok: status < 300, status, json: async () => rota.corpo } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

const URL = "/api/sessoes/s1/pauta/publicacao";
const T = "2026-10-06T17:30:00Z";

const pub = (extra: Record<string, unknown> = {}) => ({
  "sessao-id": "s1",
  regra: { "quem-publica": "secretaria", "antecedencia-minima-horas": 24, configurada: true },
  "pode-publicar": true,
  republicacao: false,
  "itens-na-pauta": 2,
  versoes: [],
  "alterada-desde-a-publicacao": false,
  avisos: [
    { tipo: "sem-parecer-comissao", "item-id": "i1", "proposicao-id": "p1", proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 7, ementa: "Hortas" } },
    { tipo: "antecedencia-nao-cumprida", "minimo-horas": 24, "horas-reais": 10 },
  ],
  "avisos-indisponiveis": false,
  ...extra,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("PublicarPauta", () => {
  it("mostra que ainda não foi publicada, a regra e os avisos — e publica depois da confirmação explícita", async () => {
    let publicado = false;
    const chamadas = mockar({
      [`GET ${URL}`]: () => ({
        corpo: publicado
          ? pub({ ultima: { versao: 1, "tipo-versao": "publicacao_inicial", "publicada-em": T, itens: 2 }, versoes: [{ versao: 1, "tipo-versao": "publicacao_inicial", "publicada-em": T, itens: 2, "publicada-por-nome": "Marina" }], republicacao: true })
          : pub(),
      }),
      [`POST ${URL}`]: () => {
        publicado = true;
        return { status: 201, corpo: { "sessao-id": "s1", versao: 1, "tipo-versao": "publicacao_inicial", "publicada-em": T, itens: 2, avisos: [], aviso: "antecedencia-nao-cumprida" } };
      },
    });
    render(<PublicarPauta token="tk" sessaoId="s1" />);
    expect(await screen.findByText("Pauta ainda não publicada")).toBeTruthy();
    expect(screen.getByText(/publica a secretaria legislativa; antecedência mínima de 24 h/)).toBeTruthy();
    expect(screen.getByText("PL 7/2026: sem parecer da comissão.")).toBeTruthy();
    expect(screen.getByText(/faltam 10 h para o início/)).toBeTruthy();
    expect(screen.getByText("Avisos (não impedem a publicação)")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Publicar a pauta" }));
    expect(chamadas.filter((c) => c.metodo === "POST")).toHaveLength(0);
    expect(screen.getByText(/Publicar a pauta com 2 itens e 2 aviso\(s\)\?/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar publicação" }));
    expect(await screen.findByText(/Pauta publicada: versão 1\. Atenção: publicada fora da antecedência mínima/)).toBeTruthy();
    expect(chamadas.find((c) => c.metodo === "POST")?.body).toEqual({});
    await waitFor(() => expect(document.querySelector(".pp-selo")?.textContent).toMatch(/^Publicada v1 em/));
    expect(screen.getByText("Histórico de publicações (1)")).toBeTruthy();
  });

  it("republicação: exige dizer o que mudou e envia a justificativa; 'alterada desde a publicação v1'", async () => {
    const ultima = { versao: 1, "tipo-versao": "publicacao_inicial", "publicada-em": T, itens: 2 };
    const chamadas = mockar({
      [`GET ${URL}`]: { corpo: pub({ ultima, versoes: [ultima], republicacao: true, "alterada-desde-a-publicacao": true, avisos: [] }) },
      [`POST ${URL}`]: { status: 201, corpo: { "sessao-id": "s1", versao: 2, "tipo-versao": "republicacao", "publicada-em": T, itens: 3, avisos: [] } },
    });
    render(<PublicarPauta token="tk" sessaoId="s1" />);
    expect(await screen.findByText(/Alterada desde a publicação v1/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Republicar a pauta" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar publicação" }));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toBe("Republicar exige dizer o que mudou.");
    expect(chamadas.filter((c) => c.metodo === "POST")).toHaveLength(0);
    fireEvent.change(screen.getByLabelText(/O que mudou desde a v1/), { target: { value: "Incluída a matéria X" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar publicação" }));
    expect(await screen.findByText("Pauta publicada: versão 2.")).toBeTruthy();
    expect(chamadas.find((c) => c.metodo === "POST")?.body).toEqual({ justificativa: "Incluída a matéria X" });
  });

  it("quem não pode publicar pela regra vê o botão desabilitado com o motivo", async () => {
    mockar({
      [`GET ${URL}`]: { corpo: pub({ "pode-publicar": false, motivo: "Pela regra desta Casa, quem publica a pauta é o Presidente da Câmara.", regra: { "quem-publica": "presidente", "antecedencia-minima-horas": null, configurada: true } }) },
    });
    render(<PublicarPauta token="tk" sessaoId="s1" />);
    const botao = await screen.findByRole("button", { name: "Publicar a pauta" });
    expect((botao as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Pela regra desta Casa, quem publica a pauta é o Presidente da Câmara.")).toBeTruthy();
  });

  it("recusa do servidor (409) vira frase; na home do vereador, quem não pode nem vê o painel", async () => {
    mockar({
      [`GET ${URL}`]: { corpo: pub() },
      [`POST ${URL}`]: { status: 409, corpo: { erro: "x", motivo: "pauta-mudou" } },
    });
    render(<PublicarPauta token="tk" sessaoId="s1" />);
    fireEvent.click(await screen.findByRole("button", { name: "Publicar a pauta" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar publicação" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/A pauta mudou enquanto você publicava/);
    cleanup();
    mockar({ [`GET ${URL}`]: { corpo: pub({ "pode-publicar": false, motivo: "não" }) } });
    const { container } = render(<PublicarPauta token="tk" sessaoId="s1" soQuemPode />);
    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container.textContent).toBe("");
  });
});
