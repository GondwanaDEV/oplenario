import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { NavegacaoCivica } from "./navegacao-civica";

// Task 2.3 (Fatia A2.2, Portal do Cidadão) — porte de portal-cidadao.html:623-662. Nenhum dos 6 destinos
// tem rota pública ainda -> todos em-breve honesto; sem contagens/prazos fabricados por instância.

describe("NavegacaoCivica", () => {
  afterEach(() => cleanup());

  it("mostra o título da seção", () => {
    render(<NavegacaoCivica />);
    expect(screen.getByRole("heading", { name: "Tudo o que a Câmara publica" })).toBeTruthy();
  });

  it("mostra os 6 cartões, todos em-breve honesto (role=status)", () => {
    render(<NavegacaoCivica />);
    const titulos = ["Sessões", "Transparência", "Ouvidoria", "Dados abertos", "Agenda pública", "Carta de Serviços"];
    for (const titulo of titulos) {
      expect(screen.getByText(titulo).textContent).toBe(titulo);
    }
    expect(screen.getAllByRole("status")).toHaveLength(6);
  });

  it("não fabrica uma agenda de sessão específica (sem 'Próxima: ...' inventado)", () => {
    render(<NavegacaoCivica />);
    expect(screen.queryByText(/16ª ordinária/i)).toBeNull();
  });

  it("o prazo da Ouvidoria é a constante legal (Lei 13.460), não um dado por instância", () => {
    render(<NavegacaoCivica />);
    expect(screen.getByText(/até 30 dias.*lei 13\.460/i)).toBeTruthy();
  });
});
