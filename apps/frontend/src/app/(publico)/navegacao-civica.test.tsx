import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { NavegacaoCivica } from "./navegacao-civica";

// Task 2.3 (Fatia A2.2, Portal do Cidadão) — porte de portal-cidadao.html:623-662. Nenhum dos 6 destinos
// tem rota pública ainda -> todos em-breve honesto; sem contagens/prazos fabricados por instância.

describe("NavegacaoCivica", () => {
  afterEach(() => cleanup());

  it("mostra o título da seção", () => {
    render(<NavegacaoCivica ente="fortaleza" />);
    expect(screen.getByRole("heading", { name: "Tudo o que a Câmara publica" })).toBeTruthy();
  });

  it("mostra os 7 cartões; Ouvidoria e Atas já são link, os outros 5 seguem em-breve honesto", () => {
    render(<NavegacaoCivica ente="fortaleza" />);
    const titulos = [
      "Sessões", "Atas das sessões", "Transparência", "Ouvidoria", "Dados abertos", "Agenda pública", "Carta de Serviços",
    ];
    for (const titulo of titulos) {
      expect(screen.getByText(titulo).textContent).toBe(titulo);
    }
    expect(screen.getAllByRole("status")).toHaveLength(5);
    expect(screen.getByRole("link", { name: /ouvidoria/i }).getAttribute("href")).toBe("/portal/casa/fortaleza/ouvidoria");
    expect(screen.getByRole("link", { name: /atas das sessões/i }).getAttribute("href")).toBe("/portal/casa/fortaleza/atas");
  });

  it("não fabrica uma agenda de sessão específica (sem 'Próxima: ...' inventado)", () => {
    render(<NavegacaoCivica ente="fortaleza" />);
    expect(screen.queryByText(/16ª ordinária/i)).toBeNull();
  });

  it("o prazo da Ouvidoria é a constante legal (Lei 13.460), não um dado por instância", () => {
    render(<NavegacaoCivica ente="fortaleza" />);
    expect(screen.getByText(/até 30 dias.*lei 13\.460/i)).toBeTruthy();
  });
});
