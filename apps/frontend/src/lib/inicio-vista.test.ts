import { describe, expect, it } from "vitest";
import { derivarInicio } from "./inicio-vista";
import type { SessaoOut } from "./contrato-sessoes.gen";

const AGORA = "2026-09-21T12:00:00Z";

function sessao(over: Partial<SessaoOut> & { id: string; estado: SessaoOut["estado"] }): SessaoOut {
  return {
    sessaoLegislativaId: "sl1",
    tipoSessao: "ordinaria",
    numeroSequencial: 1,
    modalidade: "presencial",
    delibera: true,
    transmitePublica: true,
    geraAtaRegimental: true,
    permiteVotoSecreto: false,
    permiteModalidadeRemota: false,
    lockVersion: 0,
    ...over,
  } as SessaoOut;
}

const ABERTA = sessao({ id: "s-viva", estado: "aberta", numeroSequencial: 2 });
const AGENDADA = sessao({ id: "s-prox", estado: "agendada", agendadaPara: "2026-09-28T09:00:00Z", numeroSequencial: 3 });

const base = { estadoSessoes: "pronto" as const, sessoes: [] as SessaoOut[], agoraIso: AGORA };

describe("derivarInicio — persona", () => {
  it("secretario vence (mesmo acumulando vereador)", () => {
    expect(derivarInicio({ ...base, papeis: ["vereador", "secretario"] }).persona).toBe("secretaria");
  });

  it("só vereador -> persona vereador", () => {
    expect(derivarInicio({ ...base, papeis: ["vereador"] }).persona).toBe("vereador");
  });

  it("sem papel de trabalho -> sem-area (o cidadão não tem escritório)", () => {
    expect(derivarInicio({ ...base, papeis: [] }).persona).toBe("sem-area");
  });
});

describe("derivarInicio — o bloco da sessão", () => {
  it("enquanto as sessões carregam, NÃO afirma que não há sessão", () => {
    const v = derivarInicio({ ...base, papeis: ["secretario"], estadoSessoes: "carregando" });
    expect(v.sessao.situacao).toBe("carregando");
    expect(v.sessao.acoes).toEqual([]);
  });

  it("erro de carga é distinto de 'nenhuma'", () => {
    const v = derivarInicio({ ...base, papeis: ["secretario"], estadoSessoes: "erro" });
    expect(v.sessao.situacao).toBe("erro");
  });

  it("sessão viva -> ao-vivo, e a ação primária da secretaria é o Comando da Mesa", () => {
    const v = derivarInicio({ ...base, papeis: ["secretario"], sessoes: [ABERTA, AGENDADA] });
    expect(v.sessao.situacao).toBe("ao-vivo");
    expect(v.sessao.sessaoId).toBe("s-viva");
    const primaria = v.sessao.acoes.find((a) => a.tom === "primaria");
    expect(primaria?.href).toBe("/sessoes/s-viva/conduzir");
    // o telão e a chamada da MESMA sessão viva também são oferecidos (hoje só se chega neles por URL)
    expect(v.sessao.acoes.map((a) => a.href)).toContain("/sessoes/s-viva/plenario");
    expect(v.sessao.acoes.map((a) => a.href)).toContain("/sessoes/s-viva/chamada");
  });

  it("sessão suspensa também conta como ao vivo (reusa sessao-corrente)", () => {
    const suspensa = sessao({ id: "s-pausa", estado: "suspensa" });
    const v = derivarInicio({ ...base, papeis: ["secretario"], sessoes: [suspensa] });
    expect(v.sessao.situacao).toBe("ao-vivo");
    expect(v.sessao.sessaoId).toBe("s-pausa");
  });

  it("vereador com sessão viva -> a ação primária é votar, não conduzir", () => {
    const v = derivarInicio({ ...base, papeis: ["vereador"], sessoes: [ABERTA] });
    const primaria = v.sessao.acoes.find((a) => a.tom === "primaria");
    expect(primaria?.href).toBe("/votar");
    expect(v.sessao.acoes.map((a) => a.href)).not.toContain("/sessoes/s-viva/conduzir");
  });

  it("sem sessão viva, mas com agendada futura -> agendada + atalho da pauta", () => {
    const v = derivarInicio({ ...base, papeis: ["secretario"], sessoes: [AGENDADA] });
    expect(v.sessao.situacao).toBe("agendada");
    expect(v.sessao.sessaoId).toBe("s-prox");
    expect(v.sessao.acoes.map((a) => a.href)).toContain("/pauta-convocacao");
  });

  it("nenhuma sessão -> convida a agendar (a secretaria tem a tela)", () => {
    const v = derivarInicio({ ...base, papeis: ["secretario"] });
    expect(v.sessao.situacao).toBe("nenhuma");
    expect(v.sessao.acoes.map((a) => a.href)).toContain("/agendar-sessao");
  });

  it("o rótulo usa português correto, não o enum cru do backend", () => {
    const v = derivarInicio({ ...base, papeis: ["secretario"], sessoes: [ABERTA] });
    expect(v.sessao.detalhe).toContain("ordinária"); // não "ordinaria"
    expect(v.sessao.detalhe).toContain("2ª sessão");
  });

  it("a sessão agendada informa a data — é o que se quer saber dela", () => {
    const v = derivarInicio({ ...base, papeis: ["secretario"], sessoes: [AGENDADA] });
    expect(v.sessao.detalhe).toMatch(/28\/09/);
  });

  it("agendada no PASSADO não vira 'próxima'", () => {
    const velha = sessao({ id: "s-velha", estado: "agendada", agendadaPara: "2026-09-01T09:00:00Z" });
    const v = derivarInicio({ ...base, papeis: ["secretario"], sessoes: [velha] });
    expect(v.sessao.situacao).toBe("nenhuma");
  });
});

describe("derivarInicio — atalhos", () => {
  it("a secretaria vê as áreas de trabalho, incluindo as que hoje só se alcança por URL", () => {
    const hrefs = derivarInicio({ ...base, papeis: ["secretario"] }).atalhos.map((a) => a.href);
    for (const esperado of ["/proposicoes", "/tramitacao", "/expediente", "/pauta-convocacao", "/agendar-sessao", "/cadastros/vereadores", "/calendario", "/moderacao", "/paineis/mesa"]) {
      expect(hrefs).toContain(esperado);
    }
  });

  it("o vereador é mandado para a área dele, não para o escritório da secretaria", () => {
    const hrefs = derivarInicio({ ...base, papeis: ["vereador"] }).atalhos.map((a) => a.href);
    expect(hrefs).toContain("/vereador");
    expect(hrefs).not.toContain("/paineis/mesa");
  });

  it("sem papel de trabalho -> nenhum atalho de escritório", () => {
    const v = derivarInicio({ ...base, papeis: [] });
    expect(v.atalhos.every((a) => !a.href.startsWith("/paineis"))).toBe(true);
  });
});
