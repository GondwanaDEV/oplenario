import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaAssinarParecer from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Regressão do guard de voto (achado da revisão final de branco, Onda C4): `deriveEstadoAssinatura`
// (assinatura-vista.test.ts) já prova, no nível de view-model puro, que `votoRelator: null` produz
// "sem-voto" e não "pronto-pra-revisar". O que falta é uma prova NO NÍVEL DA PÁGINA de que o CTA "Revisar
// e assinar" — a única porta pra abrir a sheet de confirmação e chamar `confirmar()` — nunca chega a
// renderizar nesse caso. Sem isto, um refactor futuro que trocasse a condição do CTA por outra (sem passar
// por `deriveEstadoAssinatura`) não seria pego por nenhum teste.
//
// Esta é a PRIMEIRA página com rota dinâmica ([id]) a ganhar teste próprio neste repo — nenhum outro
// page.test.tsx mockava `next/navigation` ainda (todos usam rotas sem segmento dinâmico). O resto do
// padrão é o MESMO dos demais page.test.tsx (vereador/page.test.tsx, tramitacao/page.test.tsx): mocka
// `global.fetch`, deixa os hooks reais rodarem — não mocka os próprios hooks (não há precedente de
// `vi.mock` de hook custom neste repo).
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

  it("votoRelator null -> nunca renderiza o CTA 'Revisar e assinar' (não fabrica voto)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => parecerSemVotoFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() =>
      expect(
        screen.getByText(/Ainda falta registrar a conclusão \(voto\) do relator/i)
      ).toBeTruthy()
    );
    expect(screen.queryByRole("button", { name: /revisar e assinar/i })).toBeNull();
    // a sheet de confirmação (e portanto o botão "Confirmar com a biometria" que dispara `confirmar()`)
    // também fica inalcançável — ela só monta quando `sheetAberta` é setada por esse CTA ausente.
    expect(screen.queryByRole("button", { name: /confirmar com a biometria/i })).toBeNull();
  });
});
