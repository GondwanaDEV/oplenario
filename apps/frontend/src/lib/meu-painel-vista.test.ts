import { describe, expect, it } from "vitest";
import { derivarHome } from "./meu-painel-vista";
import type { MeuPainelOut, ProposicaoResumoMeuPainelOut, ParecerResumoMeuPainelOut } from "./contrato-legislativo.gen";
import type { SessaoOut } from "./contrato-sessoes.gen";

function proposicao(over: Partial<ProposicaoResumoMeuPainelOut> = {}): ProposicaoResumoMeuPainelOut {
  return {
    id: "p1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 1,
    urnLex: "urn:lex:fixture",
    ementa: "Ementa fixture",
    estado: "protocolada",
    atualizadoEm: "2026-07-01T00:00:00Z",
    ...over,
  };
}

function parecer(over: Partial<ParecerResumoMeuPainelOut> = {}): ParecerResumoMeuPainelOut {
  return {
    id: "pc1",
    objetoTipo: "proposicao",
    objetoId: "p1",
    comissaoId: "com1",
    estado: "aguardando_designacao",
    votoRelator: null,
    criadoEm: "2026-07-01T00:00:00Z",
    ...over,
  };
}

function sessao(over: Partial<SessaoOut> = {}): SessaoOut {
  return {
    id: "s1",
    sessaoLegislativaId: "sl1",
    tipoSessao: "ordinaria",
    numeroSequencial: 1,
    estado: "agendada",
    modalidade: "presencial",
    delibera: true,
    transmitePublica: true,
    geraAtaRegimental: true,
    permiteVotoSecreto: false,
    permiteModalidadeRemota: false,
    agendadaPara: emDias(30),
    abertaEm: null,
    encerradaEm: null,
    motivoNaoRealizada: null,
    ...over,
  };
}

/** Datas RELATIVAS ao agora. Antes eram absolutas ("2026-08-01" como "sessão futura") e o teste
 * `próxima sessão escolhe a de menor data FUTURA` passou a REPROVAR sozinho quando o relógio de parede
 * alcançou a data — sem ninguém mudar uma linha de código. O código estava certo: a sessão tinha virado
 * passado e `derivarHome` corretamente escolheu a outra. Um teste cuja verdade depende do dia em que roda
 * não distingue regressão de calendário, e o custo cai sobre quem for ler o gate meses depois. */
const emDias = (d: number) => new Date(Date.now() + d * 86_400_000).toISOString();

describe("derivarHome", () => {
  it("ordena minhas proposições mais-recente-primeiro", () => {
    const painel: MeuPainelOut = {
      proposicoes: [
        proposicao({ id: "antiga", atualizadoEm: "2026-01-01T00:00:00Z" }),
        proposicao({ id: "recente", atualizadoEm: "2026-06-01T00:00:00Z" }),
      ],
      pareceres: [],
      ciencias: [],
    };
    const r = derivarHome(painel, []);
    expect(r.minhasProposicoes.map((p) => p.id)).toEqual(["recente", "antiga"]);
  });

  it("separa pareceres por estado: aguardando vs concluído (os 4 terminais)", () => {
    const painel: MeuPainelOut = {
      proposicoes: [],
      pareceres: [
        parecer({ id: "aguardando", estado: "com_relator" }),
        parecer({ id: "aprovado", estado: "aprovado" }),
        parecer({ id: "rejeitado", estado: "rejeitado" }),
        parecer({ id: "prejudicado", estado: "prejudicado" }),
        parecer({ id: "prazo-vencido", estado: "prazo_vencido" }),
      ],
      ciencias: [],
    };
    const r = derivarHome(painel, []);
    expect(r.meusPareceres.aguardando.map((p) => p.id)).toEqual(["aguardando"]);
    expect(r.meusPareceres.concluidos.map((p) => p.id)).toEqual([
      "aprovado",
      "rejeitado",
      "prejudicado",
      "prazo-vencido",
    ]);
  });

  it("ciências pendentes passam intactas (o marcador 'para sua ciência' é a lista não-vazia)", () => {
    const painel: MeuPainelOut = {
      proposicoes: [],
      pareceres: [],
      ciencias: [
        {
          parecerId: "pc1",
          proposicaoId: "p1",
          tipo: "pl",
          ano: 2026,
          sequencial: 1,
          urnLex: "urn:lex:fixture",
          ementa: "Ementa",
        },
      ],
    };
    const r = derivarHome(painel, []);
    expect(r.ciencias).toHaveLength(1);
    expect(r.ciencias[0].parecerId).toBe("pc1");
  });

  it("próxima sessão escolhe a de menor data FUTURA", () => {
    const sessoes = [
      sessao({ id: "mais-distante", agendadaPara: emDias(120) }),
      sessao({ id: "mais-proxima", agendadaPara: emDias(7) }),
    ];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.proximaSessao?.id).toBe("mais-proxima");
  });

  it("próxima sessão ignora datas passadas", () => {
    const sessoes = [sessao({ id: "passada", agendadaPara: emDias(-365) })];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.proximaSessao).toBeNull();
  });

  it("próxima sessão null quando não há sessão nenhuma", () => {
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, []);
    expect(r.proximaSessao).toBeNull();
  });

  it("próxima sessão ignora sessão sem agendada-para", () => {
    const sessoes = [sessao({ id: "sem-data", agendadaPara: null })];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.proximaSessao).toBeNull();
  });

  it("painel/sessoes ausentes (undefined) -> estrutura vazia coerente, sem lançar", () => {
    const r = derivarHome(undefined, undefined);
    expect(r).toEqual({
      minhasProposicoes: [],
      meusPareceres: { aguardando: [], concluidos: [] },
      ciencias: [],
      proximaSessao: null,
      sessaoAoVivo: null,
    });
  });

  it("painel/sessoes null -> estrutura vazia coerente, sem lançar", () => {
    const r = derivarHome(null, null);
    expect(r.minhasProposicoes).toEqual([]);
    expect(r.proximaSessao).toBeNull();
    expect(r.sessaoAoVivo).toBeNull();
  });

  // Defeito #16 (MATA): "há sessão agora?" tem de vir de `estado`, não de existir alguma sessão qualquer
  // na lista — uma sessão AGENDADA (a mesma que também aparece como `proximaSessao`) não é "agora".
  it("sessão AGENDADA não conta como sessão ao vivo", () => {
    const sessoes = [sessao({ id: "s-agendada", estado: "agendada", agendadaPara: emDias(5) })];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.sessaoAoVivo).toBeNull();
    expect(r.proximaSessao?.id).toBe("s-agendada");
  });

  it("sessão ABERTA é a sessão ao vivo", () => {
    const sessoes = [
      sessao({ id: "s-aberta", estado: "aberta", agendadaPara: null }),
      sessao({ id: "s-futura", estado: "agendada", agendadaPara: emDias(10) }),
    ];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.sessaoAoVivo?.id).toBe("s-aberta");
    // as duas coisas coexistem: há sessão agora E há uma próxima agendada — é exatamente o caso real do
    // defeito #16 (uma ABERTA com orador na tribuna + uma AGENDADA para 14/09/2026, ao mesmo tempo).
    expect(r.proximaSessao?.id).toBe("s-futura");
  });

  it("sessão SUSPENSA também é sessão ao vivo (a Mesa retoma sem reabrir)", () => {
    const sessoes = [sessao({ id: "s-suspensa", estado: "suspensa", agendadaPara: null })];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.sessaoAoVivo?.id).toBe("s-suspensa");
  });

  it("sessão ENCERRADA/ARQUIVADA não conta como ao vivo", () => {
    const sessoes = [
      sessao({ id: "s-encerrada", estado: "encerrada", agendadaPara: null }),
      sessao({ id: "s-arquivada", estado: "arquivada", agendadaPara: null }),
    ];
    const r = derivarHome({ proposicoes: [], pareceres: [], ciencias: [] }, sessoes);
    expect(r.sessaoAoVivo).toBeNull();
  });
});
