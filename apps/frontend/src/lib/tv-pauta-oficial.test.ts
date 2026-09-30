import { describe, expect, it } from "vitest";
import { pautaOficialTv } from "./tv-vista";
import { formatarData, formatarHora } from "./formatar-data";

// ADR-0019 fatia 3: a TV diz qual é a pauta oficial (a versão publicada) — ou que ainda não foi publicada — e avisa
// quando a pauta mudou em plenário depois da publicação.

const T = "2026-10-06T17:30:00Z";
const base = { "sessao-id": "s1", itens: [] };

describe("pautaOficialTv", () => {
  it("sem pauta carregada, nada; sem publicação, diz honestamente", () => {
    expect(pautaOficialTv(null)).toBeNull();
    expect(pautaOficialTv(base)).toBe("Pauta ainda não publicada");
  });
  it("com publicação: versão e quando; alterada em plenário depois", () => {
    const oficial = `Pauta oficial · v3 publicada em ${formatarData(T)} às ${formatarHora(T)}`;
    expect(pautaOficialTv({ ...base, publicacao: { versao: 3, "publicada-em": T, "alterada-desde": false } })).toBe(oficial);
    expect(pautaOficialTv({ ...base, publicacao: { versao: 3, "publicada-em": T, "alterada-desde": true } })).toBe(
      `${oficial} · alterada em plenário desde então`,
    );
  });
});
