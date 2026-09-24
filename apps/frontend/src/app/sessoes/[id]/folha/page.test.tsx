import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import PaginaFolha from "./page";
import type { FolhaMetadadosOut } from "@/lib/contrato-sessoes.gen";

// Este page.test.tsx mocka `useFolha` (não a rede) — mesma disciplina de chamada/page.test.tsx: o hook já
// tem os seus próprios testes de IO (use-folha.test.ts); aqui a prova é só que a PÁGINA lê `versoes`
// corretamente, mostra o estado vazio explícito (nunca uma tabela em branco), aciona `gerar`/`baixarPdf`
// pelos botões certos, e — a asserção mais importante desta fatia (D10 do brief) — que o `<iframe>` do
// visualizador carrega `sandbox=""` (string VAZIA, nunca `"allow-scripts allow-same-origin"`, que seria
// pior que não ter sandbox nenhum).
//
// SEM jest-dom (não instalado neste projeto — convenção documentada em formulario-proposicao.test.tsx /
// azulejo-faixa.test.tsx): asserções via `.toBeTruthy()`/`.textContent`/`.getAttribute()` cru, nunca
// `toBeInTheDocument`/`toHaveAttribute`.

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "s1" }),
  useSearchParams: () => ({ get: () => null }),
}));

vi.mock("@/lib/auth", () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ token: "tok" }),
}));

vi.mock("@/lib/tema", () => ({
  useTema: () => ({ tema: "claro", alternar: vi.fn() }),
}));

const gerar = vi.fn();
const buscarHtml = vi.fn();
const baixarPdf = vi.fn();
const useFolhaMock = vi.fn();

vi.mock("@/lib/use-folha", () => ({
  useFolha: (...args: unknown[]) => useFolhaMock(...args),
}));

function versao(over: Partial<FolhaMetadadosOut> = {}): FolhaMetadadosOut {
  return {
    id: "f1",
    versao: 1,
    specVersao: "folha-sessao-v1",
    htmlHash: "sha256:aaaabbbbccccdddd",
    pdfHash: "sha256:1111222233334444",
    geradaPor: "8b6f1c2a-0000-0000-0000-000000000000",
    geradaEm: "2026-08-15T14:00:00Z",
    ...over,
  };
}

function mockRetorno(versoes: FolhaMetadadosOut[] | null, extras: Partial<ReturnType<typeof useFolhaMock>> = {}) {
  useFolhaMock.mockReturnValue({
    versoes,
    estado: versoes ? "pronto" : "carregando",
    erro: null,
    recarregar: vi.fn(),
    gerar,
    buscarHtml,
    baixarPdf,
    ...extras,
  });
}

beforeEach(() => {
  // Defaults SÃOS: o visualizador dispara `buscarHtml` sozinho ao montar (auto-seleção da versão mais
  // recente) em praticamente todo teste desta suíte — um `vi.fn()` sem retorno devolveria `undefined`, e
  // `.then()` sobre `undefined` derruba o componente. Cada teste sobrepõe o que precisa exercitar.
  gerar.mockReset().mockResolvedValue({ ok: true, folha: versao() });
  buscarHtml.mockReset().mockResolvedValue({ ok: true, html: "<html><body>folha</body></html>" });
  baixarPdf.mockReset().mockResolvedValue({ ok: true });
});

afterEach(() => {
  cleanup();
});

describe("PaginaFolha — carregando/erro", () => {
  it("estado carregando mostra mensagem de carga, não a folha", () => {
    mockRetorno(null);
    render(<PaginaFolha />);
    expect(screen.getByText(/carregando a folha/i)).toBeTruthy();
  });

  it("estado erro mostra a mensagem do hook, não uma tela em branco", () => {
    mockRetorno(null, { estado: "erro", erro: "Sessão não encontrada." });
    render(<PaginaFolha />);
    expect(screen.getByText(/não foi possível abrir a folha/i)).toBeTruthy();
    expect(screen.getByText("Sessão não encontrada.")).toBeTruthy();
  });
});

describe("PaginaFolha — lista vazia", () => {
  it("sessão sem folha ainda mostra ESTADO VAZIO EXPLÍCITO — nunca uma lista/tabela em branco", () => {
    mockRetorno([]);
    render(<PaginaFolha />);
    expect(screen.getByText(/ainda não tem folha congelada/i)).toBeTruthy();
    expect(screen.queryByRole("list", { name: /versões congeladas/i })).toBeNull();
    expect(screen.getByRole("button", { name: /gerar a folha desta sessão/i })).toBeTruthy();
  });

  it("visualizador também mostra vazio explícito quando não há versão nenhuma", () => {
    mockRetorno([]);
    render(<PaginaFolha />);
    expect(screen.getByText(/gere a folha desta sessão para visualizar/i)).toBeTruthy();
  });
});

describe("PaginaFolha — lista com versões", () => {
  it("mostra a mais recente primeiro e os dois hashes de cada versão", () => {
    mockRetorno([versao({ id: "f1", versao: 1 }), versao({ id: "f2", versao: 2 })]);
    render(<PaginaFolha />);
    const itens = screen.getAllByRole("listitem");
    expect(within(itens[0]).getByText("Versão 2")).toBeTruthy();
    expect(within(itens[0]).getByText(/sha256:1111222233334444/)).toBeTruthy();
  });

  it("mostra o NOME de quem congelou quando o servidor o traz (docs/23 Fatia 5)", () => {
    mockRetorno([versao({ geradaPorNome: "Marina Alencar Freire" })]);
    render(<PaginaFolha />);
    expect(screen.getByText("Congelada por Marina Alencar Freire")).toBeTruthy();
    expect(screen.queryByText(/8b6f1c2a/)).toBeNull();
  });

  it("sem o nome (fora da Casa ou leitura indisponível), cai para o id curto rotulado", () => {
    mockRetorno([versao()]);
    render(<PaginaFolha />);
    expect(screen.getByText("Congelada por usuário 8b6f1c2a")).toBeTruthy();
  });

  it("aciona `gerar` ao clicar em 'Gerar nova versão'", async () => {
    gerar.mockResolvedValue({ ok: true, folha: versao({ versao: 2, id: "f2" }) });
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    fireEvent.click(screen.getByRole("button", { name: /gerar nova versão/i }));
    await waitFor(() => expect(gerar).toHaveBeenCalledTimes(1));
  });

  it("erro de gerar aparece inline, com role=alert — nunca some silenciosamente", async () => {
    gerar.mockResolvedValue({ ok: false, erro: "A folha só existe para uma sessão encerrada." });
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    fireEvent.click(screen.getByRole("button", { name: /gerar nova versão/i }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/sessão encerrada/i));
  });

  it("aciona `baixarPdf` com a versão certa ao clicar em 'Baixar PDF' na linha", async () => {
    mockRetorno([versao({ id: "f1", versao: 1 }), versao({ id: "f2", versao: 2 })]);
    render(<PaginaFolha />);
    const itens = screen.getAllByRole("listitem");
    fireEvent.click(within(itens[1]).getByRole("button", { name: /baixar pdf/i }));
    await waitFor(() => expect(baixarPdf).toHaveBeenCalledWith(1));
  });
});

describe("PaginaFolha — visualizador (D10)", () => {
  it("busca o HTML da versão selecionada via `buscarHtml` (apiFetch), nunca <iframe src>", async () => {
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    await waitFor(() => expect(buscarHtml).toHaveBeenCalledWith(1));
  });

  it("o iframe carrega sandbox=STRING VAZIA — nunca ausente, nunca com allow-scripts/allow-same-origin", async () => {
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    const frame = await screen.findByTitle(/folha de presença — versão 1/i);
    expect(frame.getAttribute("sandbox")).toBe("");
    expect(frame.getAttribute("sandbox")).not.toContain("allow-scripts");
    expect(frame.getAttribute("sandbox")).not.toContain("allow-same-origin");
  });

  it("erro do visualizador aparece com role=alert, sem quebrar a lista ao lado", async () => {
    buscarHtml.mockResolvedValue({ ok: false, erro: "Esta versão da folha não foi encontrada." });
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/não foi encontrada/i));
    expect(screen.getByText("Versão 1")).toBeTruthy();
  });
});

// ---- correção da revisão adversarial da fatia 6 (achado 1, lado da PÁGINA) ----
// O hook passou a honrar o próprio contrato (nunca rejeita), mas a página não pode DEPENDER disso: um
// `await` que rejeita deixa o `setCarregando(false)`/`setGerando(false)`/`setBaixando(null)` da linha
// seguinte pendurado, e o estado transitório vira permanente — spinner eterno, botão travado em "Gerando…",
// zero mensagem pro operador. Estes três testes injetam a rejeição pelo mock do hook (a única forma de
// simular a violação de contrato) e provam que nenhum estado transitório fica preso.
describe("PaginaFolha — nenhum estado transitório fica preso quando a promise REJEITA", () => {
  it("visualizador: rejeição não deixa 'Carregando o documento congelado…' para sempre", async () => {
    buscarHtml.mockRejectedValue(new TypeError("Failed to fetch"));
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    await waitFor(() => expect(screen.queryByText(/carregando o documento congelado/i)).toBeNull());
    expect(screen.getByRole("alert").textContent).toBeTruthy();
  });

  it("gerar: rejeição devolve o botão ao rótulo normal e mostra erro — nunca trava em 'Gerando…'", async () => {
    gerar.mockRejectedValue(new TypeError("Failed to fetch"));
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    fireEvent.click(screen.getByRole("button", { name: /gerar nova versão/i }));
    await waitFor(() => expect(screen.queryByRole("button", { name: /gerando…/i })).toBeNull());
    expect(screen.getByRole("button", { name: /gerar nova versão/i })).toBeTruthy();
    await waitFor(() => expect(screen.getAllByRole("alert").length).toBeGreaterThan(0));
  });

  it("baixar: rejeição devolve o botão ao rótulo normal e mostra erro — nunca trava em 'Baixando…'", async () => {
    baixarPdf.mockRejectedValue(new TypeError("Failed to fetch"));
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    const itens = screen.getAllByRole("listitem");
    fireEvent.click(within(itens[0]).getByRole("button", { name: /baixar pdf/i }));
    await waitFor(() => expect(screen.queryByRole("button", { name: /baixando…/i })).toBeNull());
    await waitFor(() => expect(screen.getAllByRole("alert").length).toBeGreaterThan(0));
  });
});

// ---- correção da revisão adversarial da fatia 6 (achado 3) ----
// As duas telas-irmãs de sessão têm `<h1>` no corpo útil (`chamada/page.tsx` "A chamada",
// `plenario/page.tsx` `#materia-titulo`). Aqui o `<h1>` só existia nos estados transitórios de
// carregando/erro: assim que a lista carregava, a árvore de headings começava em `<h2>`, sem título de
// página — leitor de tela navegando por headings entra sem âncora.
describe("PaginaFolha — hierarquia de headings", () => {
  it("estado normal (com versões) tem <h1> — não começa em h2 como a versão anterior", () => {
    mockRetorno([versao({ id: "f1", versao: 1 })]);
    render(<PaginaFolha />);
    expect(screen.getByRole("heading", { level: 1 })).toBeTruthy();
  });

  it("estado normal VAZIO (sem versão nenhuma) também tem <h1>", () => {
    mockRetorno([]);
    render(<PaginaFolha />);
    expect(screen.getByRole("heading", { level: 1 })).toBeTruthy();
  });
});
