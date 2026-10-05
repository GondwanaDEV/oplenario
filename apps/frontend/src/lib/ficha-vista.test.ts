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
    desfecho: null,
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

  it("matéria que já foi ao Executivo deixa de dizer 'Aguardando pauta' (docs/16 linha 18)", () => {
    expect(derivarFicha(ficha({ estado: "aguardando_pauta" }), null).situacao).toBe("Aguardando pauta");
    expect(derivarFicha(ficha({ estado: "aguardando_pauta", desfecho: "sancionado" }), null).situacao).toBe("Sancionada");
    expect(derivarFicha(ficha({ estado: "aguardando_pauta", desfecho: "publicada" }), null).situacao).toBe("Virou lei");
  });

  // A faixa "Onde este projeto está" pelo RITO da Casa (a rota pública devolve `rito`): a ordem e os nomes das etapas
  // são os que a Casa declarou, não o mapa fixo por nome de estado. Vocabulário fora do mapa fixo, de propósito.
  describe("com o rito da Casa", () => {
    const etapa = (chave: string, rotulo: string, terminal = false) => ({ chave, rotulo, terminal });
    // uma linha de 3 etapas com a atual no meio (a do meio é a `atual` dada, com o nome que a Casa lhe deu)
    const rito = (atual: ReturnType<typeof etapa>): NonNullable<FichaOut["rito"]> => ({
      ordemUnica: true,
      etapas: [etapa("entrada", "Entrada na Mesa"), atual, etapa("plenario_unico", "Plenário único")],
      atual,
      anteriores: null,
      proximas: [etapa("plenario_unico", "Plenário único")],
    });

    it("a faixa diz as etapas na ordem do rito, com o nome da Casa", () => {
      const vista = derivarFicha(
        ficha({ estado: "instrucao", rito: rito(etapa("instrucao", "Instrução")) }),
        [],
      );
      expect(vista.estagios).toEqual([
        { chave: "entrada", rotulo: "Entrada na Mesa", situacao: "concluido" },
        { chave: "instrucao", rotulo: "Instrução", situacao: "ativo" },
        { chave: "plenario_unico", rotulo: "Plenário único", situacao: "pendente" },
      ]);
    });

    it("estado que o mapa fixo conhece, com rito: a faixa é a da Casa, não as 5 etapas ilustrativas", () => {
      const vista = derivarFicha(
        ficha({ estado: "em_pauta", rito: rito(etapa("em_pauta", "Na Ordem do Dia")) }),
        [],
      );
      expect(vista.estagios.find((e) => e.situacao === "ativo")?.rotulo).toBe("Na Ordem do Dia");
      expect(vista.estagios.map((e) => e.rotulo)).not.toContain("1º turno");
    });

    it("o selo de situação não muda nesta camada: segue o rótulo de estado (o nome da etapa é de outro caminho)", () => {
      const vista = derivarFicha(
        ficha({ estado: "em_pauta", rito: rito(etapa("em_pauta", "Na Ordem do Dia")) }),
        [],
      );
      expect(vista.situacao).toBe("Em pauta");
    });

    it("rito sem ordem única: só o entorno, com as próximas possíveis como ramos", () => {
      const vista = derivarFicha(
        ficha({
          estado: "analise",
          rito: {
            ordemUnica: false,
            etapas: [],
            atual: etapa("analise", "Análise"),
            anteriores: [etapa("recebida", "Recebida")],
            proximas: [etapa("via_a", "Via A"), etapa("via_b", "Via B")],
          },
        }),
        [],
      );
      expect(vista.estagios.map((e) => [e.rotulo, e.situacao, e.alternativa ?? false])).toEqual([
        ["Recebida", "concluido", false],
        ["Análise", "ativo", false],
        ["Via A", "pendente", true],
        ["Via B", "pendente", true],
      ]);
    });

    it("desfecho depois do plenário (autógrafo em diante) continua vencendo o rito, na faixa e no selo", () => {
      // (o selo vem do desfecho como sempre; a faixa também, e o rito é ignorado)
      const vista = derivarFicha(
        ficha({ estado: "em_pauta", desfecho: "sancionado", rito: rito(etapa("em_pauta", "Na Ordem do Dia")) }),
        [],
      );
      expect(vista.situacao).toBe("Sancionada");
      expect(vista.estagios.map((e) => e.rotulo)).toEqual(["Protocolo", "Comissões", "1º turno", "2º turno", "Sanção"]);
    });

    it("a votação em plenário (aprovada/rejeitada) não decide a faixa: o rito segue valendo", () => {
      const vista = derivarFicha(
        ficha({ estado: "em_pauta", desfecho: "aprovada", rito: rito(etapa("em_pauta", "Na Ordem do Dia")) }),
        [],
      );
      expect(vista.estagios.find((e) => e.situacao === "ativo")?.rotulo).toBe("Na Ordem do Dia");
    });

    it("rito de outra etapa que não a do estado (corrida entre eventos): cai no mapa fixo, nunca aponta a etapa errada", () => {
      const vista = derivarFicha(
        ficha({ estado: "em_comissoes", rito: rito(etapa("entrada", "Entrada na Mesa")) }),
        [],
      );
      expect(vista.situacao).toBe("Em comissões");
      expect(vista.estagios.map((e) => e.rotulo)).toEqual(["Protocolo", "Comissões", "1º turno", "2º turno", "Sanção"]);
    });

    it("rito sem a etapa atual, ou incoerente: mapa fixo, como antes", () => {
      const semAtual = derivarFicha(ficha({ rito: { ...rito(etapa("x", "X")), atual: null } }), []);
      expect(semAtual.situacao).toBe("Em comissões");
      const foraDasEtapas = derivarFicha(
        ficha({ estado: "xpto", rito: { ...rito(etapa("outra", "Outra")), atual: etapa("xpto", "Xpto") } }),
        [],
      );
      expect(foraDasEtapas.situacao).toBe("Xpto"); // etapa fora da linha: o mapa fixo humaniza o estado
      expect(foraDasEtapas.estagios).toEqual([{ rotulo: "Em tramitação", situacao: "ativo" }]);
    });

    it("rito nulo ou ausente (matéria sem evento novo): o mapa fixo de sempre", () => {
      for (const r of [null, undefined]) {
        const vista = derivarFicha(ficha({ rito: r }), []);
        expect(vista.situacao).toBe("Em comissões");
        expect(vista.estagios[1]).toEqual({ rotulo: "Comissões", situacao: "ativo" });
      }
    });
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
          temTexto: true,
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
      temTexto: true,
    });
  });

  it("norma sem texto publicado -> temTexto falso na vista (a tela não oferece o link)", () => {
    const vista = derivarFicha(
      ficha({
        estado: "aprovada",
        norma: {
          normaId: "n1",
          proposicaoId: "p1",
          tipoNorma: "lei",
          numero: 1,
          ano: 2026,
          urn: "urn:lex:br;x:lei:2026;1",
          ementa: "Cria X.",
          publicadoEm: "2026-08-01T00:00:00Z",
          veiculoPublicacao: "diario_oficial",
          temTexto: false,
        },
      }),
      [],
    );
    expect(vista.normaPublicada?.temTexto).toBe(false);
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

  it("fail-closed: estado desconhecido -> faixa neutra (sem etapa do rito), situação humanizada (nunca lança)", () => {
    const vista = derivarFicha(ficha({ estado: "xpto-desconhecido" }), []);
    // 05/10/2026: era [{Protocolo, ativo}] — apontava uma etapa que ninguém sabia estar certa.
    expect(vista.estagios).toEqual([{ rotulo: "Em tramitação", situacao: "ativo" }]);
    // Era `toBe("xpto-desconhecido")` — asserção sobre a CHAVE. `derivarTramitacao` passou a humanizar
    // (defeitos #9/#10 do ledger): fail-closed segue sendo não lançar e não fingir progresso, mas o
    // rótulo nunca é vocabulário de banco. Detector do underscore em tramitacao-vista.test.ts.
    expect(vista.situacao).toBe("Xpto desconhecido");
  });
});
