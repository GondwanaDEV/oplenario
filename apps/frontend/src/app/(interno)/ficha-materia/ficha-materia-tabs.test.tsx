import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { FichaMateriaTabs } from "./ficha-materia-tabs";
import type { FichaMateriaOut } from "@/lib/contrato-legislativo.gen";

const ficha: FichaMateriaOut = {
  proposicao: {
    id: "1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    urnLex: "urn:x",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias",
    estado: "em_comissoes",
    aprovada: false,
    lockVersion: 3,
    atualizadoEm: "2026-05-12T10:00:00Z",
    texto: "Art. 1º Fica instituído o Programa.",
  },
  tramitacao: [
    { deEstado: "protocolada", paraEstado: "em_comissoes", gatilho: "distribuir", ocorridoEm: "2026-04-08T09:00:00Z" },
  ],
  tramitacaoTruncado: false,
  apensadas: [],
  apensadasTruncado: false,
  emendas: [
    { id: "e1", numeroLocal: 1, tipoEmenda: "modificativa", momentoApresentacao: "no_prazo", autorTexto: "Ver.ª Carla Souza", estado: "aprovada" },
  ],
  emendasTruncado: false,
  pareceres: [
    { id: "p1", comissaoId: "9119889e-1111-4222-8333-444444444444", relatorId: "r1", votoRelator: "favoravel", estado: "aprovado" },
  ],
  pareceresTruncado: false,
};

describe("FichaMateriaTabs", () => {
  afterEach(() => cleanup());

  it("abre com a aba 'Texto vigente' selecionada; demais painéis escondidos", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    const abaTexto = screen.getByRole("tab", { name: /texto vigente/i });
    expect(abaTexto.getAttribute("aria-selected")).toBe("true");
    expect(abaTexto.getAttribute("tabindex")).toBe("0");
    expect(screen.getByRole("tab", { name: /tramitação/i }).getAttribute("tabindex")).toBe("-1");
    expect(screen.getByText(/Art\. 1º/)).toBeTruthy();
  });

  it("clique numa aba troca a seleção e o painel visível", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    expect(screen.getByRole("tab", { name: /pareceres/i }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByRole("tab", { name: /texto vigente/i }).getAttribute("aria-selected")).toBe("false");
    // Era `getByText("CCJ")` — uma fixture com um código legível onde o dado REAL é `uuid NOT NULL`,
    // e por isso o teste ficou verde enquanto a aba imprimia UUIDs (defeito #11 do ledger, `MATA`).
    // A fixture agora traz o formato de verdade, e a asserção é sobre a tela, não sobre o id.
    expect(screen.getByText("Comissão designada")).toBeTruthy();
    expect(document.body.textContent).not.toContain("9119889e");
    // O voto saía cru na tela ("Voto do relator: favoravel") — sem acento e sem maiúscula, invisível
    // pro detector de underscore da sonda. `rotularVoto` já existia em parecer-vista.ts e não estava
    // sendo usado aqui; achado olhando a tela viva, não a suíte.
    expect(screen.getByText(/Voto do relator: Favorável$/)).toBeTruthy();
  });

  it("aba Pareceres: com nome servido pelo backend, a linha diz a comissão de verdade", () => {
    const p1 = { ...ficha.pareceres[0], comissaoNome: "Comissão de Finanças e Orçamento" };
    render(<FichaMateriaTabs ficha={{ ...ficha, pareceres: [p1] }} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    expect(screen.getByText("Comissão de Finanças e Orçamento")).toBeTruthy();
    expect(document.body.textContent).not.toContain("9119889e");
  });

  it("aba Pareceres: cada item linka pro editor de parecer (Onda B Slice 5), preservando ?token=", () => {
    render(<FichaMateriaTabs ficha={ficha} token="tok" />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    const link = screen.getByRole("link", { name: /abrir parecer/i }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/parecer/p1?token=tok");
  });

  it("clique numa aba move o foco do DOM pra ela (roving tabindex não pode desincronizar do foco real)", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    const abaPareceres = screen.getByRole("tab", { name: /pareceres/i });
    fireEvent.click(abaPareceres);
    expect(document.activeElement).toBe(abaPareceres);
    expect(abaPareceres.getAttribute("tabindex")).toBe("0");
  });

  it("ArrowRight/ArrowLeft navegam com roving tabindex (com wraparound)", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    const abaTexto = screen.getByRole("tab", { name: /texto vigente/i });
    fireEvent.keyDown(abaTexto, { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: /tramitação/i }).getAttribute("aria-selected")).toBe("true");

    fireEvent.keyDown(screen.getByRole("tab", { name: /tramitação/i }), { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: /texto vigente/i }).getAttribute("aria-selected")).toBe("true");

    // wraparound pra trás: da primeira aba, ArrowLeft vai pra última (Anexos)
    fireEvent.keyDown(screen.getByRole("tab", { name: /texto vigente/i }), { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: /anexos/i }).getAttribute("aria-selected")).toBe("true");
  });

  it("aba Emendas mostra TODOS os estados (chip por linha), não só os ativos", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    fireEvent.click(screen.getByRole("tab", { name: /emendas/i }));
    expect(screen.getByText("Modificativa")).toBeTruthy();
    expect(screen.getByText(/Aprovada/)).toBeTruthy();
  });

  it("aba Anexos mostra EmBreve honesto", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    fireEvent.click(screen.getByRole("tab", { name: /anexos/i }));
    expect(screen.getByRole("status")).toBeTruthy();
  });

  it("tem um h2 rotulando a região de conteúdo (não pula de h1 pra h3)", () => {
    render(<FichaMateriaTabs ficha={ficha} />);
    expect(screen.getByRole("heading", { level: 2, name: /conteúdo da matéria/i })).toBeTruthy();
  });

  it("sem texto vigente registrado -> honesto, sem lançar", () => {
    render(<FichaMateriaTabs ficha={{ ...ficha, proposicao: { ...ficha.proposicao, texto: null } }} />);
    expect(screen.getByText(/nenhum texto vigente/i)).toBeTruthy();
  });

  // ---------- fatia "truncamento-familia": as 3 abas com lista param de fingir completude ----------

  it("*Truncado=true: o badge da aba vira 'N+' e o painel mostra o aviso de corte — nas 3 listas", () => {
    const { container } = render(
      <FichaMateriaTabs
        ficha={{ ...ficha, tramitacaoTruncado: true, pareceresTruncado: true, emendasTruncado: true }}
      />,
    );
    // os 5 painéis ficam montados simultaneamente (só `hidden` alterna) — o seletor tem de mirar o
    // painel VISÍVEL, senão `querySelector` sempre acha o `.aviso-corte` da tramitação (o 1o no DOM).
    const avisoVisivel = () => container.querySelector('[role="tabpanel"]:not([hidden]) .aviso-corte');

    expect(screen.getByRole("tab", { name: /tramitação/i }).textContent).toContain("1+");
    fireEvent.click(screen.getByRole("tab", { name: /tramitação/i }));
    expect(avisoVisivel()?.getAttribute("role")).toBe("status");
    expect(avisoVisivel()?.textContent ?? "").toMatch(/Mostrando as 1 transições mais recentes/);

    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    expect(screen.getByRole("tab", { name: /pareceres/i }).textContent).toContain("1+");
    expect(avisoVisivel()?.textContent ?? "").toMatch(/Mostrando os 1 pareceres mais recentes/);

    fireEvent.click(screen.getByRole("tab", { name: /emendas/i }));
    expect(screen.getByRole("tab", { name: /emendas/i }).textContent).toContain("1+");
    expect(avisoVisivel()?.textContent ?? "").toMatch(/Mostrando as 1 emendas mais recentes/);
  });

  it("*Truncado=false: badge SEM '+' e nenhum aviso — mesmo com uma lista 'cheia' (rule 4: o sinal vem do campo do servidor, nunca do tamanho da lista)", () => {
    // A lista de tramitação aqui tem 3 itens (pareceria "grande" pra uma heurística ingênua de
    // tamanho), mas `tramitacaoTruncado` é `false` — se a UI deduzisse o corte comparando tamanhos, ela
    // erraria pro lado de acusar corte que o servidor não afirmou.
    const tramitacaoGrande = [ficha.tramitacao[0], ficha.tramitacao[0], ficha.tramitacao[0]];
    const { container } = render(
      <FichaMateriaTabs ficha={{ ...ficha, tramitacao: tramitacaoGrande, tramitacaoTruncado: false }} />,
    );
    expect(screen.getByRole("tab", { name: /tramitação/i }).textContent).toContain("3");
    expect(screen.getByRole("tab", { name: /tramitação/i }).textContent).not.toContain("3+");
    fireEvent.click(screen.getByRole("tab", { name: /tramitação/i }));
    expect(container.querySelector(".aviso-corte")).toBeNull();
  });

  it("*Truncado=true mesmo com lista pequena (1 item): o aviso aparece do mesmo jeito — a UI confia no campo do servidor, não infere 'lista pequena = sem corte'", () => {
    const { container } = render(<FichaMateriaTabs ficha={{ ...ficha, tramitacaoTruncado: true }} />);
    fireEvent.click(screen.getByRole("tab", { name: /tramitação/i }));
    const aviso = container.querySelector(".aviso-corte");
    expect(aviso?.textContent ?? "").toMatch(/Mostrando as 1 transições mais recentes/);
  });
});
