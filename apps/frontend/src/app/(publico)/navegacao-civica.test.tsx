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

  it("mostra os 9 cartões; Ouvidoria, Pautas, Atas, Audiências e Dados abertos já são link, os outros 4 seguem em-breve honesto", () => {
    render(<NavegacaoCivica ente="fortaleza" />);
    const titulos = [
      "Sessões", "Pautas das sessões", "Atas das sessões", "Audiências públicas", "Transparência", "Ouvidoria", "Dados abertos",
      "Agenda pública", "Carta de Serviços",
    ];
    for (const titulo of titulos) {
      expect(screen.getByText(titulo).textContent).toBe(titulo);
    }
    expect(screen.getAllByRole("status")).toHaveLength(4);
    expect(screen.getByRole("link", { name: /ouvidoria/i }).getAttribute("href")).toBe("/portal/casa/fortaleza/ouvidoria");
    expect(screen.getByRole("link", { name: /atas das sessões/i }).getAttribute("href")).toBe("/portal/casa/fortaleza/atas");
    expect(screen.getByRole("link", { name: /pautas das sessões/i }).getAttribute("href")).toBe("/portal/casa/fortaleza/pautas");
    expect(screen.getByRole("link", { name: /audiências públicas/i }).getAttribute("href")).toBe("/portal/casa/fortaleza/audiencias");
    expect(screen.getByRole("link", { name: /dados abertos/i }).getAttribute("href")).toBe(
      "/portal/casa/fortaleza/dados-abertos",
    );
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
