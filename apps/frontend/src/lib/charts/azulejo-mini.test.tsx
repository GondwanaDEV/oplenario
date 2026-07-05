import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AzulejoMini } from "./azulejo-mini";
import type { EstagioTramitacao } from "../tramitacao-vista";

// Onda B Slice 1 — variante compacta do AzulejoFaixa para a coluna "Situação" da tabela de proposições
// (produto/design-system/o-plenario/telas/proposicoes.html, .azulejo-mini). Mesma entrada
// (EstagioTramitacao[]) do AzulejoFaixa já testado — só troca o SVG por blocos pequenos sem rótulo textual
// por estágio (a acessibilidade vem do aria-label, não de <text> por bloco).

const estagios: EstagioTramitacao[] = [
  { rotulo: "Protocolo", situacao: "concluido" },
  { rotulo: "Comissões", situacao: "concluido" },
  { rotulo: "1º turno", situacao: "ativo" },
  { rotulo: "2º turno", situacao: "pendente" },
  { rotulo: "Sanção", situacao: "pendente" },
];

describe("AzulejoMini", () => {
  afterEach(() => cleanup());

  it("tem role=img com aria-label descritivo obrigatório", () => {
    render(<AzulejoMini estagios={estagios} rotuloAria="Tramitação: em 1º turno." />);
    expect(screen.getByRole("img", { name: /em 1º turno/ })).toBeTruthy();
  });

  it("renderiza um bloco por estágio", () => {
    const { container } = render(<AzulejoMini estagios={estagios} rotuloAria="Tramitação" />);
    expect(container.querySelectorAll(".azulejo-mini-bloco").length).toBe(5);
  });

  it("marca o estágio ativo e os pendentes com as classes correspondentes", () => {
    const { container } = render(<AzulejoMini estagios={estagios} rotuloAria="Tramitação" />);
    const blocos = container.querySelectorAll(".azulejo-mini-bloco");
    expect(blocos[2].getAttribute("class")).toContain("azulejo-mini-bloco--ativo");
    expect(blocos[3].getAttribute("class")).toContain("azulejo-mini-bloco--pendente");
    expect(blocos[0].getAttribute("class")).toContain("azulejo-mini-bloco--concluido");
  });
});
