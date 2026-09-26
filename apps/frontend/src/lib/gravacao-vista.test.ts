import { describe, expect, it } from "vitest";
import { duracao, nomeFonte, rotuloSessao, sessoesVinculaveis, vistaGravacao } from "./gravacao-vista";
import type { SessaoOut } from "./contrato-sessoes.gen";

describe("gravacao-vista", () => {
  it("duração em palavras curtas, ou null sem fim", () => {
    expect(duracao("2026-09-22T18:00:00Z", "2026-09-22T18:42:00Z")).toBe("42 min");
    expect(duracao("2026-09-22T18:00:00Z", "2026-09-22T20:00:00Z")).toBe("2 h");
    expect(duracao("2026-09-22T18:00:00Z", "2026-09-22T21:10:00Z")).toBe("3 h 10 min");
    expect(duracao("2026-09-22T18:00:00Z", null)).toBeNull();
    expect(duracao("2026-09-22T18:00:00Z", "2026-09-22T17:00:00Z")).toBeNull();
    expect(duracao("2026-09-10T13:00:00Z", "2026-09-26T20:21:00Z")).toBeNull(); // 16 dias: não é uma sessão
  });

  it("fonte em português; desconhecida aparece crua em vez de sumir", () => {
    expect(nomeFonte("gravacao_local_pos_sessao")).toBe("Gravação local");
    expect(nomeFonte("nova")).toBe("nova");
  });

  it("rótulo da sessão com acento e data", () => {
    expect(rotuloSessao({ tipoSessao: "extraordinaria", numeroSequencial: 3 })).toBe("Sessão extraordinária nº 3");
    expect(rotuloSessao({ tipoSessao: "ordinaria", numeroSequencial: 12, inicio: "2026-09-22T21:00:00Z" })).toMatch(
      /^Sessão ordinária nº 12 · 22\/09\/2026, \d\d:\d\d$/,
    );
  });

  it("sessões vinculáveis: sem a não realizada, mais recente primeiro", () => {
    const s = (id: string, estado: SessaoOut["estado"], abertaEm: string | null, agendadaPara: string | null = null) =>
      ({ id, estado, tipoSessao: "ordinaria", numeroSequencial: 1, abertaEm, agendadaPara }) as SessaoOut;
    const r = sessoesVinculaveis([
      s("a", "encerrada", "2026-09-01T21:00:00Z"),
      s("b", "nao_realizada", null, "2026-09-10T21:00:00Z"),
      s("c", "agendada", null, "2026-09-29T21:00:00Z"),
    ]);
    expect(r.map((x) => x.id)).toEqual(["c", "a"]);
  });

  it("vista sem id cru na tela", () => {
    const v = vistaGravacao({
      id: "0000-uuid",
      iniciouEm: "2026-09-22T20:50:00Z",
      encerrouEm: null,
      fonteIngestao: "importacao_legado",
      acessoRestrito: false,
      lockVersion: 0,
      sugestao: null,
    });
    expect(v.titulo).not.toContain("0000-uuid");
    expect(v.detalhe).toBe("Arquivo histórico");
    expect(v.sugestao).toBeNull();
  });
});
