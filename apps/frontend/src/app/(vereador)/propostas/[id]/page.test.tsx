import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import PaginaProposta from "./page";
import type { PropostaOut } from "@/lib/propostas-vista";

vi.mock("next/navigation", () => ({ useParams: () => ({ id: "p-1" }), useRouter: () => ({ back: vi.fn() }) }));
vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const confirmar = vi.fn();
const recusar = vi.fn();
const useProposta = vi.fn();
vi.mock("@/lib/use-propostas", () => ({ useProposta: (...a: unknown[]) => useProposta(...a) }));

const base: PropostaOut = {
  id: "p-1",
  ferramenta: "protocolar_requerimento",
  agente: "assistente-da-casa",
  titulo: "Protocolar o requerimento “Obra da praça”",
  texto: "Ana Prado requer a Secretaria de Obras informações sobre a obra da praça.",
  ritual: "assinatura",
  estado: "aguardando",
  contaminadaPor: [],
  criadaEm: "2026-09-27T15:00:00Z",
  expiraEm: "2026-09-30T15:00:00Z",
  decididaEm: null,
  resultado: null,
  erro: null,
  apresentacaoAtual: { titulo: "x", texto: "Ana Prado requer a Secretaria de Obras informações sobre a obra da praça." },
};

function montar(p: Partial<PropostaOut> = {}, extra: Record<string, unknown> = {}) {
  useProposta.mockReturnValue({
    estado: { fase: "pronto", dado: { ...base, ...p } },
    enviando: false,
    erro: null,
    confirmar,
    recusar,
    ...extra,
  });
  return render(<PaginaProposta />);
}

afterEach(() => {
  cleanup();
  confirmar.mockReset();
  recusar.mockReset();
});

describe("a proposta do assistente", () => {
  it("diz que nada foi feito, mostra o texto e confirma em 2 toques", async () => {
    confirmar.mockResolvedValue(true);
    montar();
    expect(screen.getByText("Nada foi feito ainda")).toBeTruthy();
    expect(screen.getByText(/Ana Prado requer a Secretaria de Obras/)).toBeTruthy();
    expect(screen.getByText("Esperando você")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Revisar e assinar" }));
    expect(confirmar).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar e protocolar" }));
    expect(confirmar).toHaveBeenCalledTimes(1);
  });

  it("recusar não abre a folha", () => {
    montar();
    fireEvent.click(screen.getByRole("button", { name: "Recusar" }));
    expect(recusar).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("avisa quando nasceu depois de ler conteúdo de fora da Casa", () => {
    montar({ contaminadaPor: [{ ferramenta: "pedido_esic", origem: "e-SIC", referencia: "nº 12/2026" }] });
    expect(screen.getByRole("note").textContent).toMatch(/e-SIC nº 12\/2026/);
  });

  it("decidida: mostra o recibo e não oferece confirmar", () => {
    montar({ estado: "confirmada", resultado: { ano: 2026, sequencial: 7 } });
    expect(screen.getByRole("status").textContent).toBe("Requerimento nº 7/2026 protocolado e assinado.");
    expect(screen.queryByRole("button", { name: "Revisar e assinar" })).toBeNull();
    expect(screen.getByText("Texto assinado")).toBeTruthy();
  });
});
