// Fecha a DÍVIDA que a revisão adversarial da branch anterior registrou explicitamente: `nomeFase`
// estava provada como função pura, e NADA provava que a tribuna a chamava — um refactor podia
// reintroduzir a chave crua no telão com a CI verde. Estes testes afirmam sobre o TEXTO RENDERIZADO,
// nunca sobre os mapas por dentro.
//
// A garantia central: **nenhum pedaço de UUID chega ao texto visível**. Era esse o defeito — o avatar
// mostrava dois caracteres do id ("E9") e a fila de inscritos, o prefixo dele ("64d38c04").

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Tribuna } from "./tribuna";
import type { EstadoPlenario, IdentidadeParlamentar } from "@/lib/plenario-reducer";

const ID_ANA = "cff5ac75-4717-469e-b349-665c001962ad";
const ID_BRUNO = "64d38c04-1111-2222-3333-444455556666";
const ID_CIDADAO = "e9aa0000-9999-8888-7777-666655554444";

function estadoBase(over: Partial<EstadoPlenario> = {}): EstadoPlenario {
  return {
    estado: "aberta",
    presentes: [],
    presencaEm: {},
    quorum: null,
    quorumStatus: "ok",
    precisaRehidratar: false,
    oradorAtual: null,
    marcosCronometro: [],
    ultimaFalaEncerrada: null,
    inscritos: [],
    placar: null,
    composicao: null,
    composicaoStatus: "carregando",
    ultimoSeq: 0,
    ...over,
  } as EstadoPlenario;
}

const composicaoCom = (...pares: [string, IdentidadeParlamentar][]) => new Map(pares);

const oradorAna = {
  falaId: "f1",
  oradorId: ID_ANA,
  tipoFala: "pela_ordem",
  fase: "ordem_do_dia",
  iniciouEm: "2026-09-01T23:00:00Z",
};

describe("Tribuna — o telão nunca mostra UUID no lugar de identidade", () => {
  it("com a composição carregada, mostra o NOME do orador, e nenhum pedaço do id", () => {
    const estado = estadoBase({
      oradorAtual: oradorAna,
      composicao: composicaoCom([ID_ANA, { nomeParlamentar: "Ana Ribeiro", cargoMesa: "presidente" }]),
      composicaoStatus: "ok",
    });
    const { container } = render(<Tribuna estado={estado} agora={Date.parse("2026-09-01T23:01:00Z")} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/Ana Ribeiro/);
    expect(texto).toMatch(/AR/); // iniciais reais no avatar, não caracteres do UUID
    expect(texto).not.toMatch(/cff5ac75/);
  });

  it("SEM composição (ainda carregando), cai no rótulo neutro — e não no prefixo do UUID", () => {
    const estado = estadoBase({ oradorAtual: oradorAna });
    const { container } = render(<Tribuna estado={estado} agora={Date.parse("2026-09-01T23:01:00Z")} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/Orador com a palavra/);
    expect(texto).not.toMatch(/cff5ac75|CF/);
  });

  it("orador que NÃO é membro da Casa (tribuna livre do cidadão) não é chamado de não-identificado", () => {
    // `orador-id` não tem FK para vereador e a fase `tribuna_livre_cidadao` existe: este caso é
    // legítimo, não um erro de cadastro. A tela não pode AFIRMAR identidade nem afirmar a falta dela.
    const estado = estadoBase({
      oradorAtual: { ...oradorAna, oradorId: ID_CIDADAO, fase: "tribuna_livre_cidadao", tipoFala: "principal" },
      composicao: composicaoCom([ID_ANA, { nomeParlamentar: "Ana Ribeiro", cargoMesa: null }]),
      composicaoStatus: "ok",
    });
    const { container } = render(<Tribuna estado={estado} agora={Date.parse("2026-09-01T23:01:00Z")} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/Orador com a palavra/);
    expect(texto).not.toMatch(/e9aa0000|E9/);
    expect(texto).not.toMatch(/não identificado|nao identificado/i);
  });

  it("a fila de inscritos mostra nomes, nunca o prefixo do id", () => {
    const estado = estadoBase({
      inscritos: [
        { inscricaoId: "i1", vereadorId: ID_BRUNO, fase: "ordem_do_dia", ordem: 1 },
        { inscricaoId: "i2", vereadorId: ID_CIDADAO, fase: "ordem_do_dia", ordem: 2 },
      ],
      composicao: composicaoCom([ID_BRUNO, { nomeParlamentar: "Bruno Sales", cargoMesa: null }]),
      composicaoStatus: "ok",
    });
    const { container } = render(<Tribuna estado={estado} agora={Date.now()} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/Bruno Sales/);
    expect(texto).not.toMatch(/64d38c04|e9aa0000/); // nem o resolvido nem o não-resolvido vazam id
    expect(texto).toMatch(/Inscrito/); // o não-resolvido cai no rótulo neutro
  });
});

describe("Tribuna — chave de enum não chega ao telão", () => {
  // Estes dois testes existem porque a garantia estava provada só na função PURA. Se alguém trocar
  // `nomeFase(o.fase)` por `o.fase`, ou `nomeTipoFala(o.tipoFala)` por `o.tipoFala`, é AQUI que reprova.
  it("a fase sai humanizada — 'Ordem do Dia', nunca 'ordem_do_dia'", () => {
    const estado = estadoBase({ oradorAtual: oradorAna });
    const { container } = render(<Tribuna estado={estado} agora={Date.now()} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/Ordem do Dia/);
    expect(texto).not.toMatch(/ordem_do_dia/);
  });

  it("o tipo da fala sai humanizado — 'Pela ordem', nunca 'pela_ordem'", () => {
    const estado = estadoBase({ oradorAtual: oradorAna });
    const { container } = render(<Tribuna estado={estado} agora={Date.now()} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/Pela ordem/);
    expect(texto).not.toMatch(/pela_ordem/);
  });

  it("sem orador, o cabeçalho diz 'livre' e o corpo não inventa ninguém", () => {
    const { container } = render(<Tribuna estado={estadoBase()} agora={Date.now()} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/Ninguém com a palavra no momento/);
    expect(texto).not.toMatch(/Orador com a palavra/);
  });
});
