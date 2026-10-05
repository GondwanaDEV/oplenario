import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { SecaoFicha } from "./secao-ficha";

// Task 3.2 (Fatia A2.3, Portal do Cidadão) — wrapper CLIENTE que busca a ficha + comentários (useFicha,
// 3.2) e monta a vista (derivarFicha, 3.1). Mesma razão de secao-em-tramitacao.tsx (A2.1): o fetch real
// mora aqui, não em page.tsx (SSR não resolve fetch relativo).

const fichaFake = {
  "proposicao-id": "p1",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 42,
  "urn-lex": "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
  ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
  "autor-texto": "Ver.ª Helena Matos",
  estado: "em_comissoes",
};

const fichaComNormaFake = {
  ...fichaFake,
  estado: "aprovada",
  norma: {
    "norma-id": "n1",
    "proposicao-id": "p1",
    "tipo-norma": "lei",
    numero: 1234,
    ano: 2026,
    urn: "urn:lex:br;ce;fortaleza:camara.municipal:lei:2026;1234",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
    "publicado-em": "2026-08-01T00:00:00Z",
    "veiculo-publicacao": "diario_oficial",
    "tem-texto": true,
  },
};

function mockFetch(porUrl: (url: string) => { ok: boolean; json?: () => Promise<unknown> }) {
  global.fetch = vi.fn(async (url: string) => {
    const r = porUrl(url);
    return { ok: r.ok, json: r.json ?? (async () => ({})) };
  }) as unknown as typeof fetch;
}

describe("SecaoFicha", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("carregando -> aria-busy honesto, sem conteúdo (review A2.3 item 5)", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch; // nunca resolve
    const { container } = render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
    expect(container.textContent).toBe("");
  });

  it("ficha ausente (404) -> 'matéria não encontrada' honesto, nunca quebra", async () => {
    mockFetch(() => ({ ok: false }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(screen.getByRole("status")).toBeTruthy());
    expect(screen.getByText(/matéria não encontrada/i)).toBeTruthy();
  });

  it("com o rito da Casa -> a faixa 'Onde este projeto está' segue a ordem do rito, com o nome que a Casa deu", async () => {
    const etapa = (chave: string, rotulo: string) => ({ chave, rotulo, terminal: false });
    const comRito = {
      ...fichaFake,
      estado: "instrucao",
      rito: {
        "ordem-unica": true,
        etapas: [etapa("entrada", "Entrada na Mesa"), etapa("instrucao", "Em instrução"), etapa("plenario_unico", "Plenário único")],
        atual: etapa("instrucao", "Em instrução"),
        anteriores: null,
        proximas: [etapa("plenario_unico", "Plenário único")],
      },
    };
    mockFetch((url) => ({ ok: true, json: async () => (url.endsWith("/comentarios") ? [] : comRito) }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(document.querySelector(".ficha-cab .num")?.textContent).toBe("PL 042/2026"));
    expect(
      screen.getByRole("img", {
        name: "Tramitação de PL 042/2026: concluídos Entrada na Mesa; atual Em instrução; pendente Plenário único.",
      }),
    ).toBeTruthy();
  });

  it("dado real -> cabeçalho (ref+ementa+autoria) + faixa de tramitação + permalink", async () => {
    mockFetch((url) => ({ ok: true, json: async () => (url.endsWith("/comentarios") ? [] : fichaFake) }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    // "PL 042/2026" aparece 2x (migalha + cabeçalho) — escopa ao cabeçalho, mesma disciplina de
    // document.getElementById(...) já usada em secao-em-tramitacao.test.tsx p/ desambiguar.
    await waitFor(() => expect(document.querySelector(".ficha-cab .num")?.textContent).toBe("PL 042/2026"));
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(
      "Cria o Programa Municipal de Hortas Comunitárias.",
    );
    expect(screen.getByText("Ver.ª Helena Matos").textContent).toBe("Ver.ª Helena Matos");
    expect(
      screen.getByRole("img", {
        name: "Tramitação de PL 042/2026: concluídos Protocolo; atual Comissões; pendente 1º turno, 2º turno, Sanção.",
      }),
    ).toBeTruthy();
    expect(screen.getByText(fichaFake["urn-lex"]).textContent).toBe(fichaFake["urn-lex"]);
    // a URN não aparece solta: vem rotulada, como na ficha da norma
    expect(screen.getByText("Identificador oficial:")).toBeTruthy();
  });

  it("sem resumo publicado: o aviso honesto diz que a Casa ainda não publicou (não que a IA caiu)", async () => {
    mockFetch((url) => ({ ok: true, json: async () => (url.endsWith("/comentarios") ? [] : fichaFake) }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(screen.getByText(/ainda não tem resumo em linguagem simples/i)).toBeTruthy());
    expect(document.querySelector(".resumo-ia")?.getAttribute("data-ia")).toBe("off");
    expect(screen.queryByText("Em poucas palavras")).toBeNull();
  });

  it("A.8b: resumo publicado -> selo, parágrafos e a revisão humana (IA ou Casa)", async () => {
    const comResumo = {
      ...fichaFake,
      resumo: {
        texto: "Cria hortas nos terrenos públicos sem uso.\n\nQuem planta divide a colheita.",
        versao: 2,
        "gerado-com-ia": true,
        "publicado-em": "2026-09-27T12:00:00Z",
      },
    };
    mockFetch((url) => ({ ok: true, json: async () => (url.endsWith("/comentarios") ? [] : comResumo) }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    expect(await screen.findByText("Em poucas palavras")).toBeTruthy();
    expect(document.querySelector(".resumo-ia")?.getAttribute("data-ia")).toBe("ativo");
    expect(screen.getByText("Cria hortas nos terrenos públicos sem uso.")).toBeTruthy();
    expect(screen.getByText("Quem planta divide a colheita.")).toBeTruthy();
    expect(document.querySelector(".resumo-revisao")?.textContent).toMatch(
      /^Resumo escrito com ajuda de IA e revisado pela equipe da Câmara em \d{2}\/\d{2}\/2026\. O texto oficial, acima, é o que vale\.$/,
    );
    cleanup();
    mockFetch((url) => ({
      ok: true,
      json: async () => (url.endsWith("/comentarios") ? [] : { ...comResumo, resumo: { ...comResumo.resumo, "gerado-com-ia": false } }),
    }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await screen.findByText("Em poucas palavras");
    expect(document.querySelector(".resumo-revisao")?.textContent).toMatch(/^Resumo escrito pela equipe da Câmara em /);
  });

  it("sem norma -> não mostra bloco 'virou lei'", async () => {
    mockFetch((url) => ({ ok: true, json: async () => (url.endsWith("/comentarios") ? [] : fichaFake) }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(document.querySelector(".ficha-cab .num")?.textContent).toBe("PL 042/2026"));
    expect(screen.queryByText(/virou lei/i)).toBeNull();
  });

  it("com norma publicada -> 'Lei nº 1234/2026' leva à página da norma; a URN não é impressa crua", async () => {
    mockFetch((url) => ({
      ok: true,
      json: async () => (url.endsWith("/comentarios") ? [] : fichaComNormaFake),
    }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(screen.getByText(/virou lei/i)).toBeTruthy());
    const link = screen.getByRole("link", { name: "Lei nº 1234/2026" });
    expect(link.getAttribute("href")).toBe("/portal/casa/fortaleza/leis/n1");
    expect(document.body.textContent).not.toContain("urn:lex:br;ce;fortaleza:camara.municipal:lei:2026;1234");
  });

  it("norma publicada SEM texto para baixar -> sem link de download, e diz que o texto ainda não foi publicado aqui", async () => {
    const semTexto = { ...fichaComNormaFake, norma: { ...fichaComNormaFake.norma, "tem-texto": false } };
    mockFetch((url) => ({ ok: true, json: async () => (url.endsWith("/comentarios") ? [] : semTexto) }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(screen.getByText(/virou lei/i)).toBeTruthy());
    expect(screen.queryByRole("link", { name: /texto oficial/i })).toBeNull();
    expect(document.querySelector('a[href*="/artefato"]')).toBeNull();
    expect(document.querySelector(".norma-publicada")?.textContent).toContain("O texto desta norma ainda não foi publicado aqui.");
    // a URN crua saiu do bloco (#149): o cidadão lê o título da norma, com link para a ficha dela
    expect(document.querySelector(".norma-publicada")?.textContent).not.toContain("urn:lex");
    expect(document.querySelector('.norma-publicada a[href*="/leis/"]')).not.toBeNull();
  });

  it("matéria com votação pública encerrada -> a ficha liga às votações dela (filtradas pela matéria)", async () => {
    mockFetch((url) => ({
      ok: true,
      json: async () =>
        url.endsWith("/comentarios")
          ? []
          : url.includes("/votacoes")
            ? { votacoes: [{ "votacao-id": "v1" }], total: 2, pagina: 1, "por-pagina": 20 }
            : fichaFake,
    }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    const link = await screen.findByRole("link", { name: "Ver as votações desta matéria" });
    expect(link.getAttribute("href")).toBe("/portal/casa/fortaleza/votacoes?materia=p1");
    expect(screen.getByRole("heading", { name: "Votações desta matéria" })).toBeTruthy();
  });

  it("matéria votada em plenário -> o ATO aparece no topo, com a data e o link; o selo de estado não muda", async () => {
    const votacao = {
      "votacao-id": "v9",
      "encerrada-em": "2026-09-12T18:30:00Z",
      "objeto-tipo": "proposicao",
      resultado: "aprovada",
      sessao: { "sessao-id": "s1", "tipo-sessao": "ordinaria", "numero-sequencial": 3 },
    };
    mockFetch((url) => ({
      ok: true,
      json: async () =>
        url.endsWith("/comentarios")
          ? []
          : url.includes("/votacoes")
            ? { votacoes: [votacao], total: 1, pagina: 1, "por-pagina": 20 }
            : fichaFake,
    }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    const ato = await waitFor(() => {
      const el = document.querySelector(".ultima-votacao");
      expect(el).not.toBeNull();
      return el as HTMLElement;
    });
    expect(ato.textContent).toContain("Última votação em plenário: a matéria foi aprovada em 12/09/2026.");
    expect(ato.querySelector("a")?.getAttribute("href")).toBe("/portal/casa/fortaleza/votacoes/v9");
    // o selo segue o estado do rito da Casa: o ato não o reescreve (numa matéria de dois turnos ele pode ser verdade)
    expect(document.querySelector(".estado-chip")?.textContent).not.toMatch(/aprovad/i);
    // uma busca só de votações: o ato e a seção "Votações desta matéria" usam a mesma resposta
    const chamadas = (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.filter((c) => String(c[0]).includes("/votacoes"));
    expect(chamadas).toHaveLength(1);
    expect(screen.getByRole("heading", { name: "Votações desta matéria" })).toBeTruthy();
  });

  it("redação final rejeitada -> o ato diz que foi a redação final; resultado desconhecido -> nada aparece", async () => {
    const base = { "votacao-id": "v2", "encerrada-em": "2026-09-20T12:00:00Z", sessao: { "sessao-id": "s2" } };
    for (const [votacao, esperado] of [
      [{ ...base, "objeto-tipo": "redacao_final", resultado: "rejeitada" }, "a redação final foi rejeitada em 20/09/2026."],
      [{ ...base, "objeto-tipo": "proposicao", resultado: "empate" }, null],
    ] as const) {
      cleanup();
      mockFetch((url) => ({
        ok: true,
        json: async () =>
          url.endsWith("/comentarios")
            ? []
            : url.includes("/votacoes")
              ? { votacoes: [votacao], total: 1, pagina: 1, "por-pagina": 20 }
              : fichaFake,
      }));
      render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
      // a seção de votações aparece nos dois casos: é o sinal de que a resposta de votações já chegou
      await screen.findByRole("heading", { name: "Votações desta matéria" });
      const ato = document.querySelector(".ultima-votacao");
      if (esperado) expect(ato?.textContent).toContain(esperado);
      else expect(ato).toBeNull();
    }
  });

  it("matéria sem votação pública -> nenhuma seção nem link de votações (nunca um link para lista vazia)", async () => {
    mockFetch((url) => ({
      ok: true,
      json: async () =>
        url.endsWith("/comentarios")
          ? []
          : url.includes("/votacoes")
            ? { votacoes: [], total: 0, pagina: 1, "por-pagina": 20 }
            : fichaFake,
    }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(document.querySelector(".ficha-cab .num")?.textContent).toBe("PL 042/2026"));
    await waitFor(() =>
      expect((global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.some((c) => String(c[0]).includes("/votacoes"))).toBe(true),
    );
    expect(screen.queryByRole("link", { name: /votações desta matéria/i })).toBeNull();
    expect(screen.queryByRole("heading", { name: "Votações desta matéria" })).toBeNull();
  });

  it("comentários aprovados -> lista read-only (corpo + data, sem autor), marcada como <ul>/<li> — review A2.3 item 2", async () => {
    mockFetch((url) => ({
      ok: true,
      json: async () =>
        url.endsWith("/comentarios")
          ? [{ id: "c1", corpo: "Apoio o projeto.", "criado-em": "2026-06-01T00:00:00Z" }]
          : fichaFake,
    }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(screen.getByText("Apoio o projeto.")).toBeTruthy());
    const lista = screen.getByRole("list", { name: /comentários aprovados/i });
    expect(lista.tagName).toBe("UL");
    expect(lista.querySelectorAll("li.cmt").length).toBe(1);
  });

  it("cada comentário oferece 'Denunciar' (feature 6.3) — sem sessão, leva ao gov.br e volta à matéria", async () => {
    mockFetch((url) => ({
      ok: true,
      json: async () =>
        url.endsWith("/comentarios")
          ? [{ id: "c1", corpo: "Apoio o projeto.", "criado-em": "2026-06-01T00:00:00Z" }]
          : fichaFake,
    }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    const link = await screen.findByRole("link", { name: /denunciar/i });
    expect(decodeURIComponent(link.getAttribute("href")!)).toContain("/portal/casa/fortaleza/materias/p1");
    // o comentário continua visível: quem decide é a moderação
    expect(screen.getByText("Apoio o projeto.")).toBeTruthy();
  });

  it("sem comentários aprovados -> estado honesto de vazio (não falha, não some a seção)", async () => {
    mockFetch((url) => ({ ok: true, json: async () => (url.endsWith("/comentarios") ? [] : fichaFake) }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(screen.getByText(/nenhum comentário aprovado/i)).toBeTruthy());
  });

  it("comentários falhos -> ficha continua completa; seção de comentários degrada isolada", async () => {
    mockFetch((url) => (url.endsWith("/comentarios") ? { ok: false } : { ok: true, json: async () => fichaFake }));
    render(<SecaoFicha ente="fortaleza" proposicaoId="p1" />);
    await waitFor(() => expect(document.querySelector(".ficha-cab .num")?.textContent).toBe("PL 042/2026"));
    expect(screen.getByText(/não foi possível carregar os comentários/i)).toBeTruthy();
  });
});
