import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { PainelApreciacao } from "./painel-apreciacao";
import type { PautaOut } from "@/lib/contrato";

// docs/23 Fatia 4b — "Em apreciação" no Comando da Mesa: um botão Anunciar por item; o item anunciado ganha o
// selo; o POST vai sem corpo para a rota do item; a recusa do servidor aparece com a mensagem do domínio.

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const pauta = (emApreciacao?: string): PautaOut => ({
  "sessao-id": "s1",
  itens: [
    { id: "i1", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata da 14ª sessão", ordem: 1 },
    {
      id: "i22", fase: "ordem_do_dia", "tipo-item": "proposicao", "proposicao-id": "p22", ordem: 2,
      proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22, ementa: "Energia solar em prédios públicos" },
    },
  ],
  ...(emApreciacao ? { "em-apreciacao": { "item-id": emApreciacao, "anunciado-em": "2026-09-24T12:05:00Z" } } : {}),
});

let posts: { url: string; init?: RequestInit }[];
let resposta: Response;

beforeEach(() => {
  posts = [];
  resposta = json(201, { id: "a1", "item-id": "i22", "anunciado-em": "2026-09-24T12:05:00Z" });
  global.fetch = vi.fn(async (input: string, init?: RequestInit) => {
    posts.push({ url: String(input), init });
    return resposta;
  }) as unknown as typeof fetch;
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("PainelApreciacao", () => {
  it("lista a pauta com um Anunciar por item; nada anunciado ainda", () => {
    render(<PainelApreciacao sessaoId="s1" token="tok" pauta={pauta()} onAnunciado={() => {}} />);
    expect(screen.getByRole("button", { name: "Anunciar Leitura" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Anunciar PL 22/2026" })).toBeTruthy();
    expect(screen.queryByText("Em apreciação", { selector: ".apr-selo" })).toBeNull();
  });

  it("anunciar: POST sem corpo na rota do item, aviso de sucesso e recarga da pauta", async () => {
    const onAnunciado = vi.fn();
    render(<PainelApreciacao sessaoId="s1" token="tok" pauta={pauta()} onAnunciado={onAnunciado} />);
    fireEvent.click(screen.getByRole("button", { name: "Anunciar PL 22/2026" }));
    expect(await screen.findByText("PL 22/2026 em apreciação — a TV do plenário já mostra a matéria.")).toBeTruthy();
    expect(posts).toHaveLength(1);
    expect(posts[0].url).toBe("/api/sessoes/s1/pauta/itens/i22/anuncio");
    expect(posts[0].init?.method).toBe("POST");
    expect(posts[0].init?.body).toBeUndefined();
    expect(onAnunciado).toHaveBeenCalledTimes(1);
  });

  it("o item em apreciação (da pauta) ganha o selo, não o botão", () => {
    render(<PainelApreciacao sessaoId="s1" token="tok" pauta={pauta("i22")} onAnunciado={() => {}} />);
    const linha = screen.getByText("PL 22/2026").closest("li") as HTMLElement;
    expect(within(linha).getByText("Em apreciação")).toBeTruthy();
    expect(within(linha).queryByRole("button")).toBeNull();
    expect(screen.getByRole("button", { name: "Anunciar Leitura" })).toBeTruthy();
  });

  it("recusa do servidor aparece como alerta com a mensagem do domínio, sem recarregar", async () => {
    resposta = json(409, { erro: "so' se anuncia item com a sessao aberta" });
    const onAnunciado = vi.fn();
    render(<PainelApreciacao sessaoId="s1" token="tok" pauta={pauta()} onAnunciado={onAnunciado} />);
    fireEvent.click(screen.getByRole("button", { name: "Anunciar Leitura" }));
    expect((await screen.findByRole("alert")).textContent).toBe("so' se anuncia item com a sessao aberta");
    expect(onAnunciado).not.toHaveBeenCalled();
  });
});
