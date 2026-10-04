import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { SeletorDeAnexos } from "./seletor-de-anexos";

const arq = (nome: string, tamanho = 10) => new File([new Uint8Array(tamanho)], nome);

/** O seletor com o estado que a tela dona dele teria. `limpar` simula o envio (a tela zera a selecao). */
function Tela({ inicial = [] as File[] }: { inicial?: File[] }) {
  const [arquivos, setArquivos] = useState<File[]>(inicial);
  return (
    <>
      <SeletorDeAnexos arquivos={arquivos} aoMudar={setArquivos} rotulo="Escolher arquivos" />
      <button type="button" onClick={() => setArquivos([])}>limpar</button>
    </>
  );
}
const escolher = (arquivos: File[]) => fireEvent.change(screen.getByLabelText("Escolher arquivos"), { target: { files: arquivos } });

afterEach(cleanup);

describe("SeletorDeAnexos — o que a pessoa lê e como se navega", () => {
  it("o alerta de recusa some quando a seleção muda (remover) ou é enviada (a tela zera)", () => {
    render(<Tela />);
    escolher([arq("a.pdf"), arq("programa.exe")]);
    expect(screen.getByRole("alert").textContent).toMatch(/programa.exe/);
    fireEvent.click(screen.getByRole("button", { name: "Remover a.pdf" }));
    expect(screen.queryByRole("alert")).toBeNull();
    escolher([arq("b.pdf"), arq("outro.exe")]);
    expect(screen.getByRole("alert")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "limpar" }));   // o envio zerou a seleção
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("o arquivo repetido é ignorado E avisado (sem alarme: é só um aviso)", () => {
    render(<Tela inicial={[arq("a.pdf")]} />);
    escolher([arq("a.pdf"), arq("b.csv")]);
    const aviso = screen.getByRole("status");
    expect(aviso.textContent).toMatch(/“a.pdf” já estava na lista/);
    expect(within(screen.getByRole("list", { name: "Arquivos escolhidos" })).getAllByRole("listitem")).toHaveLength(2);
    expect(screen.queryByRole("alert")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Remover b.csv" }));
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("a dica está ligada ao campo por aria-describedby", () => {
    render(<Tela />);
    const campo = screen.getByLabelText("Escolher arquivos");
    const id = campo.getAttribute("aria-describedby");
    expect(id).toBeTruthy();
    expect(document.getElementById(id!)?.textContent).toMatch(/Até 5 arquivos de até 10 MB/);
  });

  it("ao chegar em 5 o campo desliga E a tela diz por quê (não é um botão morto sem explicação)", () => {
    render(<Tela inicial={["1", "2", "3", "4"].map((n) => arq(`${n}.pdf`))} />);
    expect((screen.getByLabelText("Escolher arquivos") as HTMLInputElement).disabled).toBe(false);
    expect(screen.queryByText(/Limite de 5 arquivos atingido/)).toBeNull();
    escolher([arq("5.pdf")]);
    expect((screen.getByLabelText("Escolher arquivos") as HTMLInputElement).disabled).toBe(true);
    // o titulo do aviso vai em <b>: o texto esta' em dois nos, entao compara o texto do paragrafo inteiro
    expect(screen.getByText((_, el) => el?.tagName === "P" && /Limite de 5 arquivos atingido\. Remova um para escolher outro\./.test(el.textContent ?? ""))).toBeTruthy();
    expect(screen.getByLabelText("Escolher arquivos").getAttribute("aria-describedby")).toMatch(/\S/);
  });

  it("o foco não se perde ao remover: vai para o próximo 'Remover'; no último, para o campo", () => {
    render(<Tela inicial={[arq("a.pdf"), arq("b.pdf"), arq("c.pdf")]} />);
    fireEvent.click(screen.getByRole("button", { name: "Remover a.pdf" }));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Remover b.pdf" }));
    fireEvent.click(screen.getByRole("button", { name: "Remover c.pdf" }));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Remover b.pdf" }));   // o do índice anterior
    fireEvent.click(screen.getByRole("button", { name: "Remover b.pdf" }));
    expect(document.activeElement).toBe(screen.getByLabelText("Escolher arquivos"));   // lista vazia: volta ao campo
  });
});
