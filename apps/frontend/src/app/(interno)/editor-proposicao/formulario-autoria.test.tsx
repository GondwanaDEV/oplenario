import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { FormularioProposicao } from "./formulario-proposicao";

// Item 3 do lote 05/10: a autoria era texto livre e `autor_id` ficava nulo, então a matéria não entrava no
// perfil público do vereador (transparencia.materia.autor_id). Backend: `validar-autor!`
// (legislativo/controllers.clj) aceita `autor-id` só com `autor-tipo` "vereador" na MESMA escrita, exige
// `autor-texto` junto, e confere que o id é um vereador desta Casa. Mesa, comissão, Executivo e iniciativa
// popular seguem por texto, sem id.

const vereadores = [
  { id: "v-helena", nome: "Helena Matos Lima", "nome-parlamentar": "Helena Matos", "estado-mandato": "vigente", "com-acesso": true },
  { id: "v-caio", nome: "Caio Reis", "estado-mandato": "vigente", "com-acesso": false },
  { id: "v-ana", nome: "Ana Licenciada", "nome-parlamentar": "Ana Melo", "estado-mandato": "licenciado", "com-acesso": true },
  { id: "v-ex", nome: "Bruno Anterior", "estado-mandato": "encerrado", "com-acesso": false },
];

function simularVereadores(resposta: { ok: boolean } = { ok: true }) {
  global.fetch = vi.fn(async (url: string) => {
    if (url === "/api/cadastros/vereadores" && resposta.ok) {
      return { ok: true, status: 200, json: async () => ({ vereadores }) } as Response;
    }
    return { ok: false, status: 500, json: async () => ({}) } as Response;
  }) as unknown as typeof fetch;
}

function renderizar(props: Partial<React.ComponentProps<typeof FormularioProposicao>> = {}) {
  const aoSubmeter = vi.fn();
  render(
    <FormularioProposicao
      aoSubmeter={aoSubmeter}
      enviando={false}
      erro={null}
      rotuloAcaoPrimaria="Protocolar"
      token="tok"
      {...props}
    />,
  );
  return aoSubmeter;
}

function escolherAutor(tipo: string) {
  fireEvent.change(screen.getByLabelText("Autor"), { target: { value: tipo } });
}

describe("FormularioProposicao — autoria ligada ao vereador cadastrado", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("autor 'Vereador' oferece os vereadores em exercício, pelo nome parlamentar, e não pede o nome por texto", async () => {
    simularVereadores();
    renderizar();
    escolherAutor("vereador");
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    expect(Array.from(select.options).map((o) => [o.value, o.textContent])).toEqual([
      ["", "Escolha o vereador…"],
      ["v-helena", "Helena Matos"],
      ["v-caio", "Caio Reis"],
    ]);
    expect(screen.queryByLabelText("Nome do autor")).toBeNull();
  });

  it("escolher o vereador envia autorTipo, autorId e o nome dele em autorTexto", async () => {
    simularVereadores();
    const aoSubmeter = renderizar();
    fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "Cria o Programa X" } });
    escolherAutor("vereador");
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    fireEvent.change(select, { target: { value: "v-helena" } });
    fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));
    expect(aoSubmeter).toHaveBeenCalledWith(
      expect.objectContaining({ autorTipo: "vereador", autorId: "v-helena", autorTexto: "Helena Matos" }),
    );
  });

  it("vereador sem nome parlamentar usa o nome civil", async () => {
    simularVereadores();
    const aoSubmeter = renderizar();
    escolherAutor("vereador");
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    fireEvent.change(select, { target: { value: "v-caio" } });
    fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));
    expect(aoSubmeter).toHaveBeenCalledWith(expect.objectContaining({ autorId: "v-caio", autorTexto: "Caio Reis" }));
  });

  it("criar com autor 'Vereador' exige escolher o vereador", async () => {
    simularVereadores();
    renderizar();
    escolherAutor("vereador");
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    expect(select.required).toBe(true);
  });

  it("Mesa, comissão, Executivo e iniciativa popular seguem por texto, sem autorId", () => {
    simularVereadores();
    const aoSubmeter = renderizar();
    for (const tipo of ["mesa", "comissao", "executivo", "cidadao"]) {
      escolherAutor(tipo);
      expect(screen.queryByLabelText("Vereador autor")).toBeNull();
      fireEvent.change(screen.getByLabelText("Nome do autor"), { target: { value: "Quem propõe" } });
      fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "x" } });
      fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));
      const corpo = aoSubmeter.mock.calls.at(-1)![0];
      expect(corpo.autorTipo).toBe(tipo);
      expect(corpo.autorTexto).toBe("Quem propõe");
      expect(corpo.autorId).toBeUndefined();
    }
  });

  it("trocar de Vereador para outro tipo descarta o id e o nome do vereador (autoria é pública)", async () => {
    simularVereadores();
    const aoSubmeter = renderizar();
    escolherAutor("vereador");
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    fireEvent.change(select, { target: { value: "v-helena" } });
    escolherAutor("mesa");
    expect((screen.getByLabelText("Nome do autor") as HTMLInputElement).value).toBe("");
    fireEvent.change(screen.getByLabelText("Nome do autor"), { target: { value: "Mesa Diretora" } });
    fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));
    const corpo = aoSubmeter.mock.calls.at(-1)![0];
    expect(corpo.autorId).toBeUndefined();
    expect(corpo.autorTexto).toBe("Mesa Diretora");
  });

  it("na edição, o vereador já ligado vem selecionado — mesmo licenciado — e o id segue no envio", async () => {
    simularVereadores();
    const aoSubmeter = renderizar({
      valorInicial: { tipo: "projeto_lei", ano: 2026, ementa: "Ementa", autorTipo: "vereador", autorId: "v-ana", autorTexto: "Ana Melo" },
      rotuloAcaoPrimaria: "Salvar alterações",
    });
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    expect(select.value).toBe("v-ana");
    expect(Array.from(select.options).map((o) => o.value)).not.toContain("v-ex");
    fireEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));
    expect(aoSubmeter).toHaveBeenCalledWith(
      expect.objectContaining({ autorTipo: "vereador", autorId: "v-ana", autorTexto: "Ana Melo" }),
    );
  });

  it("na edição de matéria antiga (vereador só por texto, sem vínculo), o texto é preservado e dá para ligar depois", async () => {
    simularVereadores();
    const aoSubmeter = renderizar({
      valorInicial: { tipo: "projeto_lei", ano: 2026, ementa: "Ementa", autorTipo: "vereador", autorTexto: "Ver. Helena" },
      rotuloAcaoPrimaria: "Salvar alterações",
    });
    const select = (await screen.findByLabelText("Vereador autor")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
    expect(select.required).toBe(false);
    expect(screen.getByText(/Ver\. Helena/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));
    const corpo = aoSubmeter.mock.calls.at(-1)![0];
    expect(corpo.autorTexto).toBe("Ver. Helena");
    expect(corpo.autorId).toBeUndefined();
  });

  it("sem conseguir carregar os vereadores, avisa e deixa informar o nome por texto (sem vínculo)", async () => {
    simularVereadores({ ok: false });
    const aoSubmeter = renderizar();
    escolherAutor("vereador");
    expect(await screen.findByText(/não foi possível carregar os vereadores/i)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Nome do autor"), { target: { value: "Helena Matos" } });
    fireEvent.change(screen.getByLabelText(/ementa/i), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "Protocolar" }));
    const corpo = aoSubmeter.mock.calls.at(-1)![0];
    expect(corpo.autorTexto).toBe("Helena Matos");
    expect(corpo.autorId).toBeUndefined();
  });
});
