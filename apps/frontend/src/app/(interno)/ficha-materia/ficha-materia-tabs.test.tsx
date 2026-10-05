import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
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
    { deEstado: "protocolada", paraEstado: "em_comissoes", gatilho: "distribuir", ocorridoEm: "2026-04-08T09:00:00Z", recebimento: null },
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
  coautores: [],
  atos: [],
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

  it("aba Tramitação: a aprovação, o autógrafo e a lei entram na linha do tempo (docs/16 linha 30)", () => {
    render(
      <FichaMateriaTabs
        ficha={{
          ...ficha,
          atos: [
            { ato: "aprovada", ocorridoEm: "2026-05-01T12:00:00Z" },
            { ato: "autografo_enviado", ocorridoEm: "2026-05-02T12:00:00Z", numero: 8, ano: 2026 },
            { ato: "publicada", ocorridoEm: "2026-06-10T12:00:00Z", tipoNorma: "lei", numero: 5, ano: 2026 },
          ],
        }}
      />,
    );
    fireEvent.click(screen.getByRole("tab", { name: /tramitação/i }));
    const itens = within(document.querySelector("ol.tempo") as HTMLElement).getAllByRole("listitem");
    expect(itens.map((li) => li.querySelector(".evt")?.textContent)).toEqual([
      "Publicação: Lei nº 5/2026",
      "Autógrafo nº 8/2026 enviado ao Executivo",
      "Aprovada em plenário",
      "Protocolado → Em comissões",
    ]);
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

  // ---------- ADR-0019: quem relata, designar relator e a aba do parecer jurídico ----------

  it("aba Pareceres: mostra o relator pelo NOME (nunca o id) e, sem relator, diz isso", () => {
    const comNome = { ...ficha.pareceres[0], relatorNome: "Helena Matos" };
    const semRelator = { id: "p2", comissaoId: "c2", comissaoNome: "Comissão de Finanças", relatorId: null, votoRelator: null, estado: "em_elaboracao" };
    render(<FichaMateriaTabs ficha={{ ...ficha, pareceres: [comNome, semRelator] }} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    expect(screen.getByText("Relator: Helena Matos")).toBeTruthy();
    expect(screen.getByText("Sem relator designado")).toBeTruthy();
    expect(document.body.textContent).not.toContain("r1");
  });

  it("aba Pareceres: 'Designar relator' só para a secretaria e só onde falta relator num parecer em curso", () => {
    const semRelator = { id: "p2", comissaoId: "c2", comissaoNome: "Comissão de Finanças", relatorId: null, votoRelator: null, estado: "em_elaboracao" };
    const encerradoSemRelator = { id: "p3", comissaoId: "c3", comissaoNome: "Comissão de Obras", relatorId: null, votoRelator: null, estado: "prazo_vencido" };
    const f = { ...ficha, pareceres: [ficha.pareceres[0], semRelator, encerradoSemRelator] };
    render(<FichaMateriaTabs ficha={f} papeis={["vereador"]} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    expect(screen.queryByRole("button", { name: /designar relator/i })).toBeNull();
    cleanup();
    render(<FichaMateriaTabs ficha={f} papeis={["secretario"]} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    expect(screen.getAllByRole("button", { name: /designar relator/i })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Designar relator para Comissão de Finanças" })).toBeTruthy();
  });

  it("Designar relator: escolhe o vereador, chama POST .../relator e a ficha recarrega", async () => {
    const chamadas: Array<[string, RequestInit | undefined]> = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      chamadas.push([url, init]);
      if (url === "/api/cadastros/vereadores")
        return { ok: true, status: 200, json: async () => ({ vereadores: [{ id: "v1", nome: "Helena Past", "nome-parlamentar": null, "com-acesso": true }] }) } as Response;
      if (init?.method === "POST" && url === "/api/legislativo/pareceres/p2/relator")
        return { ok: true, status: 200, json: async () => ({ id: "p2", "relator-id": "v1", "relator-nome": "Helena Past" }) } as Response;
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    const semRelator = { id: "p2", comissaoId: "c2", comissaoNome: "Comissão de Finanças", relatorId: null, votoRelator: null, estado: "em_elaboracao" };
    const onTramitou = vi.fn();
    render(<FichaMateriaTabs ficha={{ ...ficha, pareceres: [semRelator] }} token="tk" papeis={["secretario"]} onTramitou={onTramitou} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    fireEvent.click(screen.getByRole("button", { name: "Designar relator para Comissão de Finanças" }));
    const seletor = await screen.findByLabelText("Relator de Comissão de Finanças");
    fireEvent.change(seletor, { target: { value: "v1" } });
    fireEvent.click(screen.getByRole("button", { name: "Designar" }));
    await waitFor(() => expect(onTramitou).toHaveBeenCalledTimes(1));
    const post = chamadas.find(([u]) => u.endsWith("/relator"))!;
    expect(JSON.parse(String(post[1]!.body))).toEqual({ "relator-id": "v1" });
  });

  it("Designar relator: 404 do servidor vira alerta e o formulário segue aberto", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/cadastros/vereadores")
        return { ok: true, status: 200, json: async () => ({ vereadores: [{ id: "v1", nome: "Helena Past", "com-acesso": true }] }) } as Response;
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    const semRelator = { id: "p2", comissaoId: "c2", comissaoNome: "Comissão de Finanças", relatorId: null, votoRelator: null, estado: "em_elaboracao" };
    render(<FichaMateriaTabs ficha={{ ...ficha, pareceres: [semRelator] }} token="tk" papeis={["secretario"]} />);
    fireEvent.click(screen.getByRole("tab", { name: /pareceres/i }));
    fireEvent.click(screen.getByRole("button", { name: "Designar relator para Comissão de Finanças" }));
    fireEvent.change(await screen.findByLabelText("Relator de Comissão de Finanças"), { target: { value: "v1" } });
    fireEvent.click(screen.getByRole("button", { name: "Designar" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Não encontramos o parecer ou o vereador/);
  });

  describe("aba Parecer jurídico", () => {
    const assinado = (extra = {}) => ({
      id: "pj1", "pedido-id": "ped1", numero: 3, ano: 2026, estado: "assinado", relatorio: "Analisei a matéria.",
      fundamentacao: "Art. 30 da CF.", conclusao: "com_ressalvas",
      assinatura: { nome: "Lúcia Prado", oab: "CE 12345", qualificacao: "contratado", em: "2026-09-30T14:00:00Z", algoritmo: "STUB-ICP-v0", sha256: `sha256:${"ef56".repeat(16)}` },
      "substitui-id": null, substituido: false, ...extra,
    });
    const rota = (corpo: unknown, status = 200) => {
      const f = vi.fn(async (url: string, init?: RequestInit) => {
        if (url === "/api/legislativo/proposicoes/1/pareceres-juridicos" && !init?.method)
          return { ok: status < 300, status, json: async () => corpo } as Response;
        return { ok: false, status: 404, json: async () => ({}) } as Response;
      });
      global.fetch = f as unknown as typeof fetch;
      return f;
    };

    it("só busca quando a aba é aberta; lista o assinado (com o selo, a conclusão e a assinatura) e o pedido aberto", async () => {
      const f = rota({
        pareceres: [assinado(), assinado({ id: "pj0", numero: 2, substituido: true })],
        "pedidos-abertos": [{ id: "ped2", assunto: "Análise da emenda", prazo: "2026-10-15", "criado-em": "2026-09-29T10:00:00Z" }],
      });
      render(<FichaMateriaTabs ficha={ficha} token="tk" papeis={["secretario"]} />);
      const buscouParecer = () => f.mock.calls.some(([u]) => String(u).endsWith("/pareceres-juridicos"));
      expect(buscouParecer()).toBe(false);
      fireEvent.click(screen.getByRole("tab", { name: "Parecer jurídico" }));
      expect(await screen.findByText("Parecer jurídico nº 3/2026")).toBeTruthy();
      expect(buscouParecer()).toBe(true);
      expect(screen.getByText(/opinativo/)).toBeTruthy();
      expect(screen.getByText("Substituído")).toBeTruthy();
      expect(screen.getByText("Análise da emenda")).toBeTruthy();
      expect(screen.getByText(/prazo 15\/10\/2026/)).toBeTruthy();
      expect(screen.getAllByText("Com ressalvas").length).toBeGreaterThan(0);
      expect(screen.getAllByText("Lúcia Prado").length).toBe(2); // um por parecer assinado (o substituído também fica)
      expect(screen.getAllByText(/OAB\/CE 12345 · Advogado\(a\) contratado\(a\)/).length).toBeGreaterThan(0);
      expect(screen.getAllByText("ef56".repeat(16))).toHaveLength(2); // o carimbo (SHA-256) de cada parecer assinado
      const link = screen.getByRole("link", { name: "Abrir na fila do jurídico" });
      expect(link.getAttribute("href")).toBe("/juridico/ped2?token=tk");
    });

    it("sem parecer nem pedido: diz que não há — e a secretaria pode pedir, o pedido leva o proposicao-id", async () => {
      const f = rota({ pareceres: [], "pedidos-abertos": [] });
      render(<FichaMateriaTabs ficha={ficha} token="tk" papeis={["secretario"]} />);
      fireEvent.click(screen.getByRole("tab", { name: "Parecer jurídico" }));
      expect(await screen.findByText(/Nenhum parecer jurídico assinado nem pedido em aberto/)).toBeTruthy();
      f.mockImplementation(async (url: string, init?: RequestInit) => {
        if (init?.method === "POST" && url === "/api/legislativo/pedidos-parecer-juridico")
          return { ok: true, status: 201, json: async () => ({ id: "ped9", estado: "pendente", assunto: "Análise jurídica da matéria" }) } as Response;
        return { ok: true, status: 200, json: async () => ({ pareceres: [], "pedidos-abertos": [] }) } as Response;
      });
      fireEvent.click(screen.getByRole("button", { name: "Pedir parecer jurídico" }));
      fireEvent.click(screen.getByRole("button", { name: "Abrir o pedido" }));
      expect((await screen.findByRole("status")).textContent).toMatch(/Pedido aberto: Análise jurídica da matéria/);
      const post = f.mock.calls.find(([, i]) => i?.method === "POST")!;
      expect(JSON.parse(String(post[1]!.body))).toEqual({ "proposicao-id": "1" });
    });

    it("vereador lê, mas não vê o botão de pedir nem o link para a fila do jurídico", async () => {
      rota({ pareceres: [assinado()], "pedidos-abertos": [{ id: "ped2", assunto: "Análise", prazo: null, "criado-em": "2026-09-29T10:00:00Z" }] });
      render(<FichaMateriaTabs ficha={ficha} token="tk" papeis={["vereador"]} />);
      fireEvent.click(screen.getByRole("tab", { name: "Parecer jurídico" }));
      expect(await screen.findByText("Parecer jurídico nº 3/2026")).toBeTruthy();
      expect(screen.queryByRole("button", { name: "Pedir parecer jurídico" })).toBeNull();
      expect(screen.queryByRole("link", { name: /fila do jurídico|Abrir o pedido/ })).toBeNull();
    });

    it("403 (papel sem acesso) diz quem vê — não é erro nem lista vazia", async () => {
      rota({ erro: "papel" }, 403);
      render(<FichaMateriaTabs ficha={ficha} token="tk" papeis={["admin_ente"]} />);
      fireEvent.click(screen.getByRole("tab", { name: "Parecer jurídico" }));
      expect(await screen.findByText(/visível à secretaria, aos vereadores e ao jurídico/)).toBeTruthy();
    });

    it("falha do servidor vira alerta honesto, nunca 'nenhum parecer'", async () => {
      rota({}, 500);
      render(<FichaMateriaTabs ficha={ficha} token="tk" papeis={["secretario"]} />);
      fireEvent.click(screen.getByRole("tab", { name: "Parecer jurídico" }));
      expect((await screen.findByRole("alert")).textContent).toMatch(/Não foi possível concluir agora/);
      expect(screen.queryByText(/Nenhum parecer jurídico/)).toBeNull();
    });

    it("o texto do parecer fica dentro do <details> do assinado", async () => {
      rota({ pareceres: [assinado()], "pedidos-abertos": [] });
      render(<FichaMateriaTabs ficha={ficha} token="tk" papeis={["secretario"]} />);
      fireEvent.click(screen.getByRole("tab", { name: "Parecer jurídico" }));
      const resumo = await screen.findByText("Parecer jurídico nº 3/2026");
      const det = resumo.closest("details")!;
      expect(within(det).getByText("Analisei a matéria.")).toBeTruthy();
      expect(within(det).getByText("Art. 30 da CF.")).toBeTruthy();
    });
  });
});
