import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { CentralDaCasa, PainelCentral } from "./central-da-casa";
import { derivarCentral } from "@/lib/central-vista";

// docs/23 Fatia 3 — a Central da Casa contra uma API FALSA: prova a fiação inteira (hook → vista → tela), que só
// busca o detalhe das sessões que a tela mostra e que uma fonte que falha é dita, não vira fila vazia.
// Relógio fixo: 24/09/2026 09:00 em Fortaleza.

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

const base = {
  "sessao-legislativa-id": "sl", "tipo-sessao": "ordinaria", modalidade: "presencial", delibera: true,
  "transmite-publica": true, "gera-ata-regimental": true, "permite-voto-secreto": false,
  "permite-modalidade-remota": false, "aberta-em": null, "encerrada-em": null, "motivo-nao-realizada": null, "lock-version": 0,
};
const SESSOES = [
  { ...base, id: "hoje", "numero-sequencial": 15, estado: "agendada", "agendada-para": "2026-09-24T17:00:00Z" },
  { ...base, id: "prox", "numero-sequencial": 1, "tipo-sessao": "extraordinaria", estado: "agendada", "agendada-para": "2026-10-01T18:00:00Z" },
  { ...base, id: "ontem", "numero-sequencial": 14, estado: "encerrada", "aberta-em": "2026-09-17T12:00:00Z", "encerrada-em": "2026-09-17T15:00:00Z" },
];

let falhar: Set<string>;
let urls: string[];

function servidor() {
  global.fetch = vi.fn(async (input: string) => {
    const url = String(input);
    urls.push(url);
    if ([...falhar].some((f) => url.startsWith(f))) return json(500, { erro: "x" });
    switch (url) {
      case "/api/sessoes": return json(200, { sessoes: SESSOES });
      case "/api/meu/identidade": return json(200, { nome: "Rita Campos", papeis: ["secretario"] });
      case "/api/paineis/pendencias":
        return json(200, { pendencias: [{ "objeto-tipo": "pedido_esic", "objeto-id": "e1", protocolo: "2026/0112", "vence-em": "2026-09-26T23:00:00Z", estado: "aberta" }], "pendencias-total": 1 });
      case "/api/compliance/painel":
        return json(200, { resumo: {}, "em-aberto": [{ id: "o1", "template-chave": "balancete-mensal", "objeto-tipo": "x", "objeto-id": "y", "vence-em": "2026-09-29", estado: "pendente" }], "em-aberto-total": 1, "remessas-recentes": [], "remessas-recentes-total": 0 });
      case "/api/moderacao/comentarios":
        return json(200, [{ id: "c1", "proposicao-id": "p", "autor-identidade-id": "a", corpo: "x", denunciado: true, "criado-em": "2026-09-23T00:00:00Z" }]);
      case "/api/paineis/tramitacao":
        return json(200, { itens: [], "totais-por-estado": [{ estado: "aguardando_pauta", total: 4 }, { estado: "em_comissoes", total: 7 }] });
      case "/api/sessoes/hoje/pauta": return json(200, { "sessao-id": "hoje", itens: [{ id: "i1" }, { id: "i2" }] });
      case "/api/sessoes/prox/pauta": return json(200, { "sessao-id": "prox", itens: [] });
      case "/api/sessoes/ontem/folhas": return json(200, { "sessao-id": "ontem", folhas: [] });
      case "/api/sessoes/ontem/justificativas":
        return json(200, { "sessao-id": "ontem", justificativas: [{ id: "j1", estado: "pendente" }, { id: "j2", estado: "pendente" }, { id: "j3", estado: "aprovada" }] });
    }
    return json(404, {});
  }) as unknown as typeof fetch;
}

// Cleanup no nível do ARQUIVO, para os dois `describe`: a suíte roda sem `globals`, então o Testing Library não
// desmonta sozinho. Sem isto as árvores do `PainelCentral` ficavam montadas até o fim do arquivo e os `<Link>`
// do Next agendavam trabalho depois que o jsdom já tinha caído — os "3 unhandled errors" (`window is not
// defined`, um por teste do describe) que apareciam sob carga no CI.
afterEach(() => cleanup());

describe("CentralDaCasa", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-09-24T12:00:00Z"));
    falhar = new Set();
    urls = [];
    servidor();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("a sessão de hoje em foco: trilha com a etapa atual e UMA ação principal", async () => {
    render(<CentralDaCasa token="tok" />);
    const foco = (await screen.findByRole("heading", { level: 2, name: "15ª sessão ordinária" })).closest("section") as HTMLElement;
    expect(within(foco).getByText("Sessão de hoje")).toBeTruthy();
    const trilha = within(foco).getByRole("list", { name: "Trilha da sessão" });
    expect(within(trilha).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "Agendada24/09 — concluída",
      "Pauta2 itens — concluída",
      "Em cursoa abrir — etapa atual",
      "Encerrada — pendente",
      "Folha — pendente",
    ]);
    expect(within(foco).getByRole("link", { name: "Abrir no Comando da Mesa" }).getAttribute("href")).toBe("/sessoes/hoje/conduzir?token=tok");
    expect(within(foco).getByRole("link", { name: "Ver pauta e convocação" }).getAttribute("href")).toBe("/pauta-convocacao?sessao=hoje&token=tok");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Bom dia, Rita.");
  });

  it("fila de trabalho com cada sinal medido, prazos legais primeiro", async () => {
    render(<CentralDaCasa token="tok" />);
    const fila = (await screen.findByRole("heading", { level: 2, name: /Fila de trabalho/ })).closest("section") as HTMLElement;
    await waitFor(() => expect(within(fila).getAllByRole("heading", { level: 3 })).toHaveLength(5));
    expect(within(fila).getAllByRole("heading", { level: 3 }).map((h) => h.textContent)).toEqual([
      "Pedido e-SIC 2026/0112",
      "Obrigação TCE em aberto · balancete-mensal",
      "Gerar a folha da 14ª sessão ordinária",
      "2 justificativas de ausência a decidir",
      "1 comentário do portal para moderar",
    ]);
    expect(within(fila).getByText("vence em 2 dias")).toBeTruthy();
    expect(within(fila).getByRole("link", { name: "Decidir" }).getAttribute("href")).toBe("/sessoes/ontem/chamada?token=tok");
    expect(within(fila).getByRole("heading", { level: 2 }).textContent).toBe("Fila de trabalho · 5");
  });

  it("próximas sessões: pauta vazia vira alerta com 'Montar a pauta'; matérias prontas para pauta", async () => {
    render(<CentralDaCasa token="tok" />);
    const link = await screen.findByRole("link", { name: "Montar a pauta da 1ª sessão extraordinária" });
    expect(link.getAttribute("href")).toBe("/pauta-convocacao?sessao=prox&token=tok");
    expect(screen.getByText("Pauta vazia")).toBeTruthy();
    expect(await screen.findByText("4 matérias estão prontas")).toBeTruthy();
  });

  it("só busca o detalhe que a tela mostra: nada de folha/justificativas de sessão agendada", async () => {
    render(<CentralDaCasa token="tok" />);
    await screen.findByText("2 justificativas de ausência a decidir");
    expect(urls).not.toContain("/api/sessoes/hoje/folhas");
    expect(urls).not.toContain("/api/sessoes/prox/justificativas");
    expect(urls).not.toContain("/api/sessoes/ontem/pauta");
  });

  it("fonte que falha é dita — o resto da Central continua de pé", async () => {
    falhar = new Set(["/api/moderacao/comentarios", "/api/compliance/painel"]);
    render(<CentralDaCasa token="tok" />);
    expect((await screen.findByRole("status")).textContent).toBe("Não foi possível carregar: obrigações do TCE; comentários para moderar.");
    expect(await screen.findByText("Pedido e-SIC 2026/0112")).toBeTruthy();
    expect(screen.queryByText(/Nada pendente/)).toBeNull();
  });
});

describe("PainelCentral — estados do foco", () => {
  const entrada = {
    agoraIso: "2026-09-24T12:00:00Z", nome: null, detalhes: {},
    pendencias: { itens: [], total: 0 }, compliance: { itens: [], total: 0 }, moderacao: [], tramitacao: [],
  };

  it("carregando: não afirma que não há sessão", () => {
    const { container } = render(<PainelCentral vista={derivarCentral({ ...entrada, sessoes: null, estadoSessoes: "carregando" })} token={null} />);
    expect(container.querySelector('.cc-foco[aria-busy="true"]')).toBeTruthy();
    expect(screen.queryByText("Nenhuma sessão agendada")).toBeNull();
  });

  it("sem sessão e sem pendência: oferece agendar e diz que a fila está limpa", () => {
    render(<PainelCentral vista={derivarCentral({ ...entrada, sessoes: [], estadoSessoes: "pronto" })} token={null} />);
    const foco = screen.getByRole("heading", { name: "Nenhuma sessão agendada" }).closest("section") as HTMLElement;
    expect(within(foco).getByRole("link", { name: "Agendar sessão" }).getAttribute("href")).toBe("/agendar-sessao");
    expect(screen.getByText(/Nada pendente\. A fila se enche sozinha/)).toBeTruthy();
  });

  it("erro nas sessões é dito, distinto de 'nenhuma'", () => {
    render(<PainelCentral vista={derivarCentral({ ...entrada, sessoes: null, estadoSessoes: "erro" })} token={null} />);
    expect(screen.getByText("Não foi possível carregar as sessões")).toBeTruthy();
  });
});
