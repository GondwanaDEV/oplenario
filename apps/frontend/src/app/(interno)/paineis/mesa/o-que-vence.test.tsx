// Gate do rótulo de prazo do cartão "O que vence".
//
// POR QUE ESTE ARQUIVO EXISTE: este componente não tinha teste nenhum, e por isso um defeito
// visível na tela sobreviveu até ser achado numa checagem de prontidão manual, a olho: o helper
// `diasAte` fazia `Math.max(0, ...)`, achatando todo o passado em zero, e a linha imprimia
// "vence em 0 dia(s)" para uma obrigação vencida havia 16 dias — no MESMO cartão cuja manchete
// diz "1 obrigação venceu o prazo no TCE-CE". As duas metades do card de saúde institucional
// afirmavam coisas diferentes sobre o mesmo prazo.
//
// A asserção forte aqui não é "renderiza sem quebrar" — é que atrasado, hoje e futuro produzem
// TRÊS frases distintas. Um teste que só procurasse "dia(s)" passaria nos dois desenhos.

import { cleanup, render } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { OQueVence } from "./o-que-vence";

// 15/09/2026, 12:00 local — o mesmo "hoje" da checagem que achou o defeito.
const HOJE = new Date("2026-09-15T15:00:00.000Z");

function item(venceEm: string, id: string) {
  return {
    origem: "compliance" as const,
    id,
    objetoId: id,
    objetoTipo: "competencia",
    templateChave: "remessa_mensal_sim",
    protocolo: null,
    venceEm,
  };
}

function vista(itens: ReturnType<typeof item>[]) {
  return { estado: "disponivel" as const, itens, truncamentoPendencias: null };
}

describe("OQueVence — o prazo em palavras", () => {
  beforeEach(() => vi.useFakeTimers({ now: HOJE }));
  // `cleanup()` EXPLICITO: este projeto nao tem auto-cleanup do RTL, e as queries devolvidas por
  // `render` ligam-se a `document.body`, nao ao container — sem isto, o "nao existe X" de um teste
  // le' o X que o teste anterior montou. Foi exatamente o que aconteceu ao escrever este arquivo.
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  // Queries ESCOPADAS ao container, nunca o `screen` global: este projeto nao tem auto-cleanup do
  // RTL, entao o `screen` enxerga o DOM acumulado de todos os `render` anteriores do arquivo — o
  // "nao existe X" de um teste passaria a ler o X que outro teste montou. Mesma convencao de
  // `despachos-da-mesa.test.tsx`.
  it("prazo JÁ VENCIDO diz há quantos dias venceu — nunca 'vence em 0 dia(s)'", () => {
    // 30/08/2026 é o vencimento real da obrigação de 2026-07 na Casa da demo.
    const { getByText, queryByText } = render(<OQueVence vista={vista([item("2026-08-30", "a")])} />);
    expect(getByText(/venceu há 16 dia\(s\)/)).toBeTruthy();
    expect(queryByText(/vence em 0 dia\(s\)/)).toBeNull();
  });

  it("prazo de HOJE não se confunde com prazo vencido", () => {
    const { getByText, queryByText } = render(<OQueVence vista={vista([item("2026-09-15", "b")])} />);
    expect(getByText("vence hoje")).toBeTruthy();
    expect(queryByText(/venceu há/)).toBeNull();
  });

  it("prazo FUTURO segue dizendo em quantos dias vence", () => {
    const { getByText } = render(<OQueVence vista={vista([item("2026-09-30", "c")])} />);
    expect(getByText(/vence em 15 dia\(s\)/)).toBeTruthy();
  });

  it("as três frases são distintas entre si na mesma lista", () => {
    const { getAllByText } = render(
      <OQueVence
        vista={vista([item("2026-08-30", "a"), item("2026-09-15", "b"), item("2026-09-30", "c")])}
      />,
    );
    const frases = getAllByText(/venceu há|vence hoje|vence em/).map((n) => n.textContent);
    expect(new Set(frases).size).toBe(3);
  });
});
