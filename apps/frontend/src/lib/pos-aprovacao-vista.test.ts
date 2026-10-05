import { describe, expect, it } from "vitest";
import {
  derivarPipeline,
  dataDoPrazo,
  derivarPrazoExecutivo,
  formatarNumeroAutografo,
  formatarNumeroNorma,
  fraseDaNorma,
  fraseDoPrazoDoExecutivo,
  fraseErroGerarAutografo,
  instanteFimDoDia,
  promulgavel,
  validarDiaDoPrazo,
} from "./pos-aprovacao-vista";
import type { AutografoOut, NormaOut, TramitacaoExecutivaOut } from "./contrato-legislativo.gen";

const autografo: AutografoOut = {
  id: "a1",
  proposicaoId: "p1",
  numero: 22,
  ano: 2026,
  destinatarioTexto: "Prefeitura Municipal",
  enviadoEm: "2026-06-18T00:00:00Z",
  prazoRespostaEm: "2026-07-03T00:00:00Z",
};

function tramitacao(parcial: Partial<TramitacaoExecutivaOut>): TramitacaoExecutivaOut {
  return { id: "te1", autografoId: "a1", estado: "aguardando", lockVersion: 0, ...parcial };
}

describe("formatarNumeroAutografo", () => {
  it("numero em 3 dígitos + ano", () => {
    expect(formatarNumeroAutografo(22, 2026)).toBe("022/2026");
    expect(formatarNumeroAutografo(105, 2026)).toBe("105/2026");
  });
});

describe("derivarPipeline", () => {
  it("tramitacaoExecutiva nula -> trata como 'aguardando' (autógrafo feito, executivo atual, resto futuro)", () => {
    const etapas = derivarPipeline(autografo, null);
    expect(etapas).toHaveLength(5);
    expect(etapas[0]).toMatchObject({ rotulo: "Autógrafo", situacao: "feita" });
    expect(etapas[1]).toMatchObject({ rotulo: "No Executivo", situacao: "atual" });
    expect(etapas[2]).toMatchObject({ rotulo: "Sanção ou veto", situacao: "futura", detalhe: "aguarda" });
    expect(etapas[3]).toMatchObject({ rotulo: "Promulgação", situacao: "futura" });
    expect(etapas[4]).toMatchObject({ rotulo: "Publicação", situacao: "futura" });
  });

  it("estado 'aguardando' -> etapa 2 'atual', etapa 3 futura", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "aguardando" }));
    expect(etapas[1].situacao).toBe("atual");
    expect(etapas[2].situacao).toBe("futura");
  });

  it("estado 'sancionado' -> etapas 1-3 feitas, rótulo do desfecho com data de resposta", () => {
    const etapas = derivarPipeline(
      autografo,
      tramitacao({ estado: "sancionado", respondidoEm: "2026-07-01T09:00:00-03:00" }),
    );
    expect(etapas[1].situacao).toBe("feita");
    expect(etapas[2]).toMatchObject({ situacao: "feita" });
    expect(etapas[2].detalhe).toContain("Sancionado");
    expect(etapas[2].detalhe).toContain("01/07/2026");
  });

  it("estado 'vetado' -> etapa 3 feita com rótulo 'Vetado'; passos 4/5 seguem futura mesmo assim", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "vetado" }));
    expect(etapas[2]).toMatchObject({ situacao: "feita", detalhe: expect.stringContaining("Vetado") });
    expect(etapas[3].situacao).toBe("futura");
    expect(etapas[4].situacao).toBe("futura");
  });

  it("estado 'veto_derrubado' -> rótulo específico de apreciação", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "veto_derrubado" }));
    expect(etapas[2].detalhe).toContain("Veto derrubado");
  });
});

describe("derivarPrazoExecutivo", () => {
  it("prazoRespostaEm ausente -> 'sem-prazo'", () => {
    expect(derivarPrazoExecutivo({ ...autografo, prazoRespostaEm: null })).toEqual({ estado: "sem-prazo" });
  });

  it("dentro do prazo -> 'em-curso' com dias restantes/total calculados de enviadoEm/prazoRespostaEm reais", () => {
    // início 18/06, fim 03/07 (15 dias de janela total), agora 24/06 -> restam 9 dias.
    const agora = new Date("2026-06-24T00:00:00Z");
    const r = derivarPrazoExecutivo(autografo, agora);
    expect(r).toMatchObject({ estado: "em-curso", diasRestantes: 9, diasTotal: 15 });
  });

  it("faltando poucos dias -> categoria 'urgente'", () => {
    const agora = new Date("2026-07-02T00:00:00Z"); // 1 dia restante
    const r = derivarPrazoExecutivo(autografo, agora);
    expect(r).toMatchObject({ estado: "em-curso", categoria: "urgente" });
  });

  it("prazo no passado -> 'vencido' com dias vencidos", () => {
    const agora = new Date("2026-07-10T00:00:00Z"); // 7 dias depois do prazo (03/07)
    const r = derivarPrazoExecutivo(autografo, agora);
    expect(r).toEqual({ estado: "vencido", diasVencidos: 7 });
  });
});

function norma(parcial: Partial<NormaOut> = {}): NormaOut {
  return {
    id: "n1",
    proposicaoId: "p1",
    tipoNorma: "lei",
    numero: 12,
    ano: 2026,
    urn: "urn:lex:br;ce;fortaleza:lei:2026-10-05;12",
    ementa: "Dispõe sobre X",
    estado: "promulgada",
    promulgadoEm: "2026-10-05T12:00:00Z",
    lockVersion: 0,
    ...parcial,
  };
}

describe("a norma no pipeline (F3.8b)", () => {
  it("sancionada e ainda sem norma: Promulgação é a etapa atual", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "sancionado" }), null);
    expect(etapas[3]).toMatchObject({ rotulo: "Promulgação", situacao: "atual", detalhe: "pode promulgar" });
    expect(etapas[4]).toMatchObject({ rotulo: "Publicação", situacao: "futura" });
  });

  it("veto mantido: nada a promulgar, as duas etapas seguem futuras", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "veto_mantido" }), null);
    expect(etapas[3].situacao).toBe("futura");
    expect(etapas[4].situacao).toBe("futura");
  });

  it("promulgada: Promulgação feita com o número da lei; Publicação é a atual", () => {
    const etapas = derivarPipeline(autografo, tramitacao({ estado: "sancionado" }), norma());
    expect(etapas[3]).toMatchObject({ situacao: "feita", detalhe: "Lei nº 12/2026 · 05/10/2026" });
    expect(etapas[4]).toMatchObject({ rotulo: "Publicação", situacao: "atual" });
  });

  it("publicada: as cinco etapas feitas", () => {
    const etapas = derivarPipeline(
      autografo,
      tramitacao({ estado: "veto_derrubado" }),
      norma({ estado: "publicada", publicadoEm: "2026-10-06T12:00:00Z" }),
    );
    expect(etapas.map((e) => e.situacao)).toEqual(["feita", "feita", "feita", "feita", "feita"]);
    expect(etapas[4].detalhe).toBe("06/10/2026");
  });

  it("promulgavel: sanção, sanção tácita e veto derrubado — nada mais", () => {
    expect(["sancionado", "sancao_tacita", "veto_derrubado"].every(promulgavel)).toBe(true);
    expect(["aguardando", "vetado", "veto_mantido", null, undefined].some(promulgavel)).toBe(false);
  });

  it("formatarNumeroNorma diz a espécie por extenso", () => {
    expect(formatarNumeroNorma("lei", 12, 2026)).toBe("Lei nº 12/2026");
    expect(formatarNumeroNorma("lei_complementar", 3, 2026)).toBe("Lei Complementar nº 3/2026");
    expect(formatarNumeroNorma("emenda_lom", 1, 2026)).toBe("Emenda à Lei Orgânica nº 1/2026");
  });
});

describe("fraseDaNorma — o Desfecho depois da promulgação", () => {
  it("concorda com a espécie e diz se já foi publicada", () => {
    expect(fraseDaNorma({ tipoNorma: "lei", numero: 5, ano: 2026, estado: "publicada" })).toBe(
      "virou a Lei nº 5/2026, já publicada",
    );
    expect(fraseDaNorma({ tipoNorma: "decreto_legislativo", numero: 1, ano: 2026, estado: "publicada" })).toBe(
      "virou o Decreto Legislativo nº 1/2026, já publicado",
    );
    expect(fraseDaNorma({ tipoNorma: "resolucao", numero: 2, ano: 2026, estado: "promulgada" })).toBe(
      "virou a Resolução nº 2/2026, que ainda falta publicar",
    );
  });
});

describe("prazo de resposta do Executivo ao gerar o autógrafo", () => {
  it("o prazo vai como o ÚLTIMO instante do dia escolhido, no fuso da Casa (-03:00)", () => {
    expect(instanteFimDoDia("2026-10-20")).toBe("2026-10-20T23:59:59-03:00");
  });

  it("o instante enviado volta como o MESMO dia, qualquer que seja o fuso de quem lê", () => {
    expect(dataDoPrazo(instanteFimDoDia("2026-10-20"))).toBe("20/10/2026");
    // o servidor devolve em UTC: 20/10 23h59 em Fortaleza = 21/10 02h59 UTC
    expect(dataDoPrazo("2026-10-21T02:59:59Z")).toBe("20/10/2026");
  });

  it("a frase diz até quando o Executivo pode sancionar ou vetar", () => {
    expect(fraseDoPrazoDoExecutivo("2026-10-21T02:59:59Z")).toBe(
      "O Executivo tem até 20/10/2026 para sancionar ou vetar.",
    );
  });

  describe("validarDiaDoPrazo (hoje = 2026-10-05)", () => {
    const hoje = "2026-10-05";
    it("vazio é permitido: o prazo é opcional no backend", () => {
      expect(validarDiaDoPrazo("", hoje)).toBeNull();
    });
    it("hoje e dias futuros passam", () => {
      expect(validarDiaDoPrazo("2026-10-05", hoje)).toBeNull();
      expect(validarDiaDoPrazo("2026-10-26", hoje)).toBeNull();
    });
    it("dia anterior a hoje é recusado com frase em português", () => {
      expect(validarDiaDoPrazo("2026-10-04", hoje)).toBe("O prazo não pode ser uma data que já passou.");
    });
    it("data que não existe é recusada", () => {
      expect(validarDiaDoPrazo("2026-02-31", hoje)).toBe("Informe uma data válida para o prazo.");
      expect(validarDiaDoPrazo("20/10/2026", hoje)).toBe("Informe uma data válida para o prazo.");
    });
  });

  describe("fraseErroGerarAutografo: o erro do backend vira frase para a secretaria", () => {
    it("400 genérico do backend", () => {
      expect(fraseErroGerarAutografo("requisicao invalida")).toMatch(/prazo|já foi gerado/i);
    });
    it("409: matéria não aprovada em votação", () => {
      expect(fraseErroGerarAutografo("gerar-autografo: a materia nao foi aprovada em votacao pela Camara")).toMatch(
        /aprovar a matéria em votação/i,
      );
    });
    it("409: votação não registrou o texto deliberado", () => {
      expect(
        fraseErroGerarAutografo("gerar-autografo: a votacao que aprovou esta materia nao registrou qual texto foi deliberado"),
      ).toMatch(/texto/i);
    });
    it("403", () => {
      expect(fraseErroGerarAutografo("autorizacao negada")).toMatch(/permissão/i);
    });
    it("mensagem desconhecida segue como veio (nunca esconde)", () => {
      expect(fraseErroGerarAutografo("falha de rede — tente novamente")).toBe("falha de rede — tente novamente");
    });
  });
});
