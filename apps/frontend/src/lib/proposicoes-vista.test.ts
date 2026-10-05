import { describe, expect, it } from "vitest";
import {
  categorizarSituacao,
  derivarProposicoesVista,
  ESPECIES_PROPOSICAO,
  ESTADOS_AGUARDANDO_PAUTA,
  ESTADOS_EM_PAUTA,
  formatarNumeroProposicao,
} from "./proposicoes-vista";

// Item 4 do lote 05/10: o filtro "Espécie" da lista omitia Lei Complementar e Emenda à LOM porque cada tela
// mantinha a SUA lista. Esta é a lista do backend (apps/backend/src/oplenario/legislativo/logic.clj `tipos`,
// "vocabulario fechado da V1; cresce por adicao") — escrita aqui de propósito: espécie nova no backend sem
// rótulo no frontend reprova este teste (e o `Record<…["tipo"], string>` de proposicoes-vista.ts reprova o
// tsc quando o contrato gerado ganhar a espécie).
const TIPOS_DO_BACKEND = [
  "projeto_lei", "projeto_lei_complementar", "projeto_resolucao", "projeto_decreto_legislativo",
  "proposta_emenda_lom", "indicacao", "requerimento", "mocao",
];

describe("ESPECIES_PROPOSICAO (única lista de espécies)", () => {
  it("cobre TODAS as espécies do backend, sem sobra", () => {
    expect(ESPECIES_PROPOSICAO.map((e) => e.valor).sort()).toEqual([...TIPOS_DO_BACKEND].sort());
  });

  it("toda espécie tem rótulo em palavras e sigla, nunca a chave crua", () => {
    for (const { valor, rotulo } of ESPECIES_PROPOSICAO) {
      expect(rotulo).not.toMatch(/_/);
      expect(rotulo).not.toBe(valor);
      expect(formatarNumeroProposicao(valor, 1, 2026)).not.toContain(valor);
    }
  });

  it("inclui Lei Complementar e Emenda à LOM (as duas que o filtro omitia)", () => {
    const rotulos = ESPECIES_PROPOSICAO.map((e) => e.rotulo);
    expect(rotulos).toContain("Projeto de Lei Complementar");
    expect(rotulos).toContain("Emenda à LOM");
  });
});
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";

const base: ProposicaoResumoOut = {
  id: "11111111-1111-1111-1111-111111111111",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 42,
  urnLex: "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42",
  ementa: "Cria o Programa Municipal de Hortas Comunitárias",
  autorTipo: "vereador",
  autorTexto: "Helena Matos",
  estado: "em_comissoes",
  atualizadoEm: "2026-05-21T10:00:00Z",
};

describe("derivarProposicoesVista", () => {
  it("monta o número no formato SIGLA sequencial/ano", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.numero).toBe("PL 42/2026");
  });

  it("mapeia a espécie para um rótulo legível", () => {
    const [linha] = derivarProposicoesVista([{ ...base, tipo: "requerimento" }]);
    expect(linha.especie).toBe("Requerimento");
  });

  it("a partir do autógrafo o desfecho diz a situação e a cor do chip (docs/16 linha 18)", () => {
    const [lei] = derivarProposicoesVista([{ ...base, estado: "aguardando_pauta", desfecho: "publicada" }]);
    expect(lei.situacao.rotulo).toBe("Virou lei");
    expect(lei.situacao.categoria).toBe("aprovada");
    const [vetada] = derivarProposicoesVista([{ ...base, estado: "aguardando_pauta", desfecho: "vetado" }]);
    expect(vetada.situacao.rotulo).toBe("Vetada");
    expect(vetada.situacao.categoria).toBe("tram");
    const [semDesfecho] = derivarProposicoesVista([{ ...base, estado: "aguardando_pauta", desfecho: null }]);
    expect(semDesfecho.situacao.rotulo).toBe("Aguardando pauta");
  });

  it("autor ausente vira travessão, não string vazia/undefined", () => {
    const [linha] = derivarProposicoesVista([{ ...base, autorTexto: undefined, autorTipo: undefined }]);
    expect(linha.autor).toBe("—");
  });

  it("reaproveita derivarTramitacao para a situação (estado conhecido)", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.situacao.rotulo).toBe("Em comissões");
    expect(linha.situacao.estagios.length).toBeGreaterThan(0);
  });

  it("estado desconhecido degrada honesto (fail-closed), nunca lança", () => {
    const [linha] = derivarProposicoesVista([{ ...base, estado: "estado_customizado_do_tenant" }]);
    // Era a CHAVE crua — e era exatamente este vocabulário de tenant que aparecia na tela em
    // `/proposicoes` (defeito #9 do ledger, `CONSTRANGE`). Degradar não obriga a expor a chave.
    expect(linha.situacao.rotulo).toBe("Estado customizado do tenant");
  });

  it("lista vazia vira lista vazia", () => {
    expect(derivarProposicoesVista([])).toEqual([]);
  });

  it("expõe a categoria do chip de status junto de rótulo/estágios", () => {
    const [linha] = derivarProposicoesVista([base]);
    expect(linha.situacao.categoria).toBe("tram");
  });
});

describe("categorizarSituacao", () => {
  it("estado em andamento (nenhum terminal, nenhuma espera de pauta) categoriza como 'tram'", () => {
    expect(categorizarSituacao("protocolada")).toBe("tram");
    expect(categorizarSituacao("em_comissoes")).toBe("tram");
    expect(categorizarSituacao("primeiro_turno")).toBe("tram");
  });

  it("estado de espera de pauta categoriza como 'aguarda'", () => {
    expect(categorizarSituacao("em_pauta")).toBe("aguarda");
    expect(categorizarSituacao("aguardando_pauta")).toBe("aguarda");
  });

  it("ESTADOS_AGUARDANDO_PAUTA é só a matéria pronta (aguardando_pauta); em_pauta tem o próprio conjunto", () => {
    expect([...ESTADOS_AGUARDANDO_PAUTA]).toEqual(["aguardando_pauta"]);
    expect([...ESTADOS_EM_PAUTA]).toEqual(["em_pauta"]);
  });

  it("estado terminal de sucesso categoriza como 'aprovada'", () => {
    expect(categorizarSituacao("aprovada")).toBe("aprovada");
  });

  it("estados terminais do ciclo do Executivo (legislativo/logic.clj) categorizam como 'aprovada'", () => {
    expect(categorizarSituacao("sancionado")).toBe("aprovada");
    expect(categorizarSituacao("sancao_tacita")).toBe("aprovada");
    expect(categorizarSituacao("veto_derrubado")).toBe("aprovada");
  });

  it("estado terminal de arquivamento categoriza como 'arquivada'", () => {
    expect(categorizarSituacao("arquivada")).toBe("arquivada");
  });

  it("estados terminais-negativos (rejeição/votação) categorizam como 'arquivada'", () => {
    expect(categorizarSituacao("rejeitada")).toBe("arquivada");
    expect(categorizarSituacao("prejudicada")).toBe("arquivada");
    expect(categorizarSituacao("retirada")).toBe("arquivada");
  });

  it("estado desconhecido (vocabulário livre do tenant) degrada fail-closed para 'tram', nunca lança", () => {
    expect(() => categorizarSituacao("estado_customizado_do_tenant")).not.toThrow();
    expect(categorizarSituacao("estado_customizado_do_tenant")).toBe("tram");
  });
});
