import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { TrocaDeSessao } from "./page";

afterEach(cleanup);

const nova = { sessaoId: "nova", situacao: "em_curso", abertaEm: "2026-07-01T18:00:00Z" };
const antiga = { sessaoId: "antiga", situacao: "suspensa", abertaEm: "2026-07-01T13:00:00Z" };

describe("TrocaDeSessao (cockpit com duas sessões em curso)", () => {
  it("uma sessão só: não aparece nada", () => {
    const { container } = render(<TrocaDeSessao sessoes={[nova]} atual="nova" token="tok" />);
    expect(container.innerHTML).toBe("");
  });

  it("duas sessões: diz quantas, marca a aberta e oferece a outra com o token", () => {
    render(<TrocaDeSessao sessoes={[nova, antiga]} atual="nova" token="tok" />);
    const nav = screen.getByRole("navigation", { name: "Sessões em curso" });
    expect(within(nav).getByText("Há 2 sessões em curso agora.")).toBeTruthy();
    const links = within(nav).getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute("href")).toBe("/votar?sessao=antiga&token=tok");
    expect(links[0].textContent).toMatch(/^Abrir a sessão aberta às .+, suspensa$/);
    expect(nav.querySelector('[aria-current="page"]')?.textContent).toMatch(/· esta$/);
  });
});
