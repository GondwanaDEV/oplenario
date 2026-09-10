import { describe, expect, it, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import PaginaStatus, { COMPONENTES, SEM_MEDICAO } from "./page";

// Os dois eixos que esta tela tem de provar (brief da fatia 1 da Onda E):
//  (a) ela LISTA os componentes do design (status.html:69-75);
//  (b) ela NÃO AFIRMA disponibilidade que a plataforma não mede.
//
// (b) é o eixo que tem de poder reprovar, e a revisão mostrou que a primeira versão destes testes não
// reprovava: as guardas eram BLOCKLIST (as palavras do HTML de origem: "Operacional", "degradado"…) e um
// detector ESTRUTURAL que conhecia uma única forma (a classe literal `.dot`). Blocklist só pega a
// redação que alguém já adivinhou; sinônimo, atributo (`aria-label`/`title`) e outra classe passavam
// verdes. Aqui as guardas são ALLOWLIST: a coluna de disponibilidade tem de conter EXATAMENTE a
// declaração que a página publica e nada mais, a célula não pode ter filho nenhum, a varredura é sobre
// o `innerHTML` (atributos inclusos, não só text-nodes), e prosa livre fora das regiões sancionadas
// (os dois <EmBreve>, a tabela, o cabeçalho e o rodapé) reprova por construção. A blocklist fica como
// guarda extra — barata e ainda pega o porte literal do HTML de origem.

const REGIOES_SANCIONADAS = ".em-breve, table, header, footer, .pular";

/** A célula de disponibilidade de cada linha do corpo da tabela, na ordem de COMPONENTES. */
function celulasDeDisponibilidade(container: HTMLElement): HTMLTableCellElement[] {
  return Array.from(container.querySelectorAll<HTMLTableCellElement>("tbody tr > td"));
}

describe("PaginaStatus (pública)", () => {
  afterEach(cleanup);

  it("lista os cinco componentes do design", () => {
    render(<PaginaStatus />);
    for (const nome of [
      "Portal público e e-SIC",
      "Sessão ao vivo e votação",
      "API de dados abertos",
      "Painel interno e tramitação",
      "Geração de documentos",
    ]) {
      expect(screen.getByRole("rowheader", { name: nome })).toBeDefined();
    }
    expect(COMPONENTES).toHaveLength(5);
  });

  it("cada linha declara EXATAMENTE o que falta — texto puro, sem elemento-filho", () => {
    const { container } = render(<PaginaStatus />);
    const celulas = celulasDeDisponibilidade(container);
    expect(celulas).toHaveLength(COMPONENTES.length);
    celulas.forEach((td, i) => {
      // allowlist: a coluna diz a declaração da linha e NADA mais (texto novo reprova por construção).
      expect(td.textContent?.trim()).toBe(COMPONENTES[i].declaracao);
      // e o canal é só texto: nenhum pontinho, badge, ícone ou <span> de estado — de qualquer classe.
      expect(td.children).toHaveLength(0);
    });
  });

  it("não afirma disponibilidade que não mede — nem em texto, nem em atributo", () => {
    const { container } = render(<PaginaStatus />);
    const texto = container.textContent ?? "";
    // varredura sobre o HTML servido: pega aria-label, title, alt e className, não só os text-nodes
    // (o plante do revisor era um <span aria-label="Operacional"> sem texto, invisível a textContent).
    const html = container.innerHTML;
    // badge de saúde herdado do HTML de origem
    expect(html).not.toMatch(/operacional/i);
    expect(html).not.toMatch(/degradad|fora do ar|indispon[íi]vel|est[áa]vel/i);
    // qualquer número de uptime / SLA fabricado (99,9% · 100% · 99.95 %)
    expect(texto).not.toMatch(/\d+([.,]\d+)?\s*%/);
    // nenhum elemento sinaliza saúde só por cor (os pontinhos .dot do original — classe viva no app,
    // (vereador)/vereador/page.tsx — e as classes de estado .st-ok/.st-deg de status.html)
    expect(container.querySelectorAll(".dot, .st-ok, .st-deg")).toHaveLength(0);
  });

  it("componente que não existe é declarado INEXISTENTE, nunca como 'sem medição'", () => {
    const { container } = render(<PaginaStatus />);
    // "Sem medição publicada" nega a MEDIÇÃO e, ao fazê-lo, afirma que o componente existe e está no ar.
    // A API de dados abertos não existe (sem rota /api nem endpoint no backend, sem rota Next), e a
    // própria plataforma já publica isso em (publico)/navegacao-civica.tsx. Se alguém devolver esta
    // linha ao rótulo de ausência-de-medição, as duas telas públicas voltam a se contradizer.
    const api = COMPONENTES.find((c) => c.nome === "API de dados abertos");
    expect(api?.estado).toBe("inexistente");
    const celulaApi = celulasDeDisponibilidade(container)[COMPONENTES.indexOf(api!)];
    expect(celulaApi.textContent?.trim()).not.toBe(SEM_MEDICAO);
    expect(celulaApi.textContent).toMatch(/ainda não existe/i);

    // e o estado e a declaração não podem divergir em nenhuma linha (é o que amarra a tabela ao fato).
    for (const componente of COMPONENTES) {
      if (componente.estado === "sem-medicao") {
        expect(componente.declaracao).toBe(SEM_MEDICAO);
      } else {
        expect(componente.declaracao).not.toBe(SEM_MEDICAO);
        expect(componente.declaracao).toMatch(/ainda não existe/i);
      }
    }
  });

  it("não há prosa livre fora das regiões sancionadas", () => {
    const { container } = render(<PaginaStatus />);
    // Allowlist estrutural: tudo o que a página afirma vive num <EmBreve> (motivo obrigatório e
    // auditado abaixo), na tabela (célula exata, caso 2), no cabeçalho ou no rodapé. Um <p> novo com
    // "todos os serviços no ar" — que nenhuma blocklist de sinônimo pegaria — cai aqui.
    const copia = container.cloneNode(true) as HTMLElement;
    copia.querySelectorAll(REGIOES_SANCIONADAS).forEach((no) => no.remove());
    // fora das regiões sancionadas só sobram os dois títulos de seção — e mais nada.
    expect(Array.from(copia.querySelectorAll("h2")).map((h) => h.textContent)).toEqual([
      "Componentes",
      "Incidentes recentes",
    ]);
    copia.querySelectorAll("h2").forEach((h) => h.remove());
    expect(copia.textContent?.replace(/\s+/g, " ").trim()).toBe("");

    const rodape = container.querySelector("footer");
    expect(rodape?.textContent?.replace(/\s+/g, " ").trim()).toBe(
      "Página operada pela plataforma O Plenário. Para o andamento de um processo ou de um pedido de informação, use o portal da sua Câmara.",
    );
  });

  it("o histórico de incidentes é um em-breve honesto, não uma lista vazia disfarçada", () => {
    render(<PaginaStatus />);
    const emBreve = screen.getByRole("status", { name: /Histórico de incidentes/ });
    // o motivo inteiro, literal — não um trecho: prosa acrescentada ao motivo ("tudo no ar…") também
    // é uma afirmação de disponibilidade, e um `toMatch` de trecho a deixaria passar. O literal fica
    // AQUI e nunca é importado da página; importá-lo mudaria os dois lados junto e o teste deixaria de
    // poder reprovar.
    expect(emBreve.querySelector(".em-breve-motivo")?.textContent).toBe(
      "Nada é publicado aqui porque o monitoramento de produção ainda não está em operação — a ausência de incidentes nesta página não significa que não houve nenhum. O histórico passa a ser publicado quando a medição entrar no ar.",
    );
    expect(screen.getByRole("heading", { level: 2, name: "Incidentes recentes" })).toBeDefined();
  });

  it("o cabeçalho identifica a plataforma e o em-breve global diz por que não há medição", () => {
    render(<PaginaStatus />);
    expect(screen.getByRole("heading", { level: 1, name: /Status do sistema/ })).toBeDefined();
    // O nome acessível do <EmBreve> vem do `titulo`, NUNCA do `motivo` — afirmar só o role deixava as
    // três linhas que são a tese da fatia sem uma única asserção sobre o conteúdo. O motivo é o que
    // torna o em-breve honesto; é ele que tem de poder reprovar.
    const emBreve = screen.getByRole("status", { name: /Disponibilidade da plataforma/ });
    expect(emBreve.querySelector(".em-breve-motivo")?.textContent).toBe(
      "A plataforma está em implantação e o monitoramento de disponibilidade ainda está em configuração: nenhuma medição de disponibilidade é apurada ou publicada hoje. Enquanto isso, esta página não afirma nem nega que os componentes estejam no ar — só diz o que se sabe.",
    );
  });
});
