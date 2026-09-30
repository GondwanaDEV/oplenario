import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "n1" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/conferencias",
  useSearchParams: () => new URLSearchParams(),
}));

import PaginaConferencias from "./page";
import PaginaNota from "./[id]/page";

// modo `test`: usePapeis lê os papéis DO TOKEN (síncrono)
const SECRETARIA = '{"sub":"u","papeis":["secretario"]}';
const SECRETARIA_ADMIN = '{"sub":"u","papeis":["secretario","admin_ente"]}';

const agente = (ligado: boolean) => ({
  itens: [{ agente: "conferencia-normativa", titulo: "Conferência das proposições contra a LOM e o Regimento",
    descricao: "A cada proposição protocolada, a IA lê o texto…", classes: ["leitura", "rascunho"], ligado,
    "ligado-em": ligado ? "2026-09-27T12:00:00Z" : null }],
});

const resumo = { id: "n1", "proposicao-id": "p1", tipo: "requerimento", sequencial: 5, ano: 2026,
  ementa: "Informações sobre a reforma da praça do Centro", estado: "pendente", incerteza: "revisar_com_atencao",
  "criada-em": "2026-09-27T14:03:00Z", "decidida-em": null };

const nota = (estado = "pendente", textoFinal: string | null = null) => ({
  ...resumo, estado, agente: "conferencia-normativa",
  texto: "A proposição trata da praça. [[materia:p1 | Informações sobre a reforma]]\n\nNão há dispositivo sobre prazo.",
  "texto-limpo": "A proposição trata da praça.\n\nNão há dispositivo sobre prazo.",
  citacoes: [{ "fonte-id": "materia:p1", trecho: "Informações sobre a reforma", status: "conferida", rotulo: "Requerimento nº 5/2026" }],
  "paragrafos-sem-fonte": [1], "motivos-incerteza": ["sem_fonte"], "modelo-llm-id": "fake:x",
  "texto-final": textoFinal, "decidida-em": estado === "pendente" ? null : "2026-09-27T15:00:00Z",
});

type Rota = { status?: number; corpo: unknown };

function mockar(rotas: Record<string, Rota | Rota[]>) {
  const f = vi.fn(async (url: string, init?: RequestInit) => {
    const chave = `${init?.method ?? "GET"} ${url.split("?")[0]}`;
    const r = rotas[chave];
    const atual = Array.isArray(r) ? (r.length > 1 ? r.shift()! : r[0]) : r;
    if (!atual) return { ok: false, status: 404, json: async () => ({}) } as Response;
    const status = atual.status ?? 200;
    return { ok: status < 300, status, json: async () => atual.corpo } as Response;
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

function montar(el: React.ReactNode, token: string) {
  return render(
    <AuthProvider tokenQuery={token}>
      <TemaProvider>{el}</TemaProvider>
    </AuthProvider>,
  );
}

describe("conferências", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("a fila mostra a matéria, o aviso de atenção e o agente ligado; a secretaria não liga nem desliga", async () => {
    mockar({
      "GET /api/legislativo/notas-tecnicas": { corpo: { itens: [resumo] } },
      "GET /api/identidade/agentes-institucionais": { corpo: agente(true) },
      "GET /api/meu/identidade": { corpo: { nome: "Rita", papeis: ["secretario"] } },
    });
    montar(<PaginaConferencias />, SECRETARIA);
    expect(await screen.findByText("Informações sobre a reforma da praça do Centro")).toBeTruthy();
    expect(screen.getByText("REQ 5/2026")).toBeTruthy();
    expect(screen.getByText("Ler com atenção")).toBeTruthy();
    expect(screen.getByRole("link", { name: /REQ 5\/2026/ }).getAttribute("href")).toContain("/conferencias/n1");
    expect(await screen.findByText(/Ligada desde 27\/09\/2026/)).toBeTruthy();
    expect(screen.getByText("Quem liga e desliga é o administrador da Casa.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Desligar|Ligar/ })).toBeNull();
  });

  it("Casa com jurídico ativo: a fila avisa que a nota também está na fila dele; sem jurídico, nada muda", async () => {
    mockar({
      "GET /api/legislativo/notas-tecnicas": { corpo: { itens: [resumo], "casa-com-juridico": true } },
      "GET /api/identidade/agentes-institucionais": { corpo: agente(true) },
      "GET /api/meu/identidade": { corpo: { nome: "Rita", papeis: ["secretario"] } },
    });
    montar(<PaginaConferencias />, SECRETARIA);
    expect((await screen.findByText(/Esta Casa tem jurídico ativo/)).textContent).toMatch(/Quem a usar primeiro tira a nota das duas filas/);
    // a secretaria segue vendo a nota e decidindo como sempre
    expect(screen.getByText("Informações sobre a reforma da praça do Centro")).toBeTruthy();
    cleanup();
    mockar({
      "GET /api/legislativo/notas-tecnicas": { corpo: { itens: [resumo], "casa-com-juridico": false } },
      "GET /api/identidade/agentes-institucionais": { corpo: agente(true) },
      "GET /api/meu/identidade": { corpo: { nome: "Rita", papeis: ["secretario"] } },
    });
    montar(<PaginaConferencias />, SECRETARIA);
    await screen.findByText("Informações sobre a reforma da praça do Centro");
    expect(screen.queryByText(/Esta Casa tem jurídico ativo/)).toBeNull();
  });

  it("o administrador liga a conferência; a fila vazia explica que está desligada", async () => {
    const f = mockar({
      "GET /api/legislativo/notas-tecnicas": { corpo: { itens: [] } },
      "GET /api/identidade/agentes-institucionais": { corpo: agente(false) },
      "PUT /api/identidade/agentes-institucionais/conferencia-normativa/concessao": { corpo: agente(true) },
      "GET /api/meu/identidade": { corpo: { nome: "Rita", papeis: ["secretario", "admin_ente"] } },
    });
    montar(<PaginaConferencias />, SECRETARIA_ADMIN);
    expect(await screen.findByText(/A conferência automática está desligada/)).toBeTruthy();
    fireEvent.click(await screen.findByRole("button", { name: "Ligar a conferência" }));
    expect(await screen.findByRole("button", { name: "Desligar" })).toBeTruthy();
    expect(f.mock.calls.some(([u, i]) => String(u).endsWith("/concessao") && (i as RequestInit)?.method === "PUT")).toBe(true);
  });

  it("a nota: selo de IA, aviso, citação numerada, parágrafo sem fonte — e aproveitar editando", async () => {
    const f = mockar({
      "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() },
      "POST /api/legislativo/notas-tecnicas/n1/decisao": { corpo: nota("aproveitada", "Texto da secretaria.") },
      "GET /api/meu/identidade": { corpo: { nome: "Rita", papeis: ["secretario"] } },
    });
    montar(<PaginaNota />, SECRETARIA);
    expect(await screen.findByText(/Rascunho produzido por IA/)).toBeTruthy();
    expect(screen.getByText(/Leia com atenção: há parágrafos que nenhum dispositivo lido sustenta/)).toBeTruthy();
    expect(screen.getByText("sem fonte — confira")).toBeTruthy();
    expect(screen.getByText("1").getAttribute("title")).toBe("Requerimento nº 5/2026");
    expect(screen.getByRole("link", { name: "Abrir a ficha da matéria" }).getAttribute("href")).toContain("/ficha-materia/p1");
    fireEvent.click(screen.getByRole("button", { name: "Aproveitar a nota" }));
    const campo = screen.getByLabelText("Texto da nota técnica") as HTMLTextAreaElement;
    expect(campo.value).toBe("A proposição trata da praça.\n\nNão há dispositivo sobre prazo.");
    fireEvent.change(campo, { target: { value: "Texto da secretaria." } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar a nota" }));
    expect(await screen.findByText("Texto da secretaria.")).toBeTruthy();
    expect(screen.getByText(/Aproveitada em 27\/09\/2026/)).toBeTruthy();
    const [, init] = f.mock.calls.find(([u]) => String(u).endsWith("/decisao")) as unknown as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ desfecho: "aproveitada", texto: "Texto da secretaria." });
  });

  it("aproveitar sem editar não manda texto (vale o da IA, limpo); descartar pede confirmação", async () => {
    const f = mockar({
      "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() },
      "POST /api/legislativo/notas-tecnicas/n1/decisao": { corpo: nota("descartada") },
      "GET /api/meu/identidade": { corpo: { nome: "Rita", papeis: ["secretario"] } },
    });
    montar(<PaginaNota />, SECRETARIA);
    fireEvent.click(await screen.findByRole("button", { name: "Descartar" }));
    expect(screen.getByText(/Descartar esta nota\?/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Descartar a nota" }));
    expect(await screen.findByText(/A secretaria descartou esta nota/)).toBeTruthy();
    const [, init] = f.mock.calls.find(([u]) => String(u).endsWith("/decisao")) as unknown as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ desfecho: "descartada" });
  });

  it("decisão em conflito aparece com a mensagem do sistema", async () => {
    mockar({
      "GET /api/legislativo/notas-tecnicas/n1": { corpo: nota() },
      "POST /api/legislativo/notas-tecnicas/n1/decisao": { status: 409, corpo: { erro: "Esta nota já foi decidida." } },
      "GET /api/meu/identidade": { corpo: { nome: "Rita", papeis: ["secretario"] } },
    });
    montar(<PaginaNota />, SECRETARIA);
    fireEvent.click(await screen.findByRole("button", { name: "Aproveitar a nota" }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar a nota" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toBe("Esta nota já foi decidida."));
  });
});
