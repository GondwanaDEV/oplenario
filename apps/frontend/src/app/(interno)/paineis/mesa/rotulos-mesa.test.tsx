// O painel da Mesa mostrava a CHAVE do enum em vez do rotulo de tela: "EM_COMISSOES", "PROJETO_LEI",
// "pedido_esic". A casa ja tinha os tradutores (`derivarRef`/`derivarTramitacao` em materia-vista/
// tramitacao-vista, usados pelo Portal do Cidadao) — o painel simplesmente nao os chamava. Estes
// testes travam a GARANTIA VISIVEL (o texto que sai na tela), nunca o mapa por dentro: um teste que
// afirmasse `MAPA["em_comissoes"] === "Em comissoes"` continuaria verde com o componente renderizando
// a chave crua.

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DespachosDaMesa } from "./despachos-da-mesa";
import { OQueVence } from "./o-que-vence";
import { PipelineLegislativo } from "./pipeline-legislativo";
import { rotularObjetoPrazo } from "@/lib/mesa-vista";

const pipeline = {
  estado: "disponivel" as const,
  comItens: true,
  porEstado: [
    { estado: "em_comissoes", n: 1 },
    { estado: "segundo_turno", n: 1 },
  ],
  itens: [
    { proposicaoId: "p1", estado: "em_comissoes", tipo: "projeto_lei_complementar", sequencial: 2, ano: 2026, ementa: "Altera o Codigo de Posturas", urnLex: "urn:lex:1", transicionouEm: "2026-08-01T00:00:00Z" },
    { proposicaoId: "p2", estado: "segundo_turno", tipo: "projeto_lei", sequencial: 1, ano: 2026, ementa: "Hortas comunitarias", urnLex: "urn:lex:2", transicionouEm: "2026-08-02T00:00:00Z" },
  ],
};

describe("painel da Mesa — nenhuma chave de enum chega a tela", () => {
  it("pipeline: estagio e referencia saem humanizados, sem underscore", () => {
    const { container } = render(<PipelineLegislativo vista={pipeline} />);
    const texto = container.textContent ?? "";
    expect(texto).not.toMatch(/em_comissoes|segundo_turno|projeto_lei/);
    expect(texto).toMatch(/PLC 002\/2026/);
    expect(texto).toMatch(/PL 001\/2026/);
  });

  it("pipeline: o rotulo do estagio e' o mesmo que o Portal do Cidadao ja mostra", () => {
    // aparece em mais de um lugar de proposito (o cartao do estagio e a legenda da barra) — o que
    // importa e' que o texto humanizado exista, nao que seja unico.
    const { getAllByText } = render(<PipelineLegislativo vista={pipeline} />);
    expect(getAllByText(/Em comiss/i).length).toBeGreaterThan(0);
  });

  it("despachos: a referencia da proposicao sai como sigla, nao como o tipo cru", () => {
    const vista = {
      relator: {
        estado: "disponivel" as const,
        itens: [{
          id: "r1",
          proposicaoId: "p5",
          tipo: "projeto_lei",
          sequencial: 5,
          ano: 2026,
          urnLex: "urn:lex:5",
          ementa: "Semana do Voluntariado",
          criadoEm: "2026-08-03T00:00:00Z",
        }],
      },
      distribuicao: { estado: "em-breve" as const },
      autografo: { estado: "em-breve" as const },
      ata: { estado: "em-breve" as const },
    };
    const { container } = render(<DespachosDaMesa vista={vista} />);
    const texto = container.textContent ?? "";
    expect(texto).not.toMatch(/projeto_lei|PROJETO_LEI/);
    expect(texto).toMatch(/PL 005\/2026/);
  });

  it("o que vence: o tipo do objeto sai humanizado", () => {
    const vista = {
      estado: "disponivel" as const,
      itens: [
        {
          origem: "pendencia" as const,
          objetoTipo: "pedido_esic",
          objetoId: "o1",
          protocolo: "ESIC-2026-000001",
          venceEm: "2026-12-31",
          estado: "em_aberto",
        },
      ],
    };
    const { container } = render(<OQueVence vista={vista} />);
    const texto = container.textContent ?? "";
    expect(texto).not.toMatch(/pedido_esic/);
    expect(texto).toMatch(/Pedido e-SIC/);
  });
});

describe("rotularObjetoPrazo — vocabulario da FONTE (CHECK de paineis.pendencia)", () => {
  // CHECK (objeto_tipo IN ('pedido_esic','recurso_esic','solicitacao_titular','manifestacao_ouvidoria'))
  it.each([
    ["pedido_esic", "Pedido e-SIC"],
    ["recurso_esic", "Recurso e-SIC"],
    ["solicitacao_titular", "Solicitacao do titular (LGPD)"],
    ["manifestacao_ouvidoria", "Manifestacao de ouvidoria"],
  ])("%s -> %s", (chave, rotulo) => {
    expect(rotularObjetoPrazo(chave)).toBe(rotulo);
  });

  it("fail-closed: chave fora do vocabulario devolve a propria chave, nunca lanca", () => {
    expect(rotularObjetoPrazo("tipo_que_nao_existe")).toBe("tipo_que_nao_existe");
  });
});
