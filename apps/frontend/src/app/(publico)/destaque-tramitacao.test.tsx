import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { DestaqueTramitacao } from "./destaque-tramitacao";
import type { MateriaVista } from "@/lib/materia-vista";

// Task 1.3 (Fatia A2.1, Portal do Cidadão) — porte de portal-cidadao.html:422-490 (o card de destaque).
// Só o estado `resumo-off` HONESTO do resumo por IA é portado (sem backend de IA nesta fatia — Global
// Constraints "sem dado falso"); o corpo com resumo fabricado da tela-fonte NÃO entra.

const destaque: MateriaVista = {
  ref: "PL 042/2026",
  titulo: "Cria o Programa Municipal de Hortas Comunitárias.",
  situacao: "Em 2º turno",
  permalink: "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
  proposicaoId: "abc-123",
  autorTexto: "Ver.ª Helena Matos",
  estagios: [
    { rotulo: "Protocolo", situacao: "concluido" },
    { rotulo: "Comissões", situacao: "concluido" },
    { rotulo: "1º turno", situacao: "concluido" },
    { rotulo: "2º turno", situacao: "ativo" },
    { rotulo: "Sanção", situacao: "pendente" },
  ],
};

describe("DestaqueTramitacao", () => {
  afterEach(() => cleanup());

  it("mostra a referência, o título (ementa) e a situação", () => {
    render(<DestaqueTramitacao destaque={destaque} ente="fortaleza" />);
    expect(screen.getByText("PL 042/2026").textContent).toBe("PL 042/2026");
    expect(screen.getByText(destaque.titulo).textContent).toBe(destaque.titulo);
    expect(screen.getByText("Em 2º turno").textContent).toBe("Em 2º turno");
  });

  it("mostra a autoria quando informada", () => {
    render(<DestaqueTramitacao destaque={destaque} ente="fortaleza" />);
    expect(screen.getByText("Ver.ª Helena Matos").textContent).toBe("Ver.ª Helena Matos");
  });

  it("autoria ausente -> texto honesto, não inventa um nome", () => {
    render(<DestaqueTramitacao destaque={{ ...destaque, autorTexto: null }} ente="fortaleza" />);
    expect(screen.getByText(/autoria não informada/i)).toBeTruthy();
  });

  it("renderiza a AzulejoFaixa da tramitação (role=img)", () => {
    render(<DestaqueTramitacao destaque={destaque} ente="fortaleza" />);
    expect(screen.getByRole("img")).toBeTruthy();
  });

  it("mostra o permalink (URN)", () => {
    render(<DestaqueTramitacao destaque={destaque} ente="fortaleza" />);
    expect(screen.getByText(destaque.permalink).textContent).toBe(destaque.permalink);
  });

  it("resumo por IA: só o estado 'off' honesto (sem resumo fabricado)", () => {
    render(<DestaqueTramitacao destaque={destaque} ente="fortaleza" />);
    expect(screen.getByText(/resumo em linguagem simples está indisponível/i)).toBeTruthy();
  });

  it("o título linka para a ficha da matéria (/portal/casa/{ente}/materias/{proposicaoId})", () => {
    render(<DestaqueTramitacao destaque={destaque} ente="fortaleza" />);
    const link = screen.getByRole("link", { name: destaque.titulo });
    expect(link.getAttribute("href")).toBe("/portal/casa/fortaleza/materias/abc-123");
  });
});
