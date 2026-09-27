import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }), useParams: () => ({ id: "v1" }) }));

import { ListaNormas } from "./lista-normas";
import { FormImportar } from "./nova/form-importar";
import { ConferenciaVersao } from "./versoes/[id]/conferencia-versao";

const norma = { id: "n1", camada: "casa", especie: "regimento_interno", titulo: "Regimento Interno da Câmara", numero: null, data: null, "da-casa": true };
const resumo = (estado: string) => ({ id: "v1", estado, "consolidada-ate": null, fonte: "enviado pela Casa", "n-dispositivos": 5,
  "n-alertas": 1, "enviada-em": "2026-09-27T10:00:00Z", "decidida-em": estado === "em_conferencia" ? null : "2026-09-27T11:00:00Z" });
const versao = (estado: string) => ({
  norma, versao: resumo(estado), alertas: ["Depois do art. 2º vem o art. 4º: confira se faltou algum artigo (ou se foi revogado)."],
  dispositivos: [
    { endereco: "art1", rotulo: "art. 1º", tipo: "artigo", pai: null, ordem: 0, texto: "A Câmara funciona na sede.", agrupador: "CAPÍTULO I — DA SEDE" },
    { endereco: "art1_par1u", rotulo: "art. 1º, parágrafo único", tipo: "paragrafo", pai: "art1", ordem: 1, texto: "Pode mudar.", agrupador: "CAPÍTULO I — DA SEDE" },
  ],
});

function mockar(...respostas: { status?: number; corpo: unknown }[]) {
  const fila = [...respostas];
  const f = vi.fn(async () => {
    const r = fila.length > 1 ? fila.shift()! : fila[0];
    const status = r.status ?? 200;
    return { ok: status < 300, status, json: async () => r.corpo } as Response;
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

describe("normas", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    push.mockReset();
  });

  it("lista: sem vigente ainda, e a versão esperando conferência", async () => {
    mockar({ corpo: { normas: [{ norma, vigente: null, "em-conferencia": resumo("em_conferencia") }] } });
    render(<ListaNormas token="t" />);
    expect(await screen.findByText("Regimento Interno da Câmara")).toBeTruthy();
    expect(screen.getByText(/nada desta norma vale até alguém conferir/)).toBeTruthy();
    expect(screen.getByText(/1 ponto a conferir/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Conferir" }).getAttribute("href")).toContain("/normas/versoes/v1");
  });

  it("lista vazia orienta por onde começar", async () => {
    mockar({ corpo: { normas: [] } });
    render(<ListaNormas token="t" />);
    expect(await screen.findByText(/Comece pela Lei Orgânica/)).toBeTruthy();
  });

  it("importar: manda o texto e vai para a conferência; LOM não tem número", async () => {
    const f = mockar({ status: 201, corpo: versao("em_conferencia") });
    render(<FormImportar token="t" />);
    expect(screen.queryByLabelText("Número")).toBeNull();
    fireEvent.change(screen.getByLabelText("De onde veio o texto"), { target: { value: "enviado pela Casa" } });
    fireEvent.change(screen.getByLabelText("Texto"), { target: { value: "Art. 1º Um." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar para conferência" }));
    await waitFor(() => expect(push).toHaveBeenCalled());
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/normas/versoes");
    expect(JSON.parse(init.body as string)).toEqual({ especie: "lei_organica", titulo: "Lei Orgânica do Município",
      fonte: "enviado pela Casa", texto: "Art. 1º Um." });
    expect(push.mock.calls[0][0]).toContain("/normas/versoes/v1");
  });

  it("importar: conflito aparece com a mensagem do sistema", async () => {
    mockar({ status: 409, corpo: { erro: "Esta norma já tem uma versão esperando conferência." } });
    render(<FormImportar token="t" />);
    fireEvent.change(screen.getByLabelText("De onde veio o texto"), { target: { value: "x" } });
    fireEvent.change(screen.getByLabelText("Texto"), { target: { value: "Art. 1º Um." } });
    fireEvent.click(screen.getByRole("button", { name: "Enviar para conferência" }));
    expect(await screen.findByText(/já tem uma versão esperando conferência/)).toBeTruthy();
  });

  it("conferência: texto em dispositivos, pontos a conferir e publicar em dois passos", async () => {
    const f = mockar({ corpo: versao("em_conferencia") }, { corpo: versao("vigente") });
    render(<ConferenciaVersao token="t" id="v1" />);
    expect(await screen.findByText("A Câmara funciona na sede.")).toBeTruthy();
    expect(screen.getByText("CAPÍTULO I — DA SEDE")).toBeTruthy();
    expect(screen.getByText("Parágrafo único.")).toBeTruthy();
    expect(screen.getByText(/Depois do art. 2º vem o art. 4º/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Conferi o texto" }));
    expect(screen.getByText(/Publicar esta versão\?/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Publicar" }));
    expect(await screen.findByText("Vigente")).toBeTruthy();
    const [url, init] = f.mock.calls[1] as unknown as [string, RequestInit];
    expect(url).toBe("/api/normas/versoes/v1/conferencia");
    expect(JSON.parse(init.body as string)).toEqual({ decisao: "publicar" });
    expect(screen.queryByRole("button", { name: "Conferi o texto" })).toBeNull();
  });
});
