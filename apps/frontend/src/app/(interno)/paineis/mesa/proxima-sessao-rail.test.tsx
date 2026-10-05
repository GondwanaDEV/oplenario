import { describe, expect, it, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ProximaSessaoRail } from "./proxima-sessao-rail";
import type { SliSessaoOut } from "@/lib/use-mesa";

// Achado da revisão adversarial (fatia "truncamento-familia" sitio a, conserto): a fatia acrescentou
// `sliSessoesTotal` ao hook `useMesa`, mas o único consumidor visível de `sliSessoes` no dashboard da
// Mesa não recebia o total — quando a sessão "agendada" caía fora do corte em 200, o rail AFIRMAVA
// "Nenhuma sessão agendada." mesmo com uma sessão convocada existindo fora da página. `sliSessoesTotal`
// era dado morto no FE.

function sessao(situacao: string): SliSessaoOut {
  return { sessaoId: "s1", estadoAtual: situacao, situacao, agendadaPara: "2026-09-20T18:00:00Z" };
}

function agendada(sessaoId: string, agendadaPara: string | null): SliSessaoOut {
  return { sessaoId, estadoAtual: "agendada", situacao: "agendada", agendadaPara };
}

describe("ProximaSessaoRail", () => {
  afterEach(() => cleanup());

  it("agendada visivel na lista -> mostra a data normalmente", () => {
    render(<ProximaSessaoRail sliSessoes={[sessao("agendada")]} sliSessoesTotal={1} />);
    expect(screen.getByText(/Agendada para/)).toBeDefined();
    expect(screen.queryByText(/Nenhuma sessão agendada/)).toBeNull();
  });

  it("lista vazia e SEM corte (total bate com o que veio) -> a negativa e' honesta", () => {
    render(<ProximaSessaoRail sliSessoes={[sessao("aberta")]} sliSessoesTotal={1} />);
    expect(screen.getByText("Nenhuma sessão agendada.")).toBeDefined();
  });

  it("o CENARIO DO ACHADO: a agendada caiu fora do corte — o rail nao pode afirmar que nao ha' nenhuma", () => {
    // a lista que chegou (200 na producao, 1 aqui) nao tem nenhuma "agendada" — mas o servidor diz que
    // ha' 214 sessoes ao todo (sliSessoesTotal > sliSessoes.length): a agendada de amanha esta' fora.
    render(<ProximaSessaoRail sliSessoes={[sessao("aberta")]} sliSessoesTotal={214} />);
    expect(screen.queryByText("Nenhuma sessão agendada.")).toBeNull();
    const aviso = screen.getByRole("status");
    expect(aviso.className).toContain("aviso-corte");
    expect(aviso.textContent).toContain("1");
    expect(aviso.textContent).toContain("214");
  });

  it("sliSessoesTotal ausente (null) -> comportamento antigo, nunca lanca", () => {
    render(<ProximaSessaoRail sliSessoes={[]} sliSessoesTotal={null} />);
    expect(screen.getByText("Nenhuma sessão agendada.")).toBeDefined();
  });

  // ---- Dashboard da Mesa: a sessão EM CURSO aparece, primeiro, com a porta para a condução ----

  it("sessão em curso aparece ANTES da próxima, com link para a condução", () => {
    render(
      <ProximaSessaoRail
        token="tk"
        sliSessoes={[
          { sessaoId: "em-curso-1", estadoAtual: "aberta", situacao: "em_curso", abertaEm: "2026-10-04T13:00:00Z" },
          agendada("futura", "2026-10-20T13:00:00Z"),
        ]}
        sliSessoesTotal={2}
        agora={new Date("2026-10-04T15:00:00Z")}
      />,
    );
    const link = screen.getByRole("link", { name: /Conduzir a sessão/ });
    expect(link.getAttribute("href")).toBe("/sessoes/em-curso-1/conduzir?token=tk");
    const corpo = document.body.textContent ?? "";
    expect(corpo.indexOf("Em curso agora")).toBeGreaterThanOrEqual(0);
    expect(corpo.indexOf("Em curso agora")).toBeLessThan(corpo.indexOf("Agendada para"));
    expect(screen.queryByText("Nenhuma sessão agendada.")).toBeNull();
  });

  it("só sessão em curso (nada agendada): mostra a em curso e ainda diz que não há agendada", () => {
    render(
      <ProximaSessaoRail
        sliSessoes={[{ sessaoId: "x", estadoAtual: "aberta", situacao: "em_curso" }]}
        sliSessoesTotal={1}
      />,
    );
    expect(screen.getByText("Em curso agora")).toBeDefined();
    expect(screen.getByText("Nenhuma sessão agendada.")).toBeDefined();
  });

  it("sessão suspensa também aparece, rotulada, com o link", () => {
    render(
      <ProximaSessaoRail
        sliSessoes={[{ sessaoId: "sus", estadoAtual: "suspensa", situacao: "suspensa" }]}
        sliSessoesTotal={1}
      />,
    );
    expect(screen.getByText(/suspensa/i)).toBeDefined();
    expect(screen.getByRole("link", { name: /Conduzir a sessão/ }).getAttribute("href")).toBe("/sessoes/sus/conduzir");
  });

  it("duas em curso: lista as duas, a iniciada mais recentemente primeiro", () => {
    render(
      <ProximaSessaoRail
        sliSessoes={[
          { sessaoId: "velha", estadoAtual: "aberta", situacao: "em_curso", abertaEm: "2026-10-01T13:00:00Z" },
          { sessaoId: "nova", estadoAtual: "aberta", situacao: "em_curso", abertaEm: "2026-10-04T13:00:00Z" },
        ]}
        sliSessoesTotal={2}
      />,
    );
    const hrefs = screen.getAllByRole("link", { name: /Conduzir a sessão/ }).map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/sessoes/nova/conduzir", "/sessoes/velha/conduzir"]);
  });

  // ---- "próxima sessão": hora NO FUSO DA CASA, e a mais próxima (não a primeira da lista) ----

  it("a hora sai no fuso da Casa (America/Fortaleza), nunca no do navegador", () => {
    // 2026-09-20T01:30Z = 19/09 22h30 em Fortaleza. `toLocaleString` sem fuso mostrava 20/09 no servidor UTC.
    render(
      <ProximaSessaoRail
        sliSessoes={[agendada("a", "2026-09-20T01:30:00Z")]}
        sliSessoesTotal={1}
        agora={new Date("2026-09-01T00:00:00Z")}
      />,
    );
    expect(screen.getByText(/Agendada para 19\/09\/2026 às 22h30/)).toBeDefined();
  });

  it("escolhe a agendada mais próxima no futuro, não a primeira da lista (a lista vem por 'mais antiga primeiro')", () => {
    render(
      <ProximaSessaoRail
        sliSessoes={[
          agendada("longe", "2026-12-10T13:00:00Z"),
          agendada("perto", "2026-10-06T13:00:00Z"),
          agendada("ja-passou", "2026-09-01T13:00:00Z"),
        ]}
        sliSessoesTotal={3}
        agora={new Date("2026-10-04T15:00:00Z")}
      />,
    );
    expect(screen.getByText(/Agendada para 06\/10\/2026/)).toBeDefined();
  });

  it("agendada sem data: 'data a definir', sem quebrar", () => {
    render(<ProximaSessaoRail sliSessoes={[agendada("sem-data", null)]} sliSessoesTotal={1} />);
    expect(screen.getByText(/data a definir/)).toBeDefined();
  });
});
