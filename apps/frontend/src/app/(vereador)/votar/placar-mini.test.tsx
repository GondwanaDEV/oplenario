// Revisão adversarial da frente "truncamento-familia": `derivarPlacar(estadoPlenario?.placar ?? null)`
// em page.tsx era chamado SEM o 2o argumento, então o aviso de lacuna do SSE — que a Mesa já mostra em
// sessoes/[id]/plenario/page.tsx — nunca chegava ao cockpit do vereador, exatamente quem aperta o botão
// de voto olhando este placar. Estes testes afirmam sobre `PlacarMini` (o pedaço extraído e testável de
// `page.tsx`) com um `VistaPlacar` de verdade, produzido pelo `derivarPlacar` real — não um mock da forma.

import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { PlacarMini } from "./page";
import { derivarPlacar } from "@/lib/placar-vista";
import type { PlacarVotacao } from "@/lib/plenario-reducer";

afterEach(() => cleanup());

function nominalAberto(over: Partial<PlacarVotacao> = {}): PlacarVotacao {
  return {
    votacaoId: "v1",
    modalidade: "nominal",
    objetoTipo: "proposicao",
    objetoId: "v1",
    proposicao: null,
    encerrada: false,
    votosNominais: { a: "sim" },
    votosSecretos: 0,
    resultado: null,
    totais: null,
    baseMembros: null,
    ...over,
  };
}

function secretaAberta(over: Partial<PlacarVotacao> = {}): PlacarVotacao {
  return {
    votacaoId: "v1",
    modalidade: "secreta",
    objetoTipo: "proposicao",
    objetoId: "v1",
    proposicao: null,
    encerrada: false,
    votosNominais: {},
    votosSecretos: 2,
    resultado: null,
    totais: null,
    baseMembros: null,
    ...over,
  };
}

describe("PlacarMini — cockpit do vereador segue o MESMO aviso de lacuna da Mesa", () => {
  it("nominal SEM lacuna: nenhum .aviso-corte", () => {
    const { container } = render(<PlacarMini placar={derivarPlacar(nominalAberto(), false)} />);
    expect(container.querySelector(".aviso-corte")).toBeNull();
  });

  it("nominal COM lacuna: o cockpit do vereador mostra o aviso (antes do conserto, ficava mudo)", () => {
    const { container, getByRole } = render(<PlacarMini placar={derivarPlacar(nominalAberto(), true)} />);
    const aviso = getByRole("status");
    expect(aviso.className).toContain("aviso-corte");
    expect(container.textContent).toContain("lacuna");
  });

  it("secreta COM lacuna: o contador anônimo também avisa", () => {
    const { getByRole } = render(<PlacarMini placar={derivarPlacar(secretaAberta(), true)} />);
    expect(getByRole("status").className).toContain("aviso-corte");
  });

  it("sem placar (kind nenhuma): não renderiza nada, mesmo com avisoLacuna=true", () => {
    const { container } = render(<PlacarMini placar={derivarPlacar(null, true)} />);
    expect(container.innerHTML).toBe("");
  });
});

// O defeito (05/10/2026): na votação ENCERRADA o cockpit ainda escrevia "faltam N" (e o rótulo de leitor de tela
// "Parcial: … ainda não votaram"). Ninguém mais vai votar: o que cabe é "não votaram N de M", o mesmo texto do
// telão (sessoes/[id]/plenario `PlacarMeta`). Aberta não muda.
describe("PlacarMini — quem não votou, em votação aberta e encerrada", () => {
  const meta = (c: HTMLElement) => c.querySelector(".placar-mini .quem-nao-votou")?.textContent;
  const nominalEncerrada = (over: Partial<PlacarVotacao> = {}) =>
    nominalAberto({
      encerrada: true, resultado: "aprovada", baseMembros: 3,
      votosNominais: { a: "sim", b: "nao" }, totais: { sim: 1, nao: 1, abstencao: 0 }, ...over,
    });
  const secretaEncerrada = (over: Partial<PlacarVotacao> = {}) =>
    secretaAberta({
      encerrada: true, resultado: "aprovada", baseMembros: 3, votosSecretos: 2,
      totais: { sim: 1, nao: 1, abstencao: 0 }, ...over,
    });

  it("nominal ABERTA: segue 'faltam N · parcial'", () => {
    const { container } = render(
      <PlacarMini placar={derivarPlacar(nominalAberto({ baseMembros: 3 }), false)} />,
    );
    expect(container.querySelector(".leg .parcial")?.textContent?.replace(/\s+/g, " ").trim()).toBe("faltam 2 · parcial");
    expect(container.querySelector("[role=img]")?.getAttribute("aria-label")).toBe("Parcial: 1 sim, 0 não, 2 ainda não votaram");
  });

  it("nominal ENCERRADA com quem não votou: 'não votaram N de M', nunca 'faltam' nem 'Parcial'", () => {
    const { container } = render(<PlacarMini placar={derivarPlacar(nominalEncerrada(), false)} />);
    expect(meta(container)).toBe("não votaram 1 de 3");
    expect(container.textContent).not.toMatch(/faltam|parcial/i);
    expect(container.querySelector("[role=img]")?.getAttribute("aria-label")).toBe("Resultado: 1 sim, 1 não; não votaram 1 de 3");
  });

  it("nominal ENCERRADA em que todos votaram: 'todos os M votaram', sem 'faltam 0'", () => {
    const { container } = render(
      <PlacarMini placar={derivarPlacar(nominalEncerrada({ totais: { sim: 2, nao: 1, abstencao: 0 } }), false)} />,
    );
    expect(meta(container)).toBe("todos os 3 votaram");
    expect(container.textContent).not.toMatch(/faltam/i);
  });

  it("nominal ENCERRADA sem base de membros: nenhuma linha de quem não votou (nunca inventada)", () => {
    const { container } = render(
      <PlacarMini placar={derivarPlacar(nominalEncerrada({ baseMembros: null }), false)} />,
    );
    expect(meta(container)).toBeUndefined();
    expect(container.textContent).not.toMatch(/faltam/i);
  });

  it("secreta ENCERRADA com quem não votou: 'não votaram N de M' como o telão, e o sigilo segue", () => {
    const { container } = render(<PlacarMini placar={derivarPlacar(secretaEncerrada(), false)} />);
    expect(container.querySelector(".voto-nota")?.textContent).toBe("Resultado: 1 sim, 1 não, 0 abstenção.");
    expect(meta(container)).toBe("não votaram 1 de 3");
    expect(container.textContent).not.toMatch(/faltam/i);
  });

  it("secreta ENCERRADA em que todos votaram: 'todos os M votaram'", () => {
    const { container } = render(
      <PlacarMini placar={derivarPlacar(secretaEncerrada({ totais: { sim: 2, nao: 1, abstencao: 0 } }), false)} />,
    );
    expect(meta(container)).toBe("todos os 3 votaram");
  });

  it("secreta ABERTA: não muda (só o contador anônimo, sem linha de quem falta)", () => {
    const { container } = render(
      <PlacarMini placar={derivarPlacar(secretaAberta({ baseMembros: 3 }), false)} />,
    );
    expect(container.querySelector(".voto-nota")?.textContent).toBe("2 votos lançados (contador anônimo).");
    expect(meta(container)).toBeUndefined();
  });
});
