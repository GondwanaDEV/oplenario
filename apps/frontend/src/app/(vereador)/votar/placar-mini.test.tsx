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
