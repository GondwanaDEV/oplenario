import { describe, expect, it } from "vitest";
import { camelizarChaves } from "./boundary";
import {
  ROTAS_AUDIENCIA,
  corpoDaInscricaoCidada,
  corpoDaInscricaoPresencial,
  corpoDoAjuste,
  doFio,
  formaValida,
  hrefAtaNoPortal,
  rotuloEstadoAudiencia,
  rotuloEstadoInscricao,
  rotuloFalaComo,
  rotuloFinalidade,
  rotuloReferencia,
  type AudienciaOut,
  type AudienciaPublicaOut,
} from "./contrato-audiencia";

// O contrato da ADR-0021 (Parte A): as formas kebab do fio, camelizadas e acertadas por `doFio`, viram os tipos das telas.
const fioAudiencia = {
  "sessao-id": "s1",
  numero: 3,
  estado: "aberta",
  "agendada-para": "2026-06-11T12:00:00Z",
  modalidade: "presencial",
  comissao: { id: "c1", nome: "Comissão de Meio Ambiente" },
  tema: "Plano de mobilidade",
  finalidade: "tematica",
  "tempo-fala-segundos": 300,
  "inscricoes-abertas": true,
  inscricoes: [
    { id: "i1", protocolo: "AUD-2026-000001", ordem: 1, nome: "Ana", "fala-como": "individual", tema: "Ciclovias", origem: "portal_govbr", estado: "inscrita" },
  ],
};

describe("contrato da audiência pública", () => {
  it("a audiência da Mesa: opcionais ausentes viram null e a forma passa", () => {
    const a = doFio.audiencia(camelizarChaves(fioAudiencia)) as AudienciaOut;
    expect(formaValida.audiencia(a)).toBe(true);
    expect(a.local).toBeNull();
    expect(a.proposicao).toBeNull();
    expect(a.referencia).toBeNull();
    expect(a.inscricoes[0]).toMatchObject({ falaComo: "individual", entidade: null, chamadaEm: null, tempoUsadoSegundos: null });
  });

  it("comissão chegando achatada (`comissao-id` + `comissao-nome`) vira o objeto da ADR", () => {
    const { comissao: _c, ...semObjeto } = fioAudiencia;
    void _c;
    const a = doFio.audiencia(camelizarChaves({ ...semObjeto, "comissao-id": "c9", "comissao-nome": "Finanças" })) as AudienciaOut;
    expect(a.comissao).toEqual({ id: "c9", nome: "Finanças" });
  });

  it("corpo que não bate é recusado (fail-closed)", () => {
    expect(formaValida.audiencia({})).toBe(false);
    expect(formaValida.audiencia(doFio.audiencia({ sessaoId: "s", tema: "t", tempoFalaSegundos: 300, inscricoes: [{ nome: "x" }] }))).toBe(false);
    expect(formaValida.publicas({ proximas: [] })).toBe(false);
    expect(formaValida.recibo({ protocolo: "AUD-2026-000001" })).toBe(false);
    expect(formaValida.minhas({})).toBe(false);
  });

  it("a página pública: contagem, ata e falas com padrão honesto quando ausentes", () => {
    const p = doFio.publica(
      camelizarChaves({ "sessao-id": "s1", tema: "Mobilidade", "comissao-nome": "Meio Ambiente", estado: "agendada", finalidade: "tematica",
        modalidade: "presencial", "tempo-fala-segundos": 300, "inscricoes-abertas": true }),
    ) as AudienciaPublicaOut;
    expect(formaValida.publica(p)).toBe(true);
    expect(p).toMatchObject({ inscritos: 0, ataPublicada: false, falaram: [], local: null, proposicao: null });
  });

  it("a lista pública e as minhas inscrições: listas ausentes viram vazias", () => {
    expect(doFio.publicas({ proximas: [{ sessaoId: "s", comissao: { nome: "Saúde" } }] })).toEqual({
      proximas: [{ sessaoId: "s", comissao: { nome: "Saúde" }, comissaoNome: "Saúde", local: null, agendadaPara: null }],
      realizadas: [],
    });
    expect(formaValida.minhas(doFio.minhas({}))).toBe(true);
  });

  it("os corpos de escrita saem em kebab, sem entidade para quem fala por si", () => {
    expect(corpoDaInscricaoCidada({ falaComo: "individual", entidade: "ignorada", tema: "  Ciclovias  " })).toEqual({
      "fala-como": "individual",
      tema: "Ciclovias",
      "ciente-publicidade": true,
    });
    expect(corpoDaInscricaoPresencial({ nome: " Ana ", falaComo: "entidade", entidade: " ACB ", tema: "Ônibus" })).toEqual({
      nome: "Ana",
      "fala-como": "entidade",
      entidade: "ACB",
      tema: "Ônibus",
    });
    expect(corpoDoAjuste({ inscricoesAbertas: false })).toEqual({ "inscricoes-abertas": false });
    expect(corpoDoAjuste({ tempoFalaSegundos: 180, local: "Plenário" })).toEqual({ "tempo-fala-segundos": 180, local: "Plenário" });
  });

  it("as rotas da ADR, com cada segmento codificado", () => {
    expect(ROTAS_AUDIENCIA.audiencia("s1")).toBe("/api/sessoes/s1/audiencia");
    expect(ROTAS_AUDIENCIA.chamada("s1", "i1")).toBe("/api/sessoes/s1/audiencia/inscricoes/i1/chamada");
    expect(ROTAS_AUDIENCIA.encerramento("s1", "i1")).toBe("/api/sessoes/s1/audiencia/inscricoes/i1/encerramento");
    expect(ROTAS_AUDIENCIA.ausencia("s1", "i1")).toBe("/api/sessoes/s1/audiencia/inscricoes/i1/ausencia");
    expect(ROTAS_AUDIENCIA.inscreverCidada("s/1")).toBe("/portal/audiencias/s%2F1/inscricoes");
    expect(ROTAS_AUDIENCIA.minhas).toBe("/api/portal/minhas-inscricoes");
    expect(ROTAS_AUDIENCIA.desistencia("i1")).toBe("/portal/minhas-inscricoes/i1/desistencia");
    expect(ROTAS_AUDIENCIA.publica("e", "s1")).toEqual(["e", "audiencias", "s1"]);
    expect(hrefAtaNoPortal("e", "s1")).toBe("/portal/casa/e/atas?sessao=s1");
  });

  it("os rótulos dizem em palavras; chave desconhecida sai crua, nunca some", () => {
    expect(rotuloFinalidade("metas_fiscais")).toMatch(/metas fiscais/i);
    expect(rotuloFalaComo("conselho_movimento")).toBe("Conselho ou movimento");
    expect(rotuloEstadoInscricao("inscrita")).toBe("Na fila para falar");
    expect(rotuloEstadoInscricao("novo")).toBe("novo");
    expect(rotuloEstadoAudiencia("encerrada")).toBe("Realizada");
    expect(rotuloReferencia("2026-Q1")).toBe("1º quadrimestre de 2026 (jan–abr)");
    expect(rotuloReferencia("2026-Q3")).toBe("3º quadrimestre de 2026 (set–dez)");
    expect(rotuloReferencia("2026-T1")).toBe("2026-T1");
    expect(rotuloReferencia(null)).toBeNull();
  });
});
