import { describe, expect, it } from "vitest";
import { derivarCentral, diasAte, escolherFoco, sessoesParaDetalhar, LIMITE_LEGAIS, type EntradaCentral } from "./central-vista";
import type { SessaoOut } from "./contrato-sessoes.gen";

// docs/23 Fatia 3 — a Central da Casa: foco na sessão do dia, trilha com UMA ação por etapa, fila de trabalho só
// com sinais que têm API, próximas sessões com prontidão. `agora` = 24/09/2026 09:00 em Fortaleza (UTC−3).

const AGORA = "2026-09-24T12:00:00Z";

function sessao(id: string, parcial: Partial<SessaoOut>): SessaoOut {
  return {
    id, sessaoLegislativaId: "sl", tipoSessao: "ordinaria", numeroSequencial: 15, estado: "agendada",
    modalidade: "presencial", delibera: true, transmitePublica: true, geraAtaRegimental: true,
    permiteVotoSecreto: false, permiteModalidadeRemota: false, agendadaPara: null, abertaEm: null,
    encerradaEm: null, motivoNaoRealizada: null, lockVersion: 0, ...parcial,
  };
}

function entrada(parcial: Partial<EntradaCentral> = {}): EntradaCentral {
  return {
    agoraIso: AGORA, nome: "Rita Campos", sessoes: [], estadoSessoes: "pronto", detalhes: {},
    pendencias: { itens: [], total: 0 }, compliance: { itens: [], total: 0 }, moderacao: [], tramitacao: [],
    ...parcial,
  };
}

const HOJE_14H = "2026-09-24T17:00:00Z"; // 14h em Fortaleza
const ONTEM = "2026-09-23T12:00:00Z";

describe("escolherFoco", () => {
  it("a viva vence tudo", () => {
    const viva = sessao("v", { estado: "suspensa", abertaEm: ONTEM });
    expect(escolherFoco([sessao("h", { agendadaPara: HOJE_14H }), viva], AGORA)?.id).toBe("v");
  });

  it("sem viva: a agendada de hoje (mesmo com a hora já passada) antes da encerrada de hoje", () => {
    const atrasada = sessao("a", { agendadaPara: "2026-09-24T11:00:00Z" }); // 8h, já passou
    const encerradaHoje = sessao("e", { estado: "encerrada", abertaEm: "2026-09-24T10:00:00Z", encerradaEm: "2026-09-24T11:30:00Z" });
    expect(escolherFoco([encerradaHoje, atrasada], AGORA)?.id).toBe("a");
    expect(escolherFoco([encerradaHoje], AGORA)?.id).toBe("e");
  });

  it("senão a próxima agendada; cancelada (nao_realizada) nunca é foco", () => {
    const cancelada = sessao("c", { estado: "nao_realizada", agendadaPara: "2026-09-25T12:00:00Z" });
    const prox = sessao("p", { agendadaPara: "2026-09-29T12:00:00Z" });
    expect(escolherFoco([cancelada, prox], AGORA)?.id).toBe("p");
    expect(escolherFoco([cancelada], AGORA)).toBeNull();
  });

  it("encerrada de ontem não é foco (vai para a fila)", () => {
    expect(escolherFoco([sessao("e", { estado: "encerrada", abertaEm: ONTEM, encerradaEm: ONTEM })], AGORA)).toBeNull();
  });
});

describe("sessoesParaDetalhar", () => {
  it("pede só o que a tela mostra de cada sessão", () => {
    const sessoes = [
      sessao("foco", { agendadaPara: HOJE_14H }),
      sessao("p1", { agendadaPara: "2026-09-29T12:00:00Z" }),
      sessao("e1", { estado: "encerrada", abertaEm: ONTEM, encerradaEm: ONTEM }),
    ];
    expect(sessoesParaDetalhar(sessoes, AGORA)).toEqual([
      { sessaoId: "foco", pauta: true, folhas: false, justificativas: false },
      { sessaoId: "p1", pauta: true, folhas: false, justificativas: false },
      { sessaoId: "e1", pauta: false, folhas: true, justificativas: true },
    ]);
  });

  it("acompanha só as 3 encerradas mais recentes e as 3 próximas", () => {
    const sessoes = [
      ...[1, 2, 3, 4].map((i) => sessao(`e${i}`, { estado: "encerrada", encerradaEm: `2026-09-0${i}T12:00:00Z` })),
      ...[5, 6, 7, 8].map((i) => sessao(`p${i}`, { agendadaPara: `2026-10-0${i}T12:00:00Z` })),
    ];
    const ids = sessoesParaDetalhar(sessoes, AGORA).map((a) => a.sessaoId);
    // p5 é o foco (próxima); p6..p8 são as próximas; e4..e2 as encerradas recentes
    expect(ids.sort()).toEqual(["e2", "e3", "e4", "p5", "p6", "p7", "p8"]);
  });
});

describe("derivarCentral — a trilha e a ação da etapa atual", () => {
  const acoes = (e: EntradaCentral) => {
    const v = derivarCentral(e);
    if (v.foco.situacao !== "sessao") throw new Error(v.foco.situacao);
    return v.foco.foco;
  };

  it("agendada hoje com pauta vazia → Montar a pauta (etapa atual = Pauta)", () => {
    const f = acoes(entrada({ sessoes: [sessao("s", { agendadaPara: HOJE_14H })], detalhes: { s: { itensPauta: 0 } } }));
    expect(f.chamada).toBe("Sessão de hoje");
    expect(f.principal).toEqual({ rotulo: "Montar a pauta", href: "/pauta-convocacao?sessao=s" });
    expect(f.etapas.map((e) => [e.chave, e.estado, e.detalhe])).toEqual([
      ["agendada", "feita", "24/09"],
      ["pauta", "atual", "vazia"],
      ["em-curso", "pendente", null],
      ["encerrada", "pendente", null],
      ["folha", "pendente", null],
    ]);
  });

  it("agendada com pauta montada → Abrir no Comando da Mesa, com pauta e Modo TV de apoio", () => {
    const f = acoes(entrada({ sessoes: [sessao("s", { agendadaPara: HOJE_14H })], detalhes: { s: { itensPauta: 8 } } }));
    expect(f.principal).toEqual({ rotulo: "Abrir no Comando da Mesa", href: "/sessoes/s/conduzir" });
    expect(f.secundarias.map((a) => a.rotulo)).toEqual(["Ver pauta e convocação", "Modo TV"]);
    expect(f.etapas[1]).toMatchObject({ estado: "feita", detalhe: "8 itens" });
    expect(f.etapas[2]).toMatchObject({ estado: "atual", detalhe: "a abrir" });
    expect(f.quando).toBe("24/09/2026 · 14h · presencial");
  });

  it("aberta → Conduzir, com chamada, TV e telão; selo ao vivo", () => {
    const f = acoes(entrada({ sessoes: [sessao("s", { estado: "aberta", agendadaPara: AGORA, abertaEm: AGORA })], detalhes: { s: { itensPauta: 3, justificativasPendentes: 0 } } }));
    expect(f.aoVivo).toBe(true);
    expect(f.chamada).toBe("Ao vivo");
    expect(f.principal.href).toBe("/sessoes/s/conduzir");
    expect(f.secundarias.map((a) => a.href)).toEqual(["/sessoes/s/chamada", "/sessoes/s/tv", "/sessoes/s/plenario"]);
    expect(f.etapas[2]).toMatchObject({ estado: "atual", detalhe: "ao vivo" });
  });

  it("encerrada hoje sem folha → Gerar a folha; com folha → Ver a folha", () => {
    const s = sessao("s", { estado: "encerrada", abertaEm: "2026-09-24T10:00:00Z", encerradaEm: "2026-09-24T11:30:00Z" });
    const sem = acoes(entrada({ sessoes: [s], detalhes: { s: { itensPauta: 4, folhas: 0, justificativasPendentes: 0 } } }));
    expect(sem.principal).toEqual({ rotulo: "Gerar a folha", href: "/sessoes/s/folha" });
    expect(sem.etapas.map((e) => e.estado)).toEqual(["feita", "feita", "feita", "feita", "atual"]);
    const com = acoes(entrada({ sessoes: [s], detalhes: { s: { itensPauta: 4, folhas: 2, justificativasPendentes: 0 } } }));
    expect(com.principal.rotulo).toBe("Ver a folha");
    expect(com.etapas[4]).toMatchObject({ estado: "feita", detalhe: "2 versões" });
  });

  it("pauta que não carregou: não finge que está montada nem vazia", () => {
    const f = acoes(entrada({ sessoes: [sessao("s", { agendadaPara: HOJE_14H })], detalhes: { s: { itensPauta: null } } }));
    expect(f.principal.rotulo).toBe("Abrir no Comando da Mesa");
    expect(f.porque).toMatch(/Não foi possível conferir a pauta/);
    expect(f.etapas[1]).toMatchObject({ estado: "pendente", detalhe: null });
  });

  it("segura a trilha enquanto o detalhe da sessão em foco não chegou", () => {
    expect(derivarCentral(entrada({ sessoes: [sessao("s", { agendadaPara: HOJE_14H })] })).foco).toEqual({ situacao: "carregando" });
  });

  it("sem sessão: oferece agendar; sessões carregando/erro nunca viram 'nenhuma'", () => {
    expect(derivarCentral(entrada()).foco).toEqual({ situacao: "nenhuma", acao: { rotulo: "Agendar sessão", href: "/agendar-sessao" } });
    expect(derivarCentral(entrada({ sessoes: null, estadoSessoes: "carregando" })).foco.situacao).toBe("carregando");
    expect(derivarCentral(entrada({ sessoes: null, estadoSessoes: "erro" })).foco.situacao).toBe("erro");
  });
});

describe("derivarCentral — fila de trabalho", () => {
  it("prazos legais de e-SIC e TCE juntos, por vencimento, com prazo em palavras", () => {
    const v = derivarCentral(entrada({
      pendencias: { itens: [{ objetoTipo: "pedido_esic", objetoId: "e1", protocolo: "2026/0112", venceEm: "2026-09-26T23:59:00Z" }], total: 1 },
      compliance: { itens: [
        { id: "o1", templateChave: "balancete-mensal", venceEm: "2026-09-29" },
        { id: "o2", templateChave: "rgf", venceEm: "2026-09-20" },
      ], total: 2 },
    }));
    expect(v.fila.itens.map((i) => [i.id, i.prazo?.texto])).toEqual([
      ["tce-o2", "venceu há 4 dias"],
      ["pend-pedido_esic-e1", "vence em 2 dias"],
      ["tce-o1", "vence em 5 dias"],
    ]);
    expect(v.fila.itens[1]).toMatchObject({ titulo: "Pedido e-SIC 2026/0112", gravidade: "legal", acao: { href: "/paineis/mesa" } });
    expect(v.fila.itens[0].prazo?.atrasado).toBe(true);
  });

  it(`corta os prazos legais em ${LIMITE_LEGAIS} e conta o resto pelo total AUTORITATIVO do servidor`, () => {
    const itens = Array.from({ length: 7 }, (_, i) => ({ id: `o${i}`, templateChave: "t", venceEm: `2026-10-0${i + 1}` }));
    const v = derivarCentral(entrada({ compliance: { itens, total: 12 } }));
    expect(v.fila.itens).toHaveLength(LIMITE_LEGAIS);
    expect(v.fila.legaisOcultos).toBe(12 - LIMITE_LEGAIS);
  });

  it("folha a gerar das encerradas recentes; justificativas pendentes de qualquer sessão acompanhada", () => {
    const e1 = sessao("e1", { numeroSequencial: 14, estado: "encerrada", abertaEm: ONTEM, encerradaEm: "2026-09-17T15:00:00Z" });
    const e2 = sessao("e2", { numeroSequencial: 13, estado: "encerrada", encerradaEm: "2026-09-10T15:00:00Z" });
    const v = derivarCentral(entrada({
      sessoes: [e1, e2],
      detalhes: { e1: { folhas: 0, justificativasPendentes: 2 }, e2: { folhas: 1, justificativasPendentes: 0 } },
    }));
    expect(v.fila.itens).toEqual([
      expect.objectContaining({ id: "folha-e1", titulo: "Gerar a folha da 14ª sessão ordinária", contexto: "encerrada em 17/09/2026, ainda sem folha", acao: { rotulo: "Gerar folha", href: "/sessoes/e1/folha" } }),
      expect.objectContaining({ id: "just-e1", titulo: "2 justificativas de ausência a decidir", contexto: "14ª sessão ordinária", acao: { rotulo: "Decidir", href: "/sessoes/e1/chamada" } }),
    ]);
  });

  it("a folha da sessão em foco não se repete na fila (a ação já está na trilha)", () => {
    const s = sessao("s", { estado: "encerrada", abertaEm: "2026-09-24T10:00:00Z", encerradaEm: "2026-09-24T11:30:00Z" });
    const v = derivarCentral(entrada({ sessoes: [s], detalhes: { s: { itensPauta: 1, folhas: 0, justificativasPendentes: 1 } } }));
    expect(v.fila.itens.map((i) => i.id)).toEqual(["just-s"]);
  });

  it("moderação: total e denunciados", () => {
    const v = derivarCentral(entrada({ moderacao: [{ id: "c1", denunciado: true }, { id: "c2", denunciado: false }, { id: "c3", denunciado: false }] }));
    expect(v.fila.itens).toEqual([expect.objectContaining({ titulo: "3 comentários do portal para moderar", contexto: "1 denunciado", acao: { rotulo: "Moderar", href: "/moderacao" } })]);
  });

  it("fonte que falhou é dita, não vira fila vazia; fonte em voo marca carregando", () => {
    const v = derivarCentral(entrada({ pendencias: null, moderacao: undefined }));
    expect(v.fila.indisponiveis).toEqual(["prazos de e-SIC, LGPD e ouvidoria"]);
    expect(v.fila.carregando).toBe(true);
    expect(v.lede.map((t) => t.texto).join("")).not.toMatch(/Nada pendente/);
  });
});

describe("derivarCentral — próximas sessões, prontas p/ pauta, cabeçalho", () => {
  it("próximas (fora do foco) com prontidão da pauta; vazia vira alerta e pede para montar", () => {
    const v = derivarCentral(entrada({
      sessoes: [
        sessao("foco", { agendadaPara: HOJE_14H }),
        sessao("p1", { numeroSequencial: 16, agendadaPara: "2026-09-29T12:00:00Z" }),
        sessao("p2", { tipoSessao: "extraordinaria", numeroSequencial: 1, agendadaPara: "2026-10-01T18:00:00Z" }),
        sessao("p3", { numeroSequencial: 17, agendadaPara: "2026-10-06T12:00:00Z" }),
      ],
      detalhes: { foco: { itensPauta: 8 }, p1: { itensPauta: 5 }, p2: { itensPauta: 0 }, p3: { itensPauta: null } },
    }));
    expect(v.proximas.map((p) => [p.titulo, p.pauta.tom, p.pauta.texto, p.acao.rotulo])).toEqual([
      ["16ª sessão ordinária", "ok", "Pauta: 5 itens", "Ver pauta"],
      ["1ª sessão extraordinária", "alerta", "Pauta vazia", "Montar a pauta"],
      ["17ª sessão ordinária", "neutro", "Pauta: não carregou", "Ver pauta"],
    ]);
    expect(v.proximas[1].acao.href).toBe("/pauta-convocacao?sessao=p2");
  });

  it("prontas p/ pauta soma os estados da coluna pelo total autoritativo; quadro falho → null", () => {
    expect(derivarCentral(entrada({ tramitacao: [{ estado: "aguardando_pauta", total: 3 }, { estado: "em_pauta", total: 1 }, { estado: "em_comissoes", total: 9 }] })).prontasParaPauta).toBe(4);
    expect(derivarCentral(entrada({ tramitacao: null })).prontasParaPauta).toBeNull();
  });

  it("saudação no relógio da Casa, pelo primeiro nome; lede situacional", () => {
    const v = derivarCentral(entrada({
      sessoes: [sessao("s", { agendadaPara: HOJE_14H })], detalhes: { s: { itensPauta: 8 } },
      moderacao: [{ id: "c", denunciado: false }],
    }));
    expect(v.saudacao).toBe("Bom dia, Rita.");
    expect(v.hoje).toBe("quinta-feira, 24 de setembro");
    expect(v.lede.map((t) => t.texto).join("")).toBe("A 15ª sessão ordinária é hoje, às 14h, com a pauta montada. 1 item pede você.");
    expect(derivarCentral(entrada({ agoraIso: "2026-09-24T22:00:00Z", nome: null })).saudacao).toBe("Boa noite.");
  });

  it("tudo em dia: diz que não há nada pendente", () => {
    expect(derivarCentral(entrada()).lede.map((t) => t.texto).join("")).toBe("A Casa não tem sessão marcada. Nada pendente na fila.");
  });
});

describe("diasAte", () => {
  it("conta dias de calendário no fuso da Casa (date-only e instante)", () => {
    expect(diasAte("2026-09-24", AGORA)).toBe(0);
    expect(diasAte("2026-09-25T02:00:00Z", AGORA)).toBe(0); // 23h de 24/09 em Fortaleza
    expect(diasAte("2026-09-23", AGORA)).toBe(-1);
  });
});
