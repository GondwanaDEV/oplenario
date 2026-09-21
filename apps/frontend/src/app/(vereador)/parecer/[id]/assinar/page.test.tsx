import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, fireEvent } from "@testing-library/react";
import PaginaAssinarParecer from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Guard de voto NO NÍVEL DA PÁGINA (achado docs/20 — a jornada de assinatura era circular): com
// `votoRelator: null` a tela agora está em "escolher-voto" (deriveEstadoAssinatura) — oferece ao relator
// ESCOLHER a conclusão e só então libera o CTA "Revisar e assinar". Prova que (a) sem escolha o CTA NÃO
// renderiza (não fabrica um voto) e (b) escolher um voto libera o CTA. Sem isto, um refactor que trocasse
// a condição do CTA (sem passar por `deriveEstadoAssinatura` + a escolha explícita) não seria pego.
//
// Padrão dos demais page.test.tsx: mocka `next/navigation` (rota dinâmica [id]) e `global.fetch`, deixa os
// hooks reais rodarem.
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "p1" }),
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}));

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <PaginaAssinarParecer />
      </TemaProvider>
    </AuthProvider>
  );
}

const parecerSemVotoFake = {
  id: "p1",
  "objeto-tipo": "proposicao",
  "objeto-id": "obj1",
  "comissao-id": "c1",
  "relator-id": "v1",
  "voto-relator": null,
  estado: "com_relator",
  "template-id": "t1",
  "lock-version": 0,
  "criado-em": "2026-07-01T10:00:00Z",
  objeto: { id: "obj1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x", ementa: "X" },
  relatorio: "Texto do relatório já redigido.",
  analise: "",
  "texto-estado": "rascunho",
  "texto-numero-versao": 1,
};

describe("PaginaAssinarParecer", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("votoRelator null -> oferece a escolha do voto; o CTA só aparece DEPOIS de escolher (nunca fabrica voto)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => parecerSemVotoFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    // o seletor de conclusão aparece; o CTA e a sheet ainda NÃO (nenhuma escolha feita).
    const opcao = await screen.findByLabelText("Favorável");
    expect(screen.queryByRole("button", { name: /revisar e assinar/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /confirmar com a biometria/i })).toBeNull();

    // ao escolher uma conclusão, o CTA passa a ser oferecido.
    fireEvent.click(opcao);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /revisar e assinar/i })).toBeTruthy()
    );
  });
});
