import { describe, expect, it } from "vitest";
import { derivarFicha, type ComentarioOut } from "./ficha-vista";
import type { FichaOut } from "./contrato-portal.gen";

// Task 3.1 (Fatia A2.3, Portal do Cidadão) — view-model puro da ficha pública: compõe ref/situação/
// estágios (mesma disciplina de materia-vista.ts) + a ligação à norma publicada (se houver — "virou lei")
// + os comentários aprovados (lista pública, `participacao/wire/out/comentario.clj` PublicoOut — SEM
// autor: a lista pública nunca expõe quem comentou, só o conteúdo já aprovado).

function ficha(parcial: Partial<FichaOut>): FichaOut {
  return {
    proposicaoId: "p1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    urnLex: "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
    autorTipo: "vereador",
    autorTexto: "Ver.ª Helena Matos",
    estado: "em_comissoes",
    ...parcial,
  };
}

function comentario(parcial: Partial<ComentarioOut>): ComentarioOut {
  return {
    id: "c1",
    corpo: "Apoio o projeto.",
    criadoEm: "2026-06-01T12:00:00Z",
    ...parcial,
  };
}

describe("derivarFicha", () => {
  it("compõe ref/título/situação/permalink/autoria/estágios a partir da matéria", () => {
    const vista = derivarFicha(ficha({}), []);
    expect(vista.ref).toBe("PL 042/2026");
    expect(vista.titulo).toBe("Cria o Programa Municipal de Hortas Comunitárias.");
    expect(vista.situacao).toBe("Em comissões");
    expect(vista.permalink).toBe("urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042");
    expect(vista.proposicaoId).toBe("p1");
    expect(vista.autorTexto).toBe("Ver.ª Helena Matos");
    expect(vista.estagios).toEqual([
      { rotulo: "Protocolo", situacao: "concluido" },
      { rotulo: "Comissões", situacao: "ativo" },
      { rotulo: "1º turno", situacao: "pendente" },
      { rotulo: "2º turno", situacao: "pendente" },
      { rotulo: "Sanção", situacao: "pendente" },
    ]);
  });

  it("autorTexto ausente -> null honesto, nunca undefined/inventado", () => {
    const vista = derivarFicha(ficha({ autorTexto: undefined, autorTipo: undefined }), []);
    expect(vista.autorTexto).toBeNull();
  });

  it("sem norma -> normaPublicada null", () => {
    const vista = derivarFicha(ficha({}), []);
    expect(vista.normaPublicada).toBeNull();
  });

  it("com norma publicada -> expõe o link/URN da norma", () => {
    const vista = derivarFicha(
      ficha({
        estado: "aprovada",
        norma: {
          normaId: "n1",
          proposicaoId: "p1",
          tipoNorma: "lei_ordinaria",
          numero: 1234,
          ano: 2026,
          urn: "urn:lex:br;ce;fortaleza:camara.municipal:lei:2026;1234",
          ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
          publicadoEm: "2026-08-01T00:00:00Z",
          veiculoPublicacao: "diario_oficial",
        },
      }),
      [],
    );
    expect(vista.normaPublicada).toEqual({
      normaId: "n1",
      urn: "urn:lex:br;ce;fortaleza:camara.municipal:lei:2026;1234",
      ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
      publicadoEm: "2026-08-01T00:00:00Z",
      tipoNorma: "lei_ordinaria",
      numero: 1234,
      ano: 2026,
    });
  });

  it("comentários vazios ([]) -> []", () => {
    expect(derivarFicha(ficha({}), []).comentarios).toEqual([]);
  });

  it("comentários ausentes (null, fetch degradado) -> [] honesto, nunca lança", () => {
    expect(derivarFicha(ficha({}), null).comentarios).toEqual([]);
  });

  it("comentários presentes -> repassados (id/corpo/criadoEm; SEM autor — a lista pública nunca expõe)", () => {
    const vista = derivarFicha(ficha({}), [comentario({ id: "c1" }), comentario({ id: "c2", corpo: "Só isso." })]);
    expect(vista.comentarios).toEqual([
      { id: "c1", corpo: "Apoio o projeto.", criadoEm: "2026-06-01T12:00:00Z" },
      { id: "c2", corpo: "Só isso.", criadoEm: "2026-06-01T12:00:00Z" },
    ]);
  });

  it("fail-closed: estado desconhecido -> faixa mínima honesta, situação = o estado cru (nunca lança)", () => {
    const vista = derivarFicha(ficha({ estado: "xpto-desconhecido" }), []);
    expect(vista.estagios).toEqual([{ rotulo: "Protocolo", situacao: "ativo" }]);
    expect(vista.situacao).toBe("xpto-desconhecido");
  });
});
