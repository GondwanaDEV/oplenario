import { describe, expect, it } from "vitest";
import {
  ACCEPT_DO_SELETOR,
  LIMITE_DE_ANEXOS,
  TAMANHO_MAXIMO_DO_ANEXO,
  TIPOS_ACEITOS_EM_TEXTO,
  adicionarArquivos,
  extensaoDe,
  motivoDeRecusa,
  resumoDoEnvio,
  rotuloDoTipo,
  type ItemDeEnvio,
} from "./anexos-do-atendimento";

const arquivo = (nome: string, tamanho = 100) => new File([new Uint8Array(tamanho)], nome);

describe("os limites e os tipos aceitos (o servidor confere de novo)", () => {
  it("até 5 arquivos de até 10 MB, nove tipos", () => {
    expect(LIMITE_DE_ANEXOS).toBe(5);
    expect(TAMANHO_MAXIMO_DO_ANEXO).toBe(10 * 1024 * 1024);
    expect(TIPOS_ACEITOS_EM_TEXTO).toBe("PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS");
    expect(ACCEPT_DO_SELETOR).toBe(".pdf,.png,.jpg,.jpeg,.txt,.csv,.docx,.xlsx,.odt,.ods");
  });

  it("a extensão é a última, em minúsculas", () => {
    expect(extensaoDe("Folha.PDF")).toBe("pdf");
    expect(extensaoDe("a.tar.gz")).toBe("gz");
    expect(extensaoDe("sem")).toBeNull();
    expect(extensaoDe("termina.")).toBeNull();
  });
});

describe("motivoDeRecusa — o que o seletor barra antes de enviar", () => {
  it("tipo fora da lista, sem extensão, vazio e grande demais; o resto passa", () => {
    expect(motivoDeRecusa(arquivo("folha.pdf"))).toBeNull();
    expect(motivoDeRecusa(arquivo("FOTO.JPG"))).toBeNull();
    expect(motivoDeRecusa(arquivo("programa.exe"))).toMatch(/“programa.exe” não é de um tipo aceito \(PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS\)/);
    expect(motivoDeRecusa(arquivo("planilha.xls"))).toMatch(/não é de um tipo aceito/);
    expect(motivoDeRecusa(arquivo("semextensao"))).toMatch(/não é de um tipo aceito/);
    expect(motivoDeRecusa(arquivo("vazio.pdf", 0))).toMatch(/“vazio.pdf” está vazio/);
    expect(motivoDeRecusa({ name: "grande.pdf", size: TAMANHO_MAXIMO_DO_ANEXO + 1 })).toMatch(/“grande.pdf” passa de 10 MB/);
    expect(motivoDeRecusa({ name: "no-limite.pdf", size: TAMANHO_MAXIMO_DO_ANEXO })).toBeNull();
  });
});

describe("adicionarArquivos", () => {
  it("junta os válidos, recusa com o motivo, não repete o mesmo arquivo", () => {
    const r = adicionarArquivos([arquivo("a.pdf")], [arquivo("a.pdf"), arquivo("b.csv"), arquivo("c.exe")]);
    expect(r.arquivos.map((f) => f.name)).toEqual(["a.pdf", "b.csv"]);
    expect(r.recusados).toHaveLength(1);
    expect(r.recusados[0]).toMatch(/c.exe/);
  });

  it(`para em ${LIMITE_DE_ANEXOS}: o excedente é recusado com o motivo`, () => {
    const atuais = ["1.pdf", "2.pdf", "3.pdf", "4.pdf"].map((n) => arquivo(n));
    const r = adicionarArquivos(atuais, [arquivo("5.pdf"), arquivo("6.pdf"), arquivo("7.pdf")]);
    expect(r.arquivos).toHaveLength(5);
    expect(r.recusados).toHaveLength(2);
    expect(r.recusados[0]).toMatch(/“6.pdf” não foi incluído: são no máximo 5 anexos/);
  });
});

describe("rotuloDoTipo — nunca o tipo cru na tela", () => {
  it("o tipo canônico vira o nome do formato; desconhecido sai como veio", () => {
    expect(rotuloDoTipo("application/pdf")).toBe("PDF");
    expect(rotuloDoTipo("image/png")).toBe("PNG");
    expect(rotuloDoTipo("image/jpeg")).toBe("JPEG");
    expect(rotuloDoTipo("text/plain")).toBe("Texto");
    expect(rotuloDoTipo("text/csv")).toBe("CSV");
    expect(rotuloDoTipo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).toBe("Word (DOCX)");
    expect(rotuloDoTipo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).toBe("Excel (XLSX)");
    expect(rotuloDoTipo("application/vnd.oasis.opendocument.text")).toBe("Texto (ODT)");
    expect(rotuloDoTipo("application/vnd.oasis.opendocument.spreadsheet")).toBe("Planilha (ODS)");
    expect(rotuloDoTipo("application/x-coisa")).toBe("application/x-coisa");
  });
});

describe("resumoDoEnvio", () => {
  const item = (fase: ItemDeEnvio["fase"]): ItemDeEnvio => ({ arquivo: arquivo("x.pdf"), fase });
  it("diz quantos foram, quantos falharam e quando ainda está enviando", () => {
    expect(resumoDoEnvio([item("ok"), item("ok")])).toBe("2 arquivos anexados.");
    expect(resumoDoEnvio([item("ok")])).toBe("1 arquivo anexado.");
    expect(resumoDoEnvio([item("ok"), item("erro"), item("erro")])).toBe("1 arquivo anexado; 2 não foram anexados.");
    expect(resumoDoEnvio([item("erro")])).toBe("O arquivo não foi anexado.");
    expect(resumoDoEnvio([item("ok"), item("enviando"), item("esperando")])).toBe("Enviando os arquivos…");
  });
});
