import { describe, expect, it } from "vitest";
import { situacaoDoDesfecho } from "./desfecho-vista";
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
