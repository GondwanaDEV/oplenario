// O fuso da CASA vs. o fuso de QUEM CONSULTA — o único teste da fatia que pode reprovar a omissão do
// `timeZone`.
//
// Por que num arquivo separado: `vitest.config.ts` crava `TZ=America/Fortaleza` no runner, e os
// `Intl.DateTimeFormat` de calendario-vista.ts são constantes de MÓDULO, construídas no import. Rodando
// sob o fuso da própria Casa, toda asserção de dia passa por acidente — com ou sem `timeZone` explícito.
// Aqui o módulo é recarregado (`vi.resetModules()` + import dinâmico) sob um TZ de processo DIFERENTE do
// da Casa: sem `timeZone: FUSO_DA_CASA` nos formatadores, o `Intl` cai no fuso do ambiente e estas
// asserções reprovam. É a mesma classe de defeito já registrada no repo (formatarData recuando um dia).
import { afterEach, describe, expect, it, vi } from "vitest";

const TZ_DO_RUNNER = process.env.TZ;

/** Recarrega o módulo com o processo em `tz` — o análogo, em teste, de "o navegador do usuário está em". */
async function sobOFusoDoUsuario(tz: string) {
  process.env.TZ = tz;
  vi.resetModules();
  return import("./calendario-vista");
}

afterEach(() => {
  process.env.TZ = TZ_DO_RUNNER;
  vi.resetModules();
});

describe("o dia da célula é o da CASA, não o do dispositivo de quem consulta", () => {
  // 2026-06-25T02:30Z = 24/06 23h30 em Fortaleza (UTC−3) e 25/06 03h30 em Lisboa (UTC+1).
  const SESSAO_23H30_DE_24 = "2026-06-25T02:30:00Z";

  it("de Lisboa (UTC+1), a sessão das 23h30 de 24/06 continua no dia 24, com a hora da Casa", async () => {
    const m = await sobOFusoDoUsuario("Europe/Lisbon");
    expect(m.FUSO_DA_CASA).toBe("America/Fortaleza");
    expect(m.diaLocal(SESSAO_23H30_DE_24)).toBe("2026-06-24");
    expect(m.horaLocal(SESSAO_23H30_DE_24)).toBe("23h30");
  });

  it("de Manaus (UTC−4), a sessão das 00h30 de 25/06 não recua para o dia 24", async () => {
    const m = await sobOFusoDoUsuario("America/Manaus");
    const madrugada = "2026-06-25T03:30:00Z"; // 00h30 de 25/06 em Fortaleza; 23h30 de 24/06 em Manaus
    expect(m.diaLocal(madrugada)).toBe("2026-06-25");
    expect(m.horaLocal(madrugada)).toBe("0h30");
  });

  it("a grade põe a sessão na célula do dia da Casa mesmo com o usuário do outro lado do Atlântico", async () => {
    const m = await sobOFusoDoUsuario("Europe/Lisbon");
    const v = m.derivarCalendario({
      ano: 2026,
      mes: 6,
      hoje: "2026-06-22",
      sessoes: [
        {
          id: "s1",
          sessaoLegislativaId: "sl-1",
          tipoSessao: "ordinaria",
          numeroSequencial: 15,
          estado: "agendada",
          modalidade: "presencial",
          delibera: true,
          transmitePublica: true,
          geraAtaRegimental: true,
          permiteVotoSecreto: false,
          permiteModalidadeRemota: false,
          agendadaPara: SESSAO_23H30_DE_24,
          abertaEm: null,
          encerradaEm: null,
          motivoNaoRealizada: null,
          lockVersion: 1,
        },
      ],
      obrigacoes: [],
    });
    expect(v.celulas.find((c) => c.iso === "2026-06-24")!.eventos.map((e) => e.id)).toEqual(["sessao:s1"]);
    expect(v.celulas.find((c) => c.iso === "2026-06-25")!.eventos).toEqual([]);
  });

  it("`hojeLocal` é o dia civil da Casa — quem abre de Lisboa às 03h30 ainda está no dia 24 da Casa", async () => {
    const m = await sobOFusoDoUsuario("Europe/Lisbon");
    expect(m.hojeLocal(new Date(SESSAO_23H30_DE_24))).toBe("2026-06-24");
  });

  it("`msAteViradaDoDia` conta até a meia-noite DA CASA, não a do usuário", async () => {
    const m = await sobOFusoDoUsuario("Europe/Lisbon");
    // 2026-06-24T15:00Z = 12h em Fortaleza -> faltam 12h exatas para a virada da Casa
    expect(m.msAteViradaDoDia(new Date("2026-06-24T15:00:00Z"))).toBe(12 * 3_600_000);
  });

  it("prazo é DATE-ONLY: recorte textual, imune ao fuso dos dois lados", async () => {
    const m = await sobOFusoDoUsuario("Europe/Lisbon");
    expect(m.diaLocal("2026-06-01")).toBe("2026-06-01");
    expect(m.horaLocal("2026-06-01")).toBeNull();
    expect(m.partesDoDia("2026-01-01")).toEqual({ numero: "1", mesCurto: "jan" });
  });
});
