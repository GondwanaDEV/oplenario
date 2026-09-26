import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { FilaGravacoes } from "./fila-gravacoes";

const grav = (id: string, over: Record<string, unknown> = {}) => ({
  id,
  "iniciou-em": "2026-09-22T20:50:00Z",
  "encerrou-em": "2026-09-23T00:00:00Z",
  "fonte-ingestao": "gravacao_local_pos_sessao",
  "acesso-restrito": false,
  "audio-hash": "ab",
  "lock-version": 2,
  sugestao: {
    "sessao-id": "s-12",
    "tipo-sessao": "ordinaria",
    "numero-sequencial": 12,
    estado: "encerrada",
    inicio: "2026-09-22T21:00:00Z",
  },
  ...over,
});

const sessao = (id: string, numero: number, estado: string, abertaEm: string | null) => ({
  id,
  "sessao-legislativa-id": "sl",
  "tipo-sessao": "ordinaria",
  "numero-sequencial": numero,
  estado,
  modalidade: "presencial",
  delibera: true,
  "transmite-publica": true,
  "gera-ata-regimental": true,
  "permite-voto-secreto": false,
  "permite-modalidade-remota": false,
  "aberta-em": abertaEm,
  "lock-version": 0,
});

function mockar(fila: Array<ReturnType<typeof grav>>, respostaVinculo = { ok: true, status: 200, body: {} }) {
  const fetchMock = vi.fn(async (url: string, opts?: { method?: string; body?: string }) => {
    if (opts?.method === "POST") {
      if (respostaVinculo.ok) fila.splice(0, fila.length, ...fila.filter((g) => !url.includes(`/gravacao/${g.id}/`)));
      return { ok: respostaVinculo.ok, status: respostaVinculo.status, json: async () => respostaVinculo.body } as Response;
    }
    if (url === "/api/sessoes")
      return {
        ok: true,
        status: 200,
        json: async () => ({
          sessoes: [
            sessao("s-11", 11, "encerrada", "2026-09-15T21:00:00Z"),
            sessao("s-12", 12, "encerrada", "2026-09-22T21:00:00Z"),
            sessao("s-nr", 13, "nao_realizada", null),
          ],
        }),
      } as Response;
    return { ok: true, status: 200, json: async () => ({ segmentos: fila }) } as Response;
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

describe("FilaGravacoes", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra a gravação com duração e fonte e vincula à sessão sugerida em um toque", async () => {
    const fila = [grav("g1")];
    const fetchMock = mockar(fila);
    render(<FilaGravacoes token="tok" />);

    expect(await screen.findByText("1 gravação esperando vínculo")).toBeTruthy();
    expect(screen.getByText("3 h 10 min · Gravação local")).toBeTruthy();
    expect(screen.getByText(/Sessão ordinária nº 12/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Vincular a esta sessão" }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/sessoes/s-12/gravacao/g1/vincular",
        expect.objectContaining({ method: "POST", body: JSON.stringify({ "lock-version": 2 }) }),
      ),
    );
    expect(await screen.findByText("Nenhuma gravação esperando vínculo.")).toBeTruthy();
  });

  it("sem sugestão: escolhe a sessão na lista (a não realizada não aparece)", async () => {
    const fila = [grav("g2", { sugestao: null, "acesso-restrito": true })];
    const fetchMock = mockar(fila);
    render(<FilaGravacoes token="tok" />);

    expect(await screen.findByText(/Nenhuma sessão no horário/)).toBeTruthy();
    expect(screen.getByText("Restrita")).toBeTruthy();
    const select = screen.getByLabelText("Sessão") as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBe(3));
    expect([...select.options].map((o) => o.textContent)).not.toContain(expect.stringContaining("nº 13"));
    expect(select.options[1].textContent).toContain("nº 12"); // mais recente primeiro

    fireEvent.change(select, { target: { value: "s-11" } });
    fireEvent.click(screen.getByRole("button", { name: "Vincular" }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith("/api/sessoes/s-11/gravacao/g2/vincular", expect.objectContaining({ method: "POST" })),
    );
  });

  it("'é de outra sessão' troca a sugestão pela lista", async () => {
    mockar([grav("g3")]);
    render(<FilaGravacoes token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "É de outra sessão" }));
    expect(screen.getByLabelText("Sessão")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Vincular a esta sessão" })).toBeNull();
  });

  it("conflito de vínculo (outra pessoa vinculou antes) avisa e recarrega", async () => {
    mockar([grav("g4")], { ok: false, status: 409, body: { erro: "segmento ja vinculado ou lock-version desatualizado" } });
    render(<FilaGravacoes token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "Vincular a esta sessão" }));
    expect((await screen.findByRole("alert")).textContent).toContain("já foi vinculada");
  });

  it("falha ao carregar → mensagem honesta", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    render(<FilaGravacoes token="tok" />);
    expect(await screen.findByText("Não foi possível carregar as gravações.")).toBeTruthy();
  });
});
