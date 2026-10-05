import { describe, expect, it } from "vitest";
import { derivarMeusAcompanhamentosVista } from "./meus-acompanhamentos-vista";
import type { MinhaMateria } from "./use-meus-acompanhamentos";

function materia(over: Partial<MinhaMateria> = {}): MinhaMateria {
  return {
    proposicaoId: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 12, urnLex: "urn:lex:1",
    ementa: "Altera a Lei Orgânica quanto à composição da Mesa Diretora.", estado: "em_pauta",
    seguidoEm: "2026-09-01T00:00:00Z", indisponivel: false,
    ...over,
  };
}

const ENTE = "3b87b2de-544b-4c43-9d67-930e6ea0ebd8";

describe("derivarMeusAcompanhamentosVista", () => {
  it("cada matéria disponível leva à ficha pública dela, na Casa da lista", () => {
    const [linha] = derivarMeusAcompanhamentosVista([materia()], ENTE);
    expect(linha.href).toBe(`/portal/casa/${ENTE}/materias/p1`);
  });

  it("matéria indisponível não tem ficha para abrir: sem link, nunca um link que dá 404", () => {
    const [linha] = derivarMeusAcompanhamentosVista(
      [materia({ indisponivel: true, tipo: null, ano: null, sequencial: null, ementa: null, estado: null })],
      ENTE,
    );
    expect(linha.href).toBeNull();
  });

  it("sem a Casa (servidor antigo) não há como montar o link: href nulo, a lista segue", () => {
    const [linha] = derivarMeusAcompanhamentosVista([materia()], null);
    expect(linha.href).toBeNull();
    expect(linha.titulo).toBe("PL 12/2026");
  });

  it("a situação sai em palavras, também para a fase que o rito ilustrativo não conhece (nunca a chave)", () => {
    expect(derivarMeusAcompanhamentosVista([materia({ estado: "em_comissoes" })], ENTE)[0].situacao?.rotulo).toBe("Em comissões");
    const [linha] = derivarMeusAcompanhamentosVista([materia({ estado: "aguardando_pauta" })], ENTE);
    expect(linha.situacao?.rotulo).toBe("Aguardando pauta");
  });

  it("matéria disponível -> número real + ementa real + situação derivada (sem vocabulário novo)", () => {
    const [linha] = derivarMeusAcompanhamentosVista([materia()], ENTE);
    expect(linha.titulo).toBe("PL 12/2026");
    expect(linha.ementa).toBe("Altera a Lei Orgânica quanto à composição da Mesa Diretora.");
    expect(linha.situacao?.categoria).toBe("aguarda"); // em_pauta está em ESTADOS_AGUARDANDO_PAUTA
    expect(linha.indisponivel).toBe(false);
  });

  it("terminal (aprovada) -> categoria 'aprovada'", () => {
    const [linha] = derivarMeusAcompanhamentosVista([materia({ estado: "aprovada" })], ENTE);
    expect(linha.situacao?.categoria).toBe("aprovada");
  });

  it("indisponivel=true (LEFT JOIN sem par) -> rótulo honesto, NUNCA esconde a linha nem formata null", () => {
    const itens = derivarMeusAcompanhamentosVista([
      materia({ indisponivel: true, tipo: null, ano: null, sequencial: null, ementa: null, estado: null }),
    ], ENTE);
    expect(itens).toHaveLength(1); // a linha aparece — não some da lista
    expect(itens[0].titulo).toBe("Matéria indisponível no momento");
    expect(itens[0].ementa).toBeNull();
    expect(itens[0].situacao).toBeNull();
    expect(itens[0].indisponivel).toBe(true);
  });

  it("campo numérico ausente mesmo com indisponivel=false (inconsistência defensiva) -> ainda cai no honesto, nunca formata '12/undefined'", () => {
    const [linha] = derivarMeusAcompanhamentosVista([materia({ sequencial: null })], ENTE);
    expect(linha.indisponivel).toBe(true);
    expect(linha.titulo).not.toContain("undefined");
    expect(linha.titulo).not.toContain("null");
  });

  it("preserva seguidoEm e a ordem da lista recebida", () => {
    const itens = derivarMeusAcompanhamentosVista([
      materia({ proposicaoId: "a", seguidoEm: "2026-01-01T00:00:00Z" }),
      materia({ proposicaoId: "b", seguidoEm: "2026-02-01T00:00:00Z" }),
    ], ENTE);
    expect(itens.map((i) => i.proposicaoId)).toEqual(["a", "b"]);
    expect(itens[1].seguidoEm).toBe("2026-02-01T00:00:00Z");
  });
});
