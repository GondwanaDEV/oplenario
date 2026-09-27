import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PainelResumo } from "./painel-resumo";

const versao = (n: number, desatualizado = false) => ({
  versao: n,
  "conteudo-sha256": "sha256:c",
  desatualizado,
  "origem-redacao": "gerada_automaticamente",
  "rascunho-id": "r1",
  "modelo-llm-id": "fake:fake-1",
  "prompt-versao": "resumo-v1",
  "publicado-por": "u1",
  "publicado-em": "2026-09-27T02:00:00Z",
});

const ponteiro = {
  situacao: "pronto",
  "rascunho-id": "r1",
  desatualizado: false,
  "modelo-llm-id": "fake:fake-1",
  "prompt-versao": "resumo-v1",
  incerteza: "revisar_com_atencao",
  "n-citacoes": 2,
  "n-citacoes-conferidas": 2,
  "n-paragrafos-sem-fonte": 1,
  "categoria-erro": null,
  retentavel: null,
  "ocorrido-em": "2026-09-27T01:00:00Z",
};

const rascunho = {
  "rascunho-id": "r1",
  texto: "Esta proposição cria hortas. [[proposicao:p#ementa | cria hortas]]\n\nSe aprovada, vale no município.",
  "texto-limpo": "Esta proposição cria hortas.\n\nSe aprovada, vale no município.",
  incerteza: { nivel: "revisar_com_atencao", motivos: ["conteudo_de_terceiro", "sem_fonte"] },
  citacoes: [{ "fonte-id": "proposicao:p#ementa", trecho: "cria hortas", status: "conferida", rotulo: "PL 7/2026 — ementa" }],
  "paragrafos-sem-fonte": [1],
  "modelo-llm-id": "fake:fake-1",
  "prompt-versao": "resumo-v1",
  desatualizado: false,
};

type Resp = { status: number; body: unknown };

function mockar(rotas: { resumo?: Resp; rascunho?: Resp; publicar?: Resp }) {
  const resumo = rotas.resumo ?? { status: 200, body: { "proposicao-id": "p", rascunho: ponteiro, atual: null, versoes: [] } };
  const fetchMock = vi.fn(async (url: string, opts?: { method?: string }) => {
    const r: Resp =
      opts?.method === "POST"
        ? (rotas.publicar ?? { status: 201, body: { versao: 1, "conteudo-sha256": "sha256:n" } })
        : url.includes("/rascunhos/")
          ? (rotas.rascunho ?? { status: 200, body: rascunho })
          : resumo;
    return { ok: r.status < 300, status: r.status, json: async () => r.body } as Response;
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

describe("PainelResumo", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("revisa o rascunho da IA, usa, confirma e publica com o rascunho de origem", async () => {
    const fetchMock = mockar({});
    render(<PainelResumo proposicaoId="p" token="tok" />);
    expect(await screen.findByText("Rascunho da IA pronto para revisar")).toBeTruthy();
    expect(screen.getByText("Esta matéria ainda não tem resumo publicado no portal.")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Revisar o rascunho" }));
    expect(await screen.findByText("sem fonte — confira")).toBeTruthy();
    expect(screen.getByText(/o resumo parte de um texto escrito pelo autor da matéria/)).toBeTruthy();
    expect(screen.getByTitle("PL 7/2026 — ementa").textContent).toBe("1");

    fireEvent.click(screen.getByRole("button", { name: "Usar este rascunho" }));
    const campo = screen.getByLabelText("Resumo em linguagem simples") as HTMLTextAreaElement;
    expect(campo.value).toBe("Esta proposição cria hortas.\n\nSe aprovada, vale no município.");
    fireEvent.change(campo, { target: { value: "Cria hortas comunitárias em terrenos sem uso." } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar para publicar" }));
    expect(screen.getByText("Publicar a versão 1 no portal?")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Publicar o resumo" }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/legislativo/proposicoes/p/resumo",
        expect.objectContaining({
          method: "POST",
          body: JSON.stringify({ texto: "Cria hortas comunitárias em terrenos sem uso.", "rascunho-id": "r1" }),
        }),
      ),
    );
    expect(await screen.findByText("Versão 1 publicada no portal.")).toBeTruthy();
  });

  it("mostra o publicado, avisa quando o texto mudou e escreve à mão sem rascunho de origem", async () => {
    const fetchMock = mockar({
      resumo: {
        status: 200,
        body: {
          "proposicao-id": "p",
          rascunho: null,
          atual: { versao: versao(2, true), texto: "Cria hortas." },
          versoes: [versao(2, true), versao(1)],
        },
      },
    });
    render(<PainelResumo proposicaoId="p" token="tok" />);
    expect(await screen.findByText("Cria hortas.")).toBeTruthy();
    expect(screen.getByText(/O texto da proposição mudou depois deste resumo/)).toBeTruthy();
    expect(screen.getByText("A IA ainda não redigiu o resumo")).toBeTruthy();
    expect(screen.getByRole("region", { name: "Versões publicadas" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Editar e publicar nova versão" }));
    fireEvent.click(screen.getByRole("button", { name: "Revisar para publicar" }));
    fireEvent.click(screen.getByRole("button", { name: "Publicar o resumo" }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/legislativo/proposicoes/p/resumo",
        expect.objectContaining({ body: JSON.stringify({ texto: "Cria hortas." }) }),
      ),
    );
  });

  it("IA fora ao abrir o rascunho: a mensagem R-IA-1 e o caminho de volta", async () => {
    mockar({ rascunho: { status: 503, body: { erro: "A IA está indisponível agora. Siga pela tela — o resumo pode ser escrito à mão." } } });
    render(<PainelResumo proposicaoId="p" token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "Revisar o rascunho" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/Siga pela tela/);
    fireEvent.click(screen.getByRole("button", { name: "Voltar" }));
    expect(screen.getByRole("button", { name: "Escrever à mão" })).toBeTruthy();
  });

  it("conflito na publicação volta ao editor com a frase do servidor", async () => {
    mockar({ publicar: { status: 409, body: { erro: "outra versao do resumo foi publicada ao mesmo tempo: recarregue" } } });
    render(<PainelResumo proposicaoId="p" token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "Escrever à mão" }));
    fireEvent.change(screen.getByLabelText("Resumo em linguagem simples"), { target: { value: "X" } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar para publicar" }));
    fireEvent.click(screen.getByRole("button", { name: "Publicar o resumo" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/recarregue/);
    expect(screen.getByRole("button", { name: "Revisar para publicar" })).toBeTruthy();
  });

  it("vereador: a aba explica que o resumo é da secretaria", async () => {
    mockar({ resumo: { status: 403, body: {} } });
    render(<PainelResumo proposicaoId="p" token="tok" />);
    expect(await screen.findByText("O resumo para o portal é revisado e publicado pela secretaria.")).toBeTruthy();
  });
});
