// Frente "truncamento-familia" — a fila de relatores pendentes (dashboard da Mesa) parou de fingir
// completude. Dois achados cobertos aqui: (1) `truncado` é AUTORITATIVO do servidor (sonda teto+1),
// nunca deduzido comparando contagens locais; (2) achado "classe JOIN" — um item cujo `objeto_id` não
// resolveu (LEFT JOIN sem par) chega com `indisponivel: true` e cabeçalho nulo, e a tela precisa
// renderizar isso sem chamar `derivarRef` (que exige tipo/sequencial/ano presentes) e sem esconder o
// item da fila.

import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { DespachosDaMesa } from "./despachos-da-mesa";
import type { MesaVista } from "@/lib/mesa-vista";

afterEach(() => cleanup());

const itemDisponivel = {
  id: "r1",
  proposicaoId: "p1",
  tipo: "projeto_lei",
  sequencial: 7,
  ano: 2026,
  urnLex: "urn:lex:7",
  ementa: "Arborização viária",
  criadoEm: "2026-08-03T00:00:00Z",
  indisponivel: false,
};

const itemOrfao = {
  id: "r2",
  proposicaoId: "p2",
  tipo: null,
  sequencial: null,
  ano: null,
  urnLex: null,
  ementa: null,
  criadoEm: "2026-08-04T00:00:00Z",
  indisponivel: true,
};

function vistaCom(relator: MesaVista["despachos"]["relator"]): MesaVista["despachos"] {
  return {
    relator,
    distribuicao: { estado: "em-breve" as const },
    autografo: { estado: "em-breve" as const },
    ata: { estado: "em-breve" as const },
  };
}

describe("DespachosDaMesa — a fila de relatores para de fingir completude", () => {
  it("truncado=true mostra o aviso de corte mesmo com poucos itens exibidos (discordância deliberada)", () => {
    // discordância: 1 item na tela, mas o servidor afirma que há mais fora dela — se a tela deduzisse do
    // tamanho do array, nunca mostraria o aviso aqui.
    const { container } = render(
      <DespachosDaMesa vista={vistaCom({ estado: "disponivel", itens: [itemDisponivel as never], truncado: true })} />,
    );
    expect(container.querySelector(".aviso-corte")).not.toBeNull();
  });

  it("truncado=false não mostra o aviso de corte", () => {
    const { container } = render(
      <DespachosDaMesa vista={vistaCom({ estado: "disponivel", itens: [itemDisponivel as never], truncado: false })} />,
    );
    expect(container.querySelector(".aviso-corte")).toBeNull();
  });

  it("item com objeto órfão (indisponivel) continua na fila, não some, e não chama derivarRef sobre nulos", () => {
    const { container, getByText } = render(
      <DespachosDaMesa vista={vistaCom({ estado: "disponivel", itens: [itemOrfao as never], truncado: false })} />,
    );
    expect(container.querySelectorAll(".fila-item").length).toBe(1);
    expect(getByText("Matéria indisponível")).toBeTruthy();
  });

  it("item disponível segue mostrando a referência normal (sigla + número/ano)", () => {
    const { getByText } = render(
      <DespachosDaMesa vista={vistaCom({ estado: "disponivel", itens: [itemDisponivel as never], truncado: false })} />,
    );
    expect(getByText(/PL 007\/2026/)).toBeTruthy();
  });
});
