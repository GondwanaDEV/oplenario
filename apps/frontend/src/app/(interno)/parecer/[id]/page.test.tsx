import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { ParecerEditorOut } from "@/lib/contrato-legislativo.gen";

// A dica da Clara no editor de parecer de comissão: o parecer é sobre a matéria, e é a matéria que a Clara consulta.
// Os hooks de IO são substituídos: aqui só interessa o que a página publica com o que já carregou.

const editor = vi.hoisted(() => ({
  atual: { dados: null as unknown, estado: "carregando" as "carregando" | "pronto" | "erro" },
}));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tk" }) }));
vi.mock("@/lib/use-parecer-editor", () => ({
  useParecerEditor: () => ({ ...editor.atual, recarregar: async () => {} }),
}));
vi.mock("@/lib/use-salvar-rascunho-parecer", () => ({
  useSalvarRascunhoParecer: () => ({ salvar: async () => {}, estado: "ocioso", erro: null }),
}));
vi.mock("@/lib/use-emitir-parecer", () => ({
  useEmitirParecer: () => ({ emitir: async () => {}, estado: "ocioso", erro: null }),
}));
vi.mock("../../topo", () => ({ TopoInterno: () => null }));
vi.mock("../formulario-parecer", () => ({ FormularioParecer: () => <div>formulário</div> }));
vi.mock("../rail-parecer", () => ({ RailParecer: () => null }));
vi.mock("@/app/copiloto-analise", () => ({ CopilotoAnalise: () => null }));

import PaginaParecer from "./page";
import { ProvedorDaDica, useDicaAtual } from "../../clara/dica";

const paramsResolvido = Object.assign(Promise.resolve({ id: "par1" }), {
  status: "fulfilled" as const,
  value: { id: "par1" },
});

const parecer = (extra: Partial<ParecerEditorOut> = {}) =>
  ({
    id: "par1", objetoTipo: "proposicao", objetoId: "p1", comissaoId: "c1", comissaoNome: "Comissão de Justiça",
    relatorId: "v1", estado: "em_elaboracao", lockVersion: 1,
    objeto: { id: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42, urnLex: "urn:x", ementa: "Institui hortas" },
    relatorio: null, analise: null, votoRelator: null, textoEstado: "vazio",
    ...extra,
  }) as unknown as ParecerEditorOut;

function Sonda() {
  const dica = useDicaAtual();
  return <output data-testid="dica">{dica ? `${dica.rotulo} | ${dica.inicio} | ${dica.acao}` : "sem dica"}</output>;
}

function naMoldura() {
  return render(
    <ProvedorDaDica>
      <PaginaParecer params={paramsResolvido} />
      <Sonda />
    </ProvedorDaDica>,
  );
}

afterEach(() => {
  cleanup();
  editor.atual = { dados: null, estado: "carregando" };
});

describe("PaginaParecer — a dica da Clara", () => {
  it("enquanto carrega, nada", () => {
    naMoldura();
    expect(screen.getByText("Carregando…")).toBeTruthy();
    expect(screen.getByTestId("dica").textContent).toBe("sem dica");
  });

  it("com o parecer carregado, a dica é a matéria dele ('PL 42/2026')", () => {
    editor.atual = { dados: parecer(), estado: "pronto" };
    naMoldura();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Parecer a PL 42/2026");
    expect(screen.getByTestId("dica").textContent).toBe("PL 42/2026 | Sobre o PL 42/2026,  | Perguntar sobre esta matéria");
  });

  it("sem objeto casado (o servidor não achou a matéria), sem dica: nunca inventa número", () => {
    editor.atual = { dados: parecer({ objeto: null }), estado: "pronto" };
    naMoldura();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Parecer de comissão");
    expect(screen.getByTestId("dica").textContent).toBe("sem dica");
  });
});
