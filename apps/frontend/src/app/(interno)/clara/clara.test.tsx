import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { createRef } from "react";
import { AVISO_DE_REGISTRO, Clara } from "./clara";
import { ProvedorDaDica, dicaDaMateria, useDicaDaClara } from "./dica";

const SSE = (conversa: string, interacao = "i-1") =>
  [
    'event: passo\ndata: {"ferramenta":"situacao_da_materia","argumentos":{"tipo":"projeto_lei","sequencial":11,"ano":2026},"ok":true}',
    'event: resposta\ndata: {"texto":"Está nas comissões. [[ferramenta:situacao_da_materia#1 | estado: comissoes]]","citacoes":[{"fonte-id":"ferramenta:situacao_da_materia#1","trecho":"estado: comissoes","status":"conferida"}],"paragrafos-sem-fonte":[],"incerteza":"normal","modelo":"fake-1","contaminado":false,"execucao-ia":"ia-7"}',
    `event: fim\ndata: {"execucao-id":"e1","interacao-id":"${interacao}","conversa-id":"${conversa}"}`,
    "",
  ].join("\n\n");

const agoraIso = () => new Date().toISOString();

const HISTORICO = () => ({
  interacoes: [
    { id: "i-1", "conversa-id": "c-1", "identidade-id": "p-1", pergunta: "Qual a situação do PL 11/2026?", desfecho: "resposta", "ocorrido-em": agoraIso(), "n-fontes": 1, "n-propostas": 0 },
  ],
  mais: true,
  antes: "2026-10-01T12:00:00Z",
});

const CONVERSA = (integra = true) => ({
  "conversa-id": "c-1",
  "identidade-id": "p-1",
  nome: "Rita Campos",
  interacoes: [
    {
      id: "i-1", pergunta: "Qual a situação do PL 11/2026?", desfecho: "resposta",
      resposta: { texto: "Está nas comissões. [[ferramenta:situacao_da_materia#1 | estado: comissoes]]",
        citacoes: [{ "fonte-id": "ferramenta:situacao_da_materia#1", trecho: "estado: comissoes", status: "conferida" }],
        "paragrafos-sem-fonte": [], incerteza: "normal", contaminado: false },
      passos: [{ ferramenta: "situacao_da_materia", argumentos: { tipo: "projeto_lei", sequencial: 11, ano: 2026 }, ok: true }],
      propostas: [], modelo: "openai/gpt-oss-120b", "execucao-ia": "ia-7", "ocorrido-em": "2026-10-05T13:42:00Z",
      "conteudo-sha256": "a".repeat(64), integra,
    },
    {
      id: "i-2", pergunta: "E o relator?", desfecho: "indisponivel", resposta: null, passos: [], propostas: [], modelo: null,
      "execucao-ia": null, "ocorrido-em": "2026-10-05T13:44:00Z", "conteudo-sha256": "b".repeat(64), integra,
    },
  ],
});

type Resposta = { status?: number; texto?: string; json?: unknown };

/** fetch falso por rota: cada chamada devolve a próxima resposta da fila daquela rota. */
function mockar(rotas: Record<string, Resposta[]>) {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
    const chave = Object.keys(rotas).find((k) => url.startsWith(k));
    const r = (chave ? rotas[chave].shift() : undefined) ?? { status: 500 };
    const status = r.status ?? 200;
    return { ok: status < 400, status, text: async () => r.texto ?? "", json: async () => r.json } as Response;
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

function abrir() {
  fireEvent.click(screen.getByRole("button", { name: /Pergunte à Clara/ }));
  return screen.getByRole("complementary", { name: "Clara" });
}

function perguntar(texto: string) {
  fireEvent.change(screen.getByLabelText("Sua pergunta"), { target: { value: texto } });
  fireEvent.click(screen.getByRole("button", { name: "Perguntar" }));
}

describe("Clara — o painel retrátil", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    delete document.documentElement.dataset.clara;
  });

  it("recolhida, é só o botão; abre no campo, diz que fica guardado, e Esc devolve o foco ao botão", () => {
    render(<Clara token="tok" />);
    expect(document.documentElement.dataset.clara).toBe("recolhido");
    expect(screen.queryByRole("complementary", { name: "Clara" })).toBeNull();
    const botao = screen.getByRole("button", { name: /Pergunte à Clara/ });
    expect(botao.getAttribute("aria-expanded")).toBe("false");

    const painel = abrir();
    expect(document.documentElement.dataset.clara).toBe("aberto");
    expect(document.activeElement).toBe(screen.getByLabelText("Sua pergunta"));
    expect(within(painel).getByText(AVISO_DE_REGISTRO)).toBeTruthy();
    expect(within(painel).getByText("Pergunte o que precisar saber da Casa")).toBeTruthy();

    fireEvent.keyDown(screen.getByLabelText("Sua pergunta"), { key: "Escape" });
    expect(document.documentElement.dataset.clara).toBe("recolhido");
    expect(document.activeElement).toBe(screen.getByRole("button", { name: /Pergunte à Clara/ }));
  });

  it("Ctrl + / abre e recolhe de qualquer lugar; expandir e reduzir mudam o tamanho", () => {
    mockar({ "/api/agente/historico": [{ json: HISTORICO() }] });
    render(<Clara token="tok" />);
    fireEvent.keyDown(document.body, { key: "/", ctrlKey: true });
    expect(document.documentElement.dataset.clara).toBe("aberto");
    fireEvent.click(screen.getByRole("button", { name: "Expandir a Clara" }));
    expect(document.documentElement.dataset.clara).toBe("expandido");
    fireEvent.click(screen.getByRole("button", { name: "Reduzir a Clara para a janela" }));
    expect(document.documentElement.dataset.clara).toBe("aberto");
    fireEvent.keyDown(document.body, { key: "/", ctrlKey: true });
    expect(document.documentElement.dataset.clara).toBe("recolhido");
  });

  it("a segunda pergunta continua a conversa que o `fim` devolveu; 'Nova conversa' começa outra", async () => {
    const fetchMock = mockar({
      "/api/agente/perguntas": [{ texto: SSE("c-1") }, { texto: SSE("c-1", "i-2") }, { texto: SSE("c-9", "i-3") }],
      "/api/agente/historico": [{ json: HISTORICO() }],
    });
    render(<Clara token="tok" />);
    abrir();
    perguntar("  situação do PL 11/2026? ");
    expect(await screen.findByText("Consultou a situação do PL 11/2026")).toBeTruthy();
    expect(screen.getByText("Resposta gerada por IA")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Fonte 1" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Reportar erro" })).toBeTruthy();

    perguntar("e quem relata?");
    await screen.findByText("e quem relata?");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([u]) => u === "/api/agente/perguntas")).toHaveLength(2));
    const corpos = () =>
      fetchMock.mock.calls
        .filter(([u]) => u === "/api/agente/perguntas")
        .map(([, init]) => JSON.parse((init as RequestInit).body as string));
    expect(corpos()[0]).toEqual({ pergunta: "situação do PL 11/2026?" });
    expect(corpos()[1]).toEqual({ pergunta: "e quem relata?", conversa: "c-1" });
    await screen.findAllByText("Consultou a situação do PL 11/2026");

    fireEvent.click(screen.getByRole("button", { name: "Histórico de conversas" }));
    fireEvent.click(await screen.findByRole("button", { name: "Nova conversa" }));
    expect(screen.getByText("Pergunte o que precisar saber da Casa")).toBeTruthy();
    perguntar("outra coisa");
    await vi.waitFor(() => expect(corpos()).toHaveLength(3));
    expect(corpos()[2]).toEqual({ pergunta: "outra coisa" });
  });

  it("indisponível: diz o que houve e que a pergunta ficou no histórico", async () => {
    mockar({
      "/api/agente/perguntas": [
        { texto: 'event: indisponivel\ndata: {"mensagem":"A Clara está indisponível agora. Siga pela tela — nada do seu trabalho depende dela."}\n\nevent: fim\ndata: {"execucao-id":"e1","interacao-id":"i-1","conversa-id":"c-1"}\n\n' },
      ],
    });
    render(<Clara token="tok" />);
    abrir();
    perguntar("oi, tudo bem?");
    expect(await screen.findByText("A Clara está indisponível agora")).toBeTruthy();
    expect(screen.getByText(/Sua pergunta ficou no histórico, sem resposta\./)).toBeTruthy();
  });

  it("o histórico agrupa por dia, pagina pelo instante e abre a conversa guardada como registro, sem campo", async () => {
    const fetchMock = mockar({
      "/api/agente/historico": [{ json: HISTORICO() }, { json: { interacoes: [], mais: false, antes: null } }],
      "/api/agente/conversas/": [{ json: CONVERSA() }],
    });
    render(<Clara token="tok" />);
    abrir();
    fireEvent.click(screen.getByRole("button", { name: "Histórico de conversas" }));
    expect(await screen.findByText("Hoje")).toBeTruthy();
    expect(screen.getByText(/1 fonte/)).toBeTruthy();
    expect(screen.getByText(/Nenhuma conversa se apaga/)).toBeTruthy();
    expect(screen.queryByLabelText("Sua pergunta")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Ver conversas anteriores" }));
    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([u]) => u)).toContain("/api/agente/historico?antes=2026-10-01T12%3A00%3A00Z"));

    fireEvent.click(await screen.findByRole("button", { name: /Qual a situação do PL 11\/2026\?/ }));
    const registro = await screen.findByRole("group", { name: "Registro desta conversa" });
    expect(fetchMock.mock.calls.map(([u]) => u)).toContain("/api/agente/conversas/c-1");
    expect(within(registro).getByText("Rita Campos")).toBeTruthy();
    expect(within(registro).getByText("05/10/2026 10h42–10h44")).toBeTruthy();
    expect(within(registro).getByText("openai/gpt-oss-120b")).toBeTruthy();
    expect(within(registro).getByText("1 de 1 citações conferidas")).toBeTruthy();
    expect(within(registro).getByText("Confere com o que foi gravado")).toBeTruthy();
    // duas perguntas: cada uma tem a sua linha na trilha, e o link fica embaixo de cada uma
    expect(within(registro).getByText("Uma linha por pergunta: o link fica em cada uma")).toBeTruthy();
    const links = screen.getAllByRole("link", { name: "Ver esta pergunta na trilha" }).map((l) => l.getAttribute("href"));
    expect(links).toEqual([
      "/auditoria?recurso-tipo=interacao_assistente&recurso-id=i-1&token=tok",
      "/auditoria?recurso-tipo=interacao_assistente&recurso-id=i-2&token=tok",
    ]);
    expect(screen.getByText("E o relator?")).toBeTruthy();
    expect(screen.getByText(/Ficou sem resposta: a Clara estava indisponível/)).toBeTruthy();
    expect(screen.queryByLabelText("Sua pergunta")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Voltar ao histórico" }));
    expect(await screen.findByText("Hoje")).toBeTruthy();
  });

  it("conversa que não confere com o hash gravado é dita, não escondida", async () => {
    mockar({ "/api/agente/historico": [{ json: HISTORICO() }], "/api/agente/conversas/": [{ json: CONVERSA(false) }] });
    render(<Clara token="tok" />);
    abrir();
    fireEvent.click(screen.getByRole("button", { name: "Histórico de conversas" }));
    fireEvent.click(await screen.findByRole("button", { name: /Qual a situação do PL 11\/2026\?/ }));
    expect(await screen.findByText(/NÃO confere com o que foi gravado/)).toBeTruthy();
  });

  it("histórico fora do ar vira frase com 'Tentar de novo', nunca tela quebrada", async () => {
    mockar({ "/api/agente/historico": [{ status: 503 }, { json: HISTORICO() }] });
    render(<Clara token="tok" />);
    abrir();
    fireEvent.click(screen.getByRole("button", { name: "Histórico de conversas" }));
    expect(await screen.findByText(/Não foi possível abrir o histórico agora/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Tentar de novo" }));
    expect(await screen.findByText("Hoje")).toBeTruthy();
  });

  it("expandida no computador, o histórico fica ao lado", async () => {
    mockar({ "/api/agente/historico": [{ json: HISTORICO() }] });
    render(<Clara token="tok" />);
    abrir();
    fireEvent.click(screen.getByRole("button", { name: "Expandir a Clara" }));
    const lateral = screen.getByRole("navigation", { name: "Suas conversas" });
    expect(await within(lateral).findByText("Hoje")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Histórico de conversas" })).toBeNull();
    expect(screen.getByLabelText("Sua pergunta")).toBeTruthy();
  });

  it("no celular a folha é modal: a página fica inerte enquanto ela está aberta, e o véu recolhe", () => {
    const original = window.matchMedia;
    window.matchMedia = ((q: string) => ({
      matches: q === "(max-width: 760px)", media: q, addEventListener: () => {}, removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia;
    try {
      const moldura = createRef<HTMLDivElement>();
      render(
        <>
          <div ref={moldura}>página</div>
          <Clara token="tok" moldura={moldura} />
        </>,
      );
      expect(moldura.current?.hasAttribute("inert")).toBe(false);
      abrir();
      expect(moldura.current?.hasAttribute("inert")).toBe(true);
      const veu = document.querySelector(".ast-veu") as HTMLButtonElement;
      act(() => veu.click());
      expect(moldura.current?.hasAttribute("inert")).toBe(false);
      expect(document.documentElement.dataset.clara).toBe("recolhido");
    } finally {
      window.matchMedia = original;
    }
  });

  it("a barra de comando da tela (.comando) é medida, também quando chega depois: o botão sobe acima dela", async () => {
    const original = HTMLElement.prototype.getBoundingClientRect;
    HTMLElement.prototype.getBoundingClientRect = function (this: HTMLElement) {
      if (!this.classList.contains("comando")) return original.call(this);
      const top = window.innerHeight - 68;
      return { x: 0, y: top, left: 0, top, width: 1024, right: 1024, height: 68, bottom: window.innerHeight } as DOMRect;
    };
    try {
      const moldura = createRef<HTMLDivElement>();
      render(
        <>
          <div ref={moldura} />
          <Clara token="tok" moldura={moldura} />
        </>,
      );
      const raiz = document.documentElement;
      expect(raiz.style.getPropertyValue("--comando-altura")).toBe("0px");
      const barra = document.createElement("div");
      barra.className = "comando";
      act(() => {
        moldura.current?.appendChild(barra);
      });
      await vi.waitFor(() => expect(raiz.style.getPropertyValue("--comando-altura")).toBe("68px"));
      cleanup();
      expect(raiz.style.getPropertyValue("--comando-altura")).toBe("");
    } finally {
      HTMLElement.prototype.getBoundingClientRect = original;
    }
  });

  it("busca nas conversas: vai ao core com q, diz quando não achou e limpa", async () => {
    const fetchMock = mockar({
      "/api/agente/historico": [{ json: HISTORICO() }, { json: { interacoes: [], mais: false, antes: null } }, { json: HISTORICO() }],
    });
    render(<Clara token="tok" />);
    abrir();
    fireEvent.click(screen.getByRole("button", { name: "Histórico de conversas" }));
    await screen.findByText("Hoje");
    const busca = screen.getByLabelText("Buscar nas suas conversas");
    fireEvent.change(busca, { target: { value: "v" } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    expect(screen.getByText("Escreva pelo menos 2 letras.")).toBeTruthy();
    fireEvent.change(busca, { target: { value: " veto " } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    expect(await screen.findByText("Nenhuma conversa com “veto”.")).toBeTruthy();
    expect(fetchMock.mock.calls.map(([u]) => u)).toContain("/api/agente/historico?q=veto");
    expect(screen.queryByRole("button", { name: "Nova conversa" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Limpar busca" }));
    expect(await screen.findByText("Hoje")).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([u]) => u === "/api/agente/historico")).toHaveLength(2);
  });

  it("a dica da tela: 'Nesta tela: PL 42/2026' começa a pergunta no campo, sem enviar nada", async () => {
    const fetchMock = mockar({});
    function Ficha() {
      useDicaDaClara(dicaDaMateria("projeto_lei", 42, 2026));
      return <p>a ficha</p>;
    }
    render(
      <ProvedorDaDica>
        <Ficha />
        <Clara token="tok" />
      </ProvedorDaDica>,
    );
    abrir();
    expect(screen.getByText("PL 42/2026")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Perguntar sobre esta matéria" }));
    const campo = screen.getByLabelText("Sua pergunta") as HTMLTextAreaElement;
    expect(campo.value).toBe("Sobre o PL 42/2026, ");
    await vi.waitFor(() => expect(document.activeElement).toBe(campo));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sem dica (a tela não diz do que trata), a faixa não aparece", () => {
    render(
      <ProvedorDaDica>
        <Clara token="tok" />
      </ProvedorDaDica>,
    );
    abrir();
    expect(screen.queryByText(/Nesta tela/)).toBeNull();
  });
});
