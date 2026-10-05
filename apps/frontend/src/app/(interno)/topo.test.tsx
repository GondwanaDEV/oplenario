import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { TopoInterno, arrumarNav, destinosVisiveis } from "./topo";
import { TemaProvider } from "@/lib/tema";
import { AuthProvider } from "@/lib/auth";
import { MolduraDaClara } from "./clara/moldura-da-clara";

// TopoInterno chama useTema() (src/lib/tema.tsx), useAuth() (src/lib/auth.tsx) e, desde a fatia
// "demo-tres-consertos" #1, useMeuIdentidade() (busca GET /api/meu/identidade) — como useTema(), o
// contexto lança fora do seu Provider (mesmo contrato de composição documentado em src/lib/auth.tsx). O
// teste real precisa do MESMO wrapper que layout.tsx aplica em produção (mirror do padrão em
// auth.test.tsx / proposicoes/page.test.tsx), e agora também mocka `fetch` — o ator não é mais uma prop
// literal, é resolvido do servidor (achado ao vivo: o cabeçalho mostrava o MESMO ator fixo pra qualquer
// persona logada).
describe("TopoInterno", () => {
  // `cleanup` explicito (a suite nao liga os globals do Vitest, entao o Testing Library nao desmonta sozinho): sem
  // ele, os tres Topos ficavam montados ate' o fim do arquivo e, sob carga no CI, o React ainda tinha trabalho
  // agendado quando o jsdom era desmontado -> "ReferenceError: window is not defined" (3 erros nao tratados, CI do
  // PR #82, com os 8 testes verdes).
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra o rótulo da área e o nome+papel resolvidos do ator autenticado", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ nome: "Marina Alencar Freire", papeis: ["secretario"] }) }) as Response
    ) as unknown as typeof fetch;

    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Painéis da Mesa" />
        </TemaProvider>
      </AuthProvider>
    );
    // selector: ".area-tag" desambigua da Task 12 — o novo <nav> (abaixo) também renderiza o texto
    // "Painéis da Mesa" no link ativo; sem o selector, getByText acha 2 elementos e lança.
    expect(screen.getByText("Painéis da Mesa", { selector: ".area-tag" })).toBeTruthy();
    await waitFor(() => expect(screen.getByText("Marina Alencar Freire")).toBeTruthy());
    expect(screen.getByText("Secretário(a)")).toBeTruthy();
    // a secretaria vê mais de 6 entradas: os Painéis da Mesa estão no grupo Sessões, que marca a página atual
    const sessoes = screen.getByRole("button", { name: /^Sessões/ });
    expect(sessoes.getAttribute("data-atual")).toBe("true");
    expect(sessoes.textContent).toContain("contém a página atual");
    fireEvent.click(sessoes);
    expect(sessoes.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByRole("link", { name: "Painéis da Mesa" }).getAttribute("aria-current")).toBe("page");
    fireEvent.click(screen.getByRole("button", { name: "Matérias" }));
    expect(sessoes.getAttribute("aria-expanded")).toBe("false"); // um grupo aberto por vez
    // Task 14 — comToken deve preservar o ?token= dev na navegação interna via <Link>.
    expect(screen.getByRole("link", { name: "Proposições" }).getAttribute("href")).toBe("/proposicoes?token=abc123");
  });

  it("o grupo aberto fecha no Esc (o foco volta ao botão) e no clique fora da barra", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ nome: "Marina Alencar Freire", papeis: ["secretario"] }) }) as Response
    ) as unknown as typeof fetch;
    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Atendimento" />
        </TemaProvider>
      </AuthProvider>
    );
    const casa = await screen.findByRole("button", { name: "Casa" });
    fireEvent.click(casa);
    expect(screen.getByRole("link", { name: "Vereadores" })).toBeTruthy();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(casa.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("link", { name: "Vereadores" })).toBeNull();
    expect(document.activeElement).toBe(casa);
    fireEvent.click(casa);
    fireEvent.pointerDown(document.body);
    expect(casa.getAttribute("aria-expanded")).toBe("false");
  });

  it("a entrada 'Clara' abre o painel expandido ali mesmo — não leva à antiga tela cheia /assistente (ADR-0024, fatia 5)", async () => {
    global.fetch = vi.fn(async (url: string | URL | Request) =>
      String(url).includes("/api/meu/identidade")
        ? ({ ok: true, json: async () => ({ nome: "Marina Alencar Freire", papeis: ["secretario"] }) } as Response)
        : ({ ok: false, status: 404, json: async () => ({}) } as Response),
    ) as unknown as typeof fetch;
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["secretario"]}'>
        <TemaProvider>
          <MolduraDaClara>
            <TopoInterno area="Central da Casa" />
          </MolduraDaClara>
        </TemaProvider>
      </AuthProvider>
    );
    const entrada = await screen.findByRole("button", { name: "Clara" });
    expect(screen.queryByRole("link", { name: "Clara" })).toBeNull();
    expect(document.querySelector('a[href^="/assistente"]')).toBeNull();
    expect(entrada.getAttribute("aria-expanded")).toBe("false");
    expect(document.getElementById(entrada.getAttribute("aria-controls") ?? "")?.tagName).toBe("ASIDE");
    fireEvent.click(entrada);
    expect(document.documentElement.dataset.clara).toBe("expandido");
    expect(document.activeElement).toBe(screen.getByLabelText("Sua pergunta"));
    await waitFor(() => expect(entrada.getAttribute("aria-expanded")).toBe("true"));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(document.activeElement).toBe(entrada);
    cleanup();
    delete document.documentElement.dataset.clara;
    // sem a Clara na tela (fora da moldura), a entrada não aparece — nem como link para a rota antiga
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["secretario"]}'>
        <TemaProvider>
          <TopoInterno area="Central da Casa" />
        </TemaProvider>
      </AuthProvider>
    );
    await screen.findByText("Marina Alencar Freire");
    expect(screen.queryByRole("button", { name: "Clara" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Clara" })).toBeNull();
  });

  it("enquanto a identidade carrega, mostra um rótulo HONESTO — nunca um nome fixo/inventado", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch; // nunca resolve
    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Painéis da Mesa" />
        </TemaProvider>
      </AuthProvider>
    );
    expect(screen.getByText("Carregando…")).toBeTruthy();
    expect(screen.queryByText("Sérgio Lopes")).toBeNull();
  });

  describe("a conta de acesso (trocar o próprio e-mail)", () => {
    // Responde por rota: o topo busca a identidade, /eu (o tipo do vínculo) e a contagem da caixa.
    function servidor(tipoVinculo: string) {
      global.fetch = vi.fn(async (url: string | URL | Request) => {
        const u = String(url);
        if (u.includes("/api/eu")) {
          return { ok: true, json: async () => ({ ator: { papeis: ["secretario"], "tipo-vinculo": tipoVinculo } }) } as Response;
        }
        if (u.includes("/api/meu/identidade")) {
          return { ok: true, json: async () => ({ nome: "Marina Alencar Freire", papeis: ["secretario"] }) } as Response;
        }
        return { ok: false, status: 404 } as Response;
      }) as unknown as typeof fetch;
    }
    const montar = () =>
      render(
        <AuthProvider tokenQuery={null}>
          <TemaProvider>
            <TopoInterno area="Painéis da Mesa" />
          </TemaProvider>
        </AuthProvider>
      );
    afterEach(() => vi.unstubAllEnvs());

    it("modo real, servidor da Casa: o avatar abre um menu com o link para a página de conta", async () => {
      vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
      servidor("servidor");
      montar();
      const link = await screen.findByRole("link", { name: /Trocar meu e-mail de acesso/ });
      expect(link.getAttribute("href")).toBe("/api/auth/conta");
      expect(link.getAttribute("target")).toBe("_blank");
      expect(link.getAttribute("rel")).toBe("noopener noreferrer");
      expect(screen.getByText("Marina Alencar Freire")).toBeTruthy(); // o nome segue na barra
    });

    it("sessão do gov.br (cidadão) não tem o link", async () => {
      vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
      servidor("cidadao");
      montar();
      await screen.findByText("Marina Alencar Freire");
      await new Promise((r) => setTimeout(r, 20));
      expect(screen.queryByRole("link", { name: /e-mail de acesso/ })).toBeNull();
    });

    it("modo dev (token de dev) não tem o link", async () => {
      servidor("servidor");
      render(
        <AuthProvider tokenQuery="abc123">
          <TemaProvider>
            <TopoInterno area="Painéis da Mesa" />
          </TemaProvider>
        </AuthProvider>
      );
      await screen.findByText("Marina Alencar Freire");
      expect(screen.queryByRole("link", { name: /e-mail de acesso/ })).toBeNull();
    });
  });

  it("se a busca falhar, mostra 'Sessão'/'indisponível' — nunca finge um ator (mesma disciplina da fatia 2)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 401 }) as Response) as unknown as typeof fetch;
    render(
      <AuthProvider tokenQuery="abc123">
        <TemaProvider>
          <TopoInterno area="Painéis da Mesa" />
        </TemaProvider>
      </AuthProvider>
    );
    await waitFor(() => expect(screen.getByText("Sessão")).toBeTruthy());
    expect(screen.getByText("indisponível")).toBeTruthy();
  });
});

describe("destinosVisiveis — a nav por papel", () => {
  const rotulos = (papeis: string[]) => destinosVisiveis(papeis).map((d) => d.rotulo);

  it("a secretaria vê as telas de trabalho e NÃO vê as do administrador", () => {
    const r = rotulos(["secretario"]);
    expect(r).toContain("Proposições");
    expect(r).not.toContain("Administração");
    expect(r).not.toContain("IA da Casa");
  });

  it("quem é SÓ administrador da Casa vê só a área dele — nada que o leve a 'Acesso restrito' (ADR-0005)", () => {
    expect(rotulos(["admin_ente"])).toEqual(["Caixa", "Clara", "IA da Casa", "Administração", "Auditoria"]);
  });

  it("o controle interno (auditor) vê só a trilha de auditoria (ADR-0017)", () => {
    expect(rotulos(["auditor"])).toEqual(["Caixa", "Clara", "Auditoria"]);
    expect(rotulos(["secretario"])).toContain("Auditoria");
  });

  it("o jurídico (juridico) vê a fila de pareceres e as contas — nada das telas da secretaria (ADR-0019, ADR-0021)", () => {
    expect(rotulos(["juridico"])).toEqual(["Caixa", "Clara", "Jurídico", "Contas"]);
    expect(rotulos(["secretario"])).toContain("Jurídico");
    expect(rotulos(["admin_ente"])).not.toContain("Jurídico");
    expect(rotulos(["vereador"])).not.toContain("Jurídico");
  });

  it("as Contas (ADR-0021) são de secretaria, vereador e jurídico — não de quem só administra ou audita", () => {
    for (const papeis of [["secretario"], ["vereador"], ["juridico"]]) expect(rotulos(papeis)).toContain("Contas");
    for (const papeis of [["admin_ente"], ["auditor"]]) expect(rotulos(papeis)).not.toContain("Contas");
  });

  it("a Caixa (ADR-0020) é de TODA pessoa interna — logo depois da Central", () => {
    for (const papeis of [["secretario"], ["vereador"], ["admin_ente"], ["auditor"], ["juridico"], ["secretario", "admin_ente"], []]) {
      expect(rotulos(papeis)).toContain("Caixa");
    }
    expect(rotulos(["secretario"]).slice(0, 2)).toEqual(["Central", "Caixa"]);
  });

  it("quem acumula secretaria e administração vê as duas", () => {
    const r = rotulos(["secretario", "admin_ente"]);
    expect(r).toContain("Proposições");
    expect(r).toContain("Administração");
  });
});

describe("TopoInterno — o número da Caixa (ADR-0020)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  function montarCom(rotas: Record<string, unknown>) {
    global.fetch = vi.fn(async (url: string) => {
      const corpo = rotas[String(url)];
      if (corpo === undefined) return { ok: false, status: 404, json: async () => ({}) } as Response;
      return { ok: true, status: 200, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;
    render(
      <AuthProvider tokenQuery='{"sub":"u","papeis":["auditor"]}'>
        <TemaProvider>
          <TopoInterno area="Auditoria" />
        </TemaProvider>
      </AuthProvider>
    );
  }

  it("soma comunicados não lidos e avisos não lidos no link da Caixa, com a frase para leitor de tela", async () => {
    montarCom({
      "/api/meu/identidade": { nome: "Renata", papeis: ["auditor"] },
      "/api/meu/comunicados/contagem": { itens: [], "nao-lidos": 2, "pendentes-ciencia": 0, "proxima-ciencia-ate": null },
      "/api/meu/notificacoes": { notificacoes: [], "nao-lidas": 1, "notificacoes-total": 0 },
    });
    const link = await screen.findByRole("link", { name: "Caixa, 3 por ler" });
    expect(link.getAttribute("href")?.startsWith("/caixa?token=")).toBe(true);
    expect(link.querySelector(".nav-contagem")?.textContent).toBe("3");
  });

  it("uma fonte fora conta a outra; as duas fora, nenhum número (nunca um zero que mente)", async () => {
    montarCom({
      "/api/meu/identidade": { nome: "Renata", papeis: ["auditor"] },
      "/api/meu/comunicados/contagem": { itens: [], "nao-lidos": 4, "pendentes-ciencia": 0, "proxima-ciencia-ate": null },
    });
    expect(await screen.findByRole("link", { name: "Caixa, 4 por ler" })).toBeTruthy();
    cleanup();
    montarCom({ "/api/meu/identidade": { nome: "Renata", papeis: ["auditor"] } });
    await waitFor(() => expect(screen.getByText("Renata")).toBeTruthy());
    expect(screen.getByRole("link", { name: "Caixa" }).querySelector(".nav-contagem")).toBeNull();
  });
});

describe("arrumarNav — os grupos da barra", () => {
  const forma = (papeis: string[]) =>
    arrumarNav(destinosVisiveis(papeis)).map((i) => (i.tipo === "link" ? i.destino.rotulo : `${i.nome}[${i.destinos.map((d) => d.rotulo).join(",")}]`));

  it("a secretaria: as quatro portas soltas e o resto em Matérias · Sessões · Cidadão · Casa", () => {
    expect(forma(["secretario"])).toEqual([
      "Central", "Caixa", "Busca", "Clara",
      "Matérias[Proposições,Tramitação,Recebimentos,Conferências,Jurídico,Contas,Normas]",
      "Sessões[Painéis da Mesa,Agendar sessão,Pauta,Tempos da tribuna,Gravações,Atas,Calendário]",
      "Cidadão[Atendimento,Moderação]",
      "Casa[Expediente,Vereadores,Auditoria]",
    ]);
  });

  it("nenhuma entrada visível se perde nem se repete ao agrupar", () => {
    for (const papeis of [["secretario"], ["vereador"], ["secretario", "admin_ente"], []]) {
      const achatada = arrumarNav(destinosVisiveis(papeis)).flatMap((i) => (i.tipo === "link" ? [i.destino] : i.destinos));
      expect(achatada.map((d) => d.href).sort()).toEqual(destinosVisiveis(papeis).map((d) => d.href).sort());
    }
  });

  it("quem vê poucas entradas (administração, auditoria, jurídico) continua com a barra plana", () => {
    expect(forma(["admin_ente"])).toEqual(["Caixa", "Clara", "IA da Casa", "Administração", "Auditoria"]);
    expect(forma(["auditor"])).toEqual(["Caixa", "Clara", "Auditoria"]);
    expect(forma(["juridico"])).toEqual(["Caixa", "Clara", "Jurídico", "Contas"]);
  });
});
