// Achado ao vivo (Daouda, verificação em browser, 12/09/2026): o palco projetava o enum CRU do domínio
// na parede do plenário — "Em votação: projeto_lei 7/2026" em vez de "PL 7/2026". `MateriaEmVotacao`
// (o pedaço extraído e testável de `page.tsx`, mesmo molde de `PlacarMini` em votar/placar-mini.test.tsx)
// reusa `tituloObjetoVotacao` — a MESMA função que já resolve isto para o cockpit do vereador em `/votar`
// — para as duas telas não poderem voltar a divergir. Este arquivo crava o rótulo: se a chave crua do
// enum (`_`) voltar a aparecer, é ESTE teste que reprova.

import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MateriaEmVotacao } from "./page";
import type { PlacarVotacao } from "@/lib/plenario-reducer";

afterEach(() => cleanup());

function placarBase(over: Partial<PlacarVotacao> = {}): PlacarVotacao {
  return {
    votacaoId: "v1",
    modalidade: "nominal",
    objetoTipo: "proposicao",
    objetoId: "p1",
    proposicao: null,
    encerrada: false,
    votosNominais: {},
    votosSecretos: 0,
    resultado: null,
    totais: null,
    baseMembros: null,
    ...over,
  };
}

describe("MateriaEmVotacao — o rótulo da matéria no palco (achado ao vivo, enum cru)", () => {
  it("proposição resolvida -> sigla+número+ementa (a forma que o cockpit já mostra), NUNCA a chave crua do enum", () => {
    const { container } = render(
      <MateriaEmVotacao
        placar={placarBase({
          proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 7, ementa: "Institui o Programa Municipal de Arborização Urbana." },
        })}
      />,
    );
    expect(container.textContent).toContain("PL 7/2026");
    expect(container.textContent).toContain("Institui o Programa Municipal de Arborização Urbana.");
    // a rede de segurança sobre o texto CRU — o defeito exato que a verificação em browser achou.
    expect(container.textContent).not.toContain("projeto_lei");
    expect(container.textContent).not.toMatch(/[a-z]+_[a-z]+/);
  });

  it("redação final resolvida -> mesmo formato de proposição (o objeto-id É a matéria)", () => {
    const { container } = render(
      <MateriaEmVotacao
        placar={placarBase({
          objetoTipo: "redacao_final",
          proposicao: { tipo: "projeto_lei_complementar", ano: 2025, sequencial: 3, ementa: "Dispõe sobre Y" },
        })}
      />,
    );
    expect(container.textContent).toContain("PLC 3/2025");
    expect(container.textContent).not.toContain("projeto_lei_complementar");
  });

  it("sem proposição resolvida (emenda/parecer/requerimento) -> rótulo honesto do TIPO, nunca em branco nem inventado", () => {
    const { container } = render(<MateriaEmVotacao placar={placarBase({ objetoTipo: "emenda", proposicao: null })} />);
    expect(container.textContent?.toLowerCase()).toContain("emenda");
  });

  it("sem placar -> não renderiza nada (nenhuma votação em curso)", () => {
    const { container } = render(<MateriaEmVotacao placar={null} />);
    expect(container.innerHTML).toBe("");
  });

  it("placar encerrado -> não renderiza nada (o resultado mora em <Placar>, não aqui)", () => {
    const { container } = render(<MateriaEmVotacao placar={placarBase({ encerrada: true })} />);
    expect(container.innerHTML).toBe("");
  });

  it("sem objetoTipo (forma inesperada) -> não renderiza nada, nunca 'undefined' na tela", () => {
    const { container } = render(<MateriaEmVotacao placar={placarBase({ objetoTipo: null })} />);
    expect(container.innerHTML).toBe("");
  });
});
