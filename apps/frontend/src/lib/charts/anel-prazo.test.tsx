import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { AnelPrazo, arcoDashoffset } from "./anel-prazo";

describe("AnelPrazo — o número do centro diz a verdade", () => {
  afterEach(() => cleanup());

  it("no prazo: o número é o que falta, com 'dias'", () => {
    const { container } = render(<AnelPrazo diasRestantes={9} diasTotal={30} rotulo="Prazo X" />);
    expect(container.querySelector(".d")?.textContent).toBe("9");
    expect(container.querySelector(".u")?.textContent).toBe("dias");
    expect(screen.getByRole("img").getAttribute("aria-label")).toBe("Prazo X: faltam 9 dias.");
  });

  it("falta um dia: singular no centro e na descrição", () => {
    const { container } = render(<AnelPrazo diasRestantes={1} diasTotal={30} rotulo="Prazo X" />);
    expect(container.querySelector(".u")?.textContent).toBe("dia");
    expect(screen.getByRole("img").getAttribute("aria-label")).toBe("Prazo X: falta 1 dia.");
  });

  it("JÁ VENCIDO: o centro mostra os dias de ATRASO, não '0 dias'", () => {
    const { container } = render(<AnelPrazo diasRestantes={0} diasTotal={30} rotulo="Prazo X" atrasoDias={36} />);
    expect(container.querySelector(".d")?.textContent).toBe("36");
    expect(container.querySelector(".u")?.textContent).toBe("atraso");
    expect(screen.getByRole("img").getAttribute("aria-label")).toBe("Prazo X: venceu há 36 dias.");
    expect(container.textContent).not.toMatch(/0\s*dias/);
  });

  it("vencido há um dia: singular", () => {
    render(<AnelPrazo diasRestantes={0} diasTotal={30} rotulo="Prazo X" atrasoDias={1} />);
    expect(screen.getByRole("img").getAttribute("aria-label")).toBe("Prazo X: venceu há 1 dia.");
  });
});

describe("arcoDashoffset", () => {
  it("0 dias restantes de um total -> offset = 0 (arco cheio)", () => {
    expect(arcoDashoffset(0, 14, 40)).toBeCloseTo(0, 1);
  });
  it("todo o prazo restante -> offset = circunferencia (arco vazio)", () => {
    const circ = 2 * Math.PI * 40;
    expect(arcoDashoffset(14, 14, 40)).toBeCloseTo(circ, 1);
  });
  it("metade do prazo -> offset = metade da circunferencia", () => {
    const circ = 2 * Math.PI * 40;
    expect(arcoDashoffset(7, 14, 40)).toBeCloseTo(circ / 2, 1);
  });
  it("diasTotal 0 -> nao divide por zero (offset 0, trata como vencido/cheio)", () => {
    expect(arcoDashoffset(0, 0, 40)).toBe(0);
  });
});
