import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { BotaoModoTv } from "./botao-modo-tv";

describe("BotaoModoTv", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("abre a TV da sessão numa JANELA separada, sem referência de volta (noopener)", () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    render(<BotaoModoTv sessaoId="s1" token={null} />);
    fireEvent.click(screen.getByRole("button", { name: /Modo TV/ }));
    expect(open).toHaveBeenCalledWith("/sessoes/s1/tv", "oplenario-tv", expect.stringContaining("noopener"));
    expect(open.mock.calls[0][2]).toMatch(/width=\d+,height=\d+/);
  });

  it("em dev repassa o token de bypass", () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    render(<BotaoModoTv sessaoId="s1" token='{"sub":"u"}' />);
    fireEvent.click(screen.getByRole("button", { name: /Modo TV/ }));
    expect(open.mock.calls[0][0]).toBe(`/sessoes/s1/tv?token=${encodeURIComponent('{"sub":"u"}')}`);
  });
});
