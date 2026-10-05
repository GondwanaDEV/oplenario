import { describe, expect, it } from "vitest";
import { categoriaDoDesfecho, colunaDoDesfecho, desfechoDaPosAprovacao, situacaoDoDesfecho } from "./desfecho-vista";
import { derivarTramitacao } from "./tramitacao-vista";

// docs/16, retriagem linha 18: o selo do portal depois do plenário

describe("situacaoDoDesfecho", () => {
  it("a lei publicada não diz mais 'Aguardando pauta' e fecha a faixa", () => {
    expect(derivarTramitacao("aguardando_pauta", "publicada").rotuloSituacao).toBe("Virou lei");
    expect(situacaoDoDesfecho("publicada")?.estagios.every((e) => e.situacao === "concluido")).toBe(true);
  });

  it("à espera do Executivo, a Sanção está em andamento", () => {
    const r = situacaoDoDesfecho("autografo_enviado");
    expect(r?.rotuloSituacao).toBe("Enviada ao Executivo");
    expect(r?.estagios.map((e) => e.situacao)).toEqual(["concluido", "concluido", "concluido", "concluido", "ativo"]);
  });

  it("só a votação em plenário não muda o selo: em dois turnos, 'Aguardando pauta' depois do 1º é verdade", () => {
    expect(situacaoDoDesfecho("aprovada")).toBeNull();
    expect(situacaoDoDesfecho("rejeitada")).toBeNull();
    expect(derivarTramitacao("aguardando_pauta", "aprovada").rotuloSituacao).toBe("Aguardando pauta");
  });

  it("sem desfecho ou desfecho desconhecido, a situação vem do rito", () => {
    expect(situacaoDoDesfecho(null)).toBeNull();
    expect(derivarTramitacao("em_comissoes", "ato_de_uma_versao_futura").rotuloSituacao).toBe("Em comissões");
  });
});

describe("categoriaDoDesfecho e colunaDoDesfecho", () => {
  it("lei = aprovada e Concluídas; veto por apreciar = em tramitação e Em Plenário; votação não decide", () => {
    expect(categoriaDoDesfecho("publicada")).toBe("aprovada");
    expect(colunaDoDesfecho("publicada")).toBe("concluidas");
    expect(categoriaDoDesfecho("veto_mantido")).toBe("arquivada");
    expect(categoriaDoDesfecho("vetado")).toBe("tram");
    expect(colunaDoDesfecho("vetado")).toBe("em-plenario");
    expect(categoriaDoDesfecho("aprovada")).toBeNull();
    expect(colunaDoDesfecho("aprovada")).toBeNull();
    expect(colunaDoDesfecho(null)).toBeNull();
  });
});

// a ficha interna não recebe `desfecho` na rota da ficha: lê os mesmos atos pela pós-aprovação, com a regra da lista
describe("desfechoDaPosAprovacao", () => {
  const autografo = {
    id: "a1", proposicaoId: "1", numero: 7, ano: 2026, destinatarioTexto: "Prefeito", enviadoEm: "2026-06-01T12:00:00Z",
  };
  const executivo = (estado: string) => ({ id: "t1", autografoId: "a1", estado, lockVersion: 1 });
  const norma = (estado: string) => ({
    id: "n1", proposicaoId: "1", tipoNorma: "lei_ordinaria", numero: 1, ano: 2026, urn: "urn:x", ementa: "e", estado,
    promulgadoEm: "2026-06-20T12:00:00Z", lockVersion: 1,
  });

  it("sem pós-aprovação ou sem autógrafo: não decide", () => {
    expect(desfechoDaPosAprovacao(null)).toBeNull();
    expect(desfechoDaPosAprovacao({ autografo: null, tramitacaoExecutiva: null, norma: null })).toBeNull();
  });

  it("autógrafo à espera da resposta do Executivo", () => {
    expect(desfechoDaPosAprovacao({ autografo, tramitacaoExecutiva: executivo("aguardando") })).toBe("autografo_enviado");
  });

  it("a resposta do Executivo e a apreciação do veto", () => {
    expect(desfechoDaPosAprovacao({ autografo, tramitacaoExecutiva: executivo("vetado") })).toBe("vetado");
    expect(desfechoDaPosAprovacao({ autografo, tramitacaoExecutiva: executivo("veto_derrubado") })).toBe("veto_derrubado");
  });

  it("a norma vence: promulgada, depois publicada", () => {
    const base = { autografo, tramitacaoExecutiva: executivo("sancionado") };
    expect(desfechoDaPosAprovacao({ ...base, norma: norma("promulgada") })).toBe("promulgada");
    expect(desfechoDaPosAprovacao({ ...base, norma: norma("publicada") })).toBe("publicada");
  });
});
