import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { PainelInicio } from "./painel-inicio";
import { derivarInicio } from "@/lib/inicio-vista";
import type { SessaoOut } from "@/lib/contrato-sessoes.gen";

const AGORA = "2026-09-21T12:00:00Z";

const ABERTA = {
  id: "s-viva",
  estado: "aberta",
  sessaoLegislativaId: "sl1",
  tipoSessao: "ordinaria",
  numeroSequencial: 2,
  modalidade: "presencial",
  delibera: true,
  transmitePublica: true,
  geraAtaRegimental: true,
  permiteVotoSecreto: false,
  permiteModalidadeRemota: false,
  lockVersion: 0,
} as SessaoOut;

const href = (nome: RegExp) => screen.getByRole("link", { name: nome }).getAttribute("href");

afterEach(() => cleanup());

describe("PainelInicio", () => {
  it("sessão viva: oferece o Comando da Mesa e as telas que só se alcançava por URL", () => {
    const vista = derivarInicio({ papeis: ["secretario"], sessoes: [ABERTA], estadoSessoes: "pronto", agoraIso: AGORA });
    render(<PainelInicio vista={vista} />);

    expect(screen.getByText(/A sessão está acontecendo agora/i)).toBeTruthy();
    expect(href(/Comando da Mesa/)).toBe("/sessoes/s-viva/conduzir");
    expect(href(/Telão do plenário/)).toBe("/sessoes/s-viva/plenario");
    expect(href(/Chamada de presença/)).toBe("/sessoes/s-viva/chamada");
  });

  it("enquanto carrega, não afirma que não há sessão e não oferece ação", () => {
    const vista = derivarInicio({ papeis: ["secretario"], sessoes: null, estadoSessoes: "carregando" });
    const { container } = render(<PainelInicio vista={vista} />);

    expect(screen.queryByText(/Nenhuma sessão agendada/i)).toBeNull();
    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
    expect(screen.queryByRole("link", { name: /Comando da Mesa/ })).toBeNull();
  });

  it("secretaria sem sessão: as áreas de trabalho viram atalhos clicáveis", () => {
    const vista = derivarInicio({ papeis: ["secretario"], sessoes: [], estadoSessoes: "pronto", agoraIso: AGORA });
    render(<PainelInicio vista={vista} />);

    expect(screen.getByText(/Áreas de trabalho/i)).toBeTruthy();
    expect(href(/Proposições/)).toBe("/proposicoes");
    expect(href(/Painéis da Mesa/)).toBe("/paineis/mesa");
    expect(href(/^Agendar sessão$/)).toBe("/agendar-sessao");
  });

  it("vereador: é mandado para a área dele, sem o escritório da secretaria", () => {
    const vista = derivarInicio({ papeis: ["vereador"], sessoes: [ABERTA], estadoSessoes: "pronto", agoraIso: AGORA });
    render(<PainelInicio vista={vista} />);

    expect(href(/^Votar$/)).toBe("/votar");
    expect(screen.queryByRole("link", { name: /Painéis da Mesa/ })).toBeNull();
    expect(screen.getByText(/Sua área/i)).toBeTruthy();
  });
});
