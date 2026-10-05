import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { SaudeInstitucional } from "./saude-institucional";
import type { MesaVista } from "@/lib/mesa-vista";

// As remessas ao TCE já vinham no card de compliance (`remessasRecentes`) e nenhuma tela as mostrava: a Mesa
// via "em dia com o TCE" sem saber se a remessa do mês foi gerada, enviada ou rejeitada.

const resumo = { pendente: 1, cumprida: 4, vencida: 0, dispensada: 0, cancelada: 0 };

function remessa(over: Partial<NonNullable<MesaVista["saude"]["remessas"]>["itens"][number]> = {}) {
  return {
    id: "r1", templateChave: "remessa_mensal_sim", sistema: "SIM", competencia: "2026-08", versao: 1,
    estado: "aceita", submetidaEm: "2026-09-02T15:00:00Z", respostaEm: "2026-09-03T15:00:00Z",
    criadoEm: "2026-09-01T15:00:00Z", ...over,
  };
}

function vista(remessas: NonNullable<MesaVista["saude"]["remessas"]>): MesaVista["saude"] {
  return { estado: "disponivel", resumo, emAberto: [], truncamento: null, remessas };
}

describe("SaudeInstitucional — remessas ao TCE", () => {
  afterEach(() => cleanup());

  it("mostra cada remessa em palavras: nome, competência, versão e situação", () => {
    render(
      <SaudeInstitucional
        vista={vista({
          itens: [remessa({ id: "r2", estado: "submetida", versao: 2 }), remessa({ id: "r1", estado: "aceita" })],
          total: 2, truncado: false, rejeitadas: 0,
        })}
      />,
    );
    expect(screen.getByRole("heading", { name: "Remessas ao TCE" })).toBeDefined();
    expect(screen.getByText(/Enviada, aguardando o TCE/)).toBeDefined();
    expect(screen.getByText(/Aceita pelo TCE/)).toBeDefined();
    expect(screen.getAllByText(/Remessa mensal ao SIM \(TCE-CE\)/).length).toBe(2);
    expect(screen.getByText(/versão 2/)).toBeDefined();
    // enum cru nunca na tela
    expect(document.body.textContent).not.toMatch(/\bsubmetida\b|\brascunho\b/);
  });

  it("rejeitada chama atenção em texto (não só por cor)", () => {
    render(
      <SaudeInstitucional
        vista={vista({ itens: [remessa({ estado: "rejeitada" })], total: 1, truncado: false, rejeitadas: 1 })}
      />,
    );
    expect(screen.getByText(/Rejeitada pelo TCE/)).toBeDefined();
    expect(screen.getByText(/precisa de nova versão/i)).toBeDefined();
  });

  it("nenhuma remessa -> diz que ainda não há, em vez de calar", () => {
    render(<SaudeInstitucional vista={vista({ itens: [], total: 0, truncado: false, rejeitadas: 0 })} />);
    expect(screen.getByText("Nenhuma remessa gerada ainda.")).toBeDefined();
  });

  it("lista cortada: o total vem do servidor, não da lista", () => {
    render(
      <SaudeInstitucional
        vista={vista({ itens: [remessa()], total: 14, truncado: true, rejeitadas: 0 })}
      />,
    );
    const aviso = screen.getByRole("status");
    expect(aviso.textContent).toContain("1");
    expect(aviso.textContent).toContain("14");
  });

  it("compliance indisponível -> sem bloco de remessas", () => {
    render(<SaudeInstitucional vista={{ estado: "indisponivel", resumo: undefined, emAberto: undefined, truncamento: null, remessas: undefined }} />);
    expect(screen.queryByRole("heading", { name: "Remessas ao TCE" })).toBeNull();
  });
});
