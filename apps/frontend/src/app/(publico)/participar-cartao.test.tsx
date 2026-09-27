import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ParticiparCartao } from "./participar-cartao";

const ENTE = "10000000-0000-0000-0000-000000000001";
const HREF = `/api/auth/login?ente=${ENTE}&via=govbr`;

describe("ParticiparCartao — 'Entrar para participar' (ADR-0015)", () => {
  afterEach(() => cleanup());

  it("com o gov.br ligado, o botão é uma navegação de topo ao BFF pedindo o gov.br", () => {
    render(<ParticiparCartao vista={{ estado: "pronto", nome: "Câmara Municipal de Baturité" }} ente={ENTE} hrefEntrar={HREF} />);
    const botao = screen.getByRole("link", { name: /entrar com gov\.br/i });
    expect(botao.getAttribute("href")).toBe(HREF);
    expect(screen.getByText(/Câmara Municipal de Baturité/)).toBeTruthy();
  });

  it("sem o broker, não há botão — a tela diz que ainda não está disponível", () => {
    render(<ParticiparCartao vista={{ estado: "indisponivel", nome: null }} ente={ENTE} hrefEntrar={HREF} />);
    expect(screen.queryByRole("link", { name: /entrar com gov\.br/i })).toBeNull();
    expect(screen.getByRole("status").textContent).toMatch(/ainda não está disponível/);
  });

  it("promete o que o broker faz: só nome e CPF; e consultar continua livre", () => {
    render(<ParticiparCartao vista={{ estado: "pronto", nome: null }} ente={ENTE} hrefEntrar={HREF} />);
    expect(screen.getByText(/seu nome e CPF verificado/)).toBeTruthy();
    expect(screen.getByRole("link", { name: /voltar e só consultar/i }).getAttribute("href")).toBe(`/portal/casa/${ENTE}`);
  });
});
