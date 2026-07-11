import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { IdentidadeLexmlCard } from "./identidade-lexml-card";

describe("IdentidadeLexmlCard", () => {
  afterEach(() => cleanup());

  it("renderiza a URN LexML em fonte mono", () => {
    render(<IdentidadeLexmlCard urnLex="urn:lex:br;ceara;fortaleza:camara.municipal:projeto.lei:2026;042" />);
    expect(screen.getByText("Identidade canônica (LexML)")).toBeTruthy();
    expect(screen.getByText("urn:lex:br;ceara;fortaleza:camara.municipal:projeto.lei:2026;042")).toBeTruthy();
  });
});
