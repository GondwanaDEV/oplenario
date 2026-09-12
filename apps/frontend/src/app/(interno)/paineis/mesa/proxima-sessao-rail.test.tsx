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
});
