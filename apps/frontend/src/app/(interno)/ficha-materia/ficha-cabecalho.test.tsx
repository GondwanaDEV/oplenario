import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { FichaCabecalho } from "./ficha-cabecalho";
import type { ProposicaoDetalheOut, RitoDaMateriaOut } from "@/lib/contrato-legislativo.gen";

const proposicao: ProposicaoDetalheOut = {
  id: "1",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 42,
  urnLex: "urn:x",
  ementa: "Cria o Programa Municipal de Hortas Comunitárias",
  autorTexto: "Ver.ª Helena Matos",
  estado: "em_comissoes",
  aprovada: false,
  lockVersion: 3,
  atualizadoEm: "2026-05-12T10:00:00Z",
};

describe("FichaCabecalho", () => {
  afterEach(() => cleanup());

  it("renderiza número, espécie, chip de situação, título e a faixa de azulejo", () => {
    render(<FichaCabecalho proposicao={proposicao} />);
    expect(screen.getByText("PL 42/2026")).toBeTruthy();
    expect(screen.getByText("Projeto de Lei")).toBeTruthy();
    expect(screen.getByText("Em comissões")).toBeTruthy();
    expect(screen.getByText("Cria o Programa Municipal de Hortas Comunitárias")).toBeTruthy();
    expect(screen.getByText(/Ver\.ª Helena Matos/)).toBeTruthy();
    expect(screen.getByRole("img", { name: /Tramitação/ })).toBeTruthy();
  });

  it("aguardando_pauta (chave real do rito da demo): a faixa não volta ao Protocolo e o chip diz 'Aguardando pauta'", () => {
    render(<FichaCabecalho proposicao={{ ...proposicao, estado: "aguardando_pauta" }} />);
    expect(screen.getByText("Aguardando pauta")).toBeTruthy();
    const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
    expect(faixa).toContain("concluídos Protocolo, Comissões");
    expect(faixa).not.toContain("atual Protocolo");
  });

  it("estado desconhecido da Casa: faixa neutra 'Em tramitação', sem marcar etapa do rito", () => {
    render(<FichaCabecalho proposicao={{ ...proposicao, estado: "xpto_da_casa" }} />);
    const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
    expect(faixa).toContain("atual Em tramitação");
    expect(faixa).not.toContain("Protocolo");
  });

  it("sem autoria informada -> honesto, não lança", () => {
    render(<FichaCabecalho proposicao={{ ...proposicao, autorTexto: null }} />);
    expect(screen.getByText(/não informada/)).toBeTruthy();
  });

  it("fatia 2c: requerimento coletivo mostra os coautores que assinaram", () => {
    render(
      <FichaCabecalho
        proposicao={proposicao}
        coautores={[
          { nome: "Bia Lima", assinadoEm: "2026-09-26T12:00:00Z" },
          { nome: "Caio Reis", assinadoEm: "2026-09-26T12:05:00Z" },
        ]}
      />,
    );
    expect(screen.getByText("Bia Lima, Caio Reis")).toBeTruthy();
    expect(screen.getByText(/Coautores/)).toBeTruthy();
  });

  // "Onde está a matéria" pelo RITO da Casa: a rota da ficha devolve as etapas em ordem; o nome do estado deixa de
  // decidir a posição. Vocabulário fora do mapa fixo do front, de propósito.
  describe("faixa pelo rito da Casa", () => {
    const etapa = (chave: string, rotulo: string, terminal = false) => ({ chave, rotulo, terminal });
    const ritoEmLinha: RitoDaMateriaOut = {
      ordemUnica: true,
      etapas: [etapa("entrada", "Entrada"), etapa("instrucao", "Instrução"), etapa("plenario_unico", "Plenário único")],
      atual: etapa("instrucao", "Instrução"),
      anteriores: null,
      proximas: [etapa("plenario_unico", "Plenário único")],
    };

    it("estado que o front não conhece, com rito: a faixa marca a etapa e usa os nomes da Casa", () => {
      render(<FichaCabecalho proposicao={{ ...proposicao, estado: "instrucao" }} rito={ritoEmLinha} />);
      const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
      expect(faixa).toContain("concluídos Entrada");
      expect(faixa).toContain("atual Instrução");
      expect(faixa).toContain("pendente Plenário único");
      expect(faixa).not.toContain("Em tramitação");
      expect(faixa).not.toContain("Protocolo");
    });

    it("com rito, a faixa não usa as etapas ilustrativas nem para um estado que o mapa fixo conhece", () => {
      render(<FichaCabecalho proposicao={proposicao} rito={ritoEmLinha} />);
      const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
      expect(faixa).not.toContain("1º turno");
      expect(faixa).not.toContain("Sanção");
    });

    it("sem ordem única: só o entorno, com as próximas possíveis como ramos", () => {
      const rito: RitoDaMateriaOut = {
        ordemUnica: false,
        etapas: [],
        atual: etapa("analise", "Análise"),
        anteriores: [etapa("recebida", "Recebida")],
        proximas: [etapa("via_a", "Via A"), etapa("via_b", "Via B")],
      };
      render(<FichaCabecalho proposicao={{ ...proposicao, estado: "analise" }} rito={rito} />);
      const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
      expect(faixa).toContain("concluídos Recebida");
      expect(faixa).toContain("atual Análise");
      expect(faixa).toContain("próximas possíveis Via A, Via B");
    });

    it("rito que não declara a etapa atual: volta ao comportamento anterior (mapa ilustrativo)", () => {
      render(<FichaCabecalho proposicao={proposicao} rito={{ ...ritoEmLinha, atual: null }} />);
      const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
      expect(faixa).toContain("atual Comissões");
    });

    it("rito nulo (matéria sem rito): comportamento anterior", () => {
      render(<FichaCabecalho proposicao={proposicao} rito={null} />);
      const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
      expect(faixa).toContain("atual Comissões");
    });

    // Decisão de produto (05/10/2026): o chip e a faixa da mesma tela dizem a mesma coisa, e o nome da Casa vence. Antes
    // o chip dizia "Em pauta" (rótulo fixo) e a faixa "Em Pauta" (nome da Casa), ou pior, nomes diferentes.
    it("com rito, o chip diz o nome que a Casa deu à etapa atual, o mesmo da faixa", () => {
      const rito: RitoDaMateriaOut = {
        ...ritoEmLinha,
        etapas: [etapa("protocolada", "Entrada"), etapa("em_comissoes", "Análise nas comissões"), etapa("em_pauta", "Na Ordem do Dia")],
        atual: etapa("em_comissoes", "Análise nas comissões"),
        proximas: [etapa("em_pauta", "Na Ordem do Dia")],
      };
      const { container } = render(<FichaCabecalho proposicao={proposicao} rito={rito} />);
      const chip = container.querySelector(".ficha-cab .chip");
      expect(chip?.textContent).toBe("Análise nas comissões");
      expect(screen.queryByText("Em comissões")).toBeNull();
      const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
      expect(faixa).toContain("atual Análise nas comissões");
    });

    it("rito sem ordem única: o chip segue a etapa atual que a faixa mostra", () => {
      const rito: RitoDaMateriaOut = {
        ordemUnica: false,
        etapas: [],
        atual: etapa("analise", "Análise"),
        anteriores: null,
        proximas: [etapa("via_a", "Via A"), etapa("via_b", "Via B")],
      };
      const { container } = render(<FichaCabecalho proposicao={{ ...proposicao, estado: "analise" }} rito={rito} />);
      expect(container.querySelector(".ficha-cab .chip")?.textContent).toBe("Análise");
    });

    it("sem rito verificável (atual nulo), o chip cai no rótulo fixo de sempre", () => {
      const { container } = render(<FichaCabecalho proposicao={proposicao} rito={{ ...ritoEmLinha, atual: null }} />);
      expect(container.querySelector(".ficha-cab .chip")?.textContent).toBe("Em comissões");
    });

    it("a partir do autógrafo o selo vem do desfecho, não do rito (nem do nome da Casa)", () => {
      const rito: RitoDaMateriaOut = {
        ...ritoEmLinha,
        etapas: [etapa("entrada", "Entrada"), etapa("aprovada", "Aprovada pelo Plenário", true)],
        atual: etapa("aprovada", "Aprovada pelo Plenário", true),
        proximas: [],
      };
      const { container } = render(
        <FichaCabecalho proposicao={{ ...proposicao, estado: "aprovada" }} rito={rito} desfecho="vetado" />,
      );
      const chip = container.querySelector(".ficha-cab .chip");
      expect(chip?.textContent).toBe("Vetada");
      expect(chip?.className).toContain("chip-tram");
    });

    it("desfecho que só registra a votação não muda o selo: vale o nome da Casa", () => {
      const { container } = render(<FichaCabecalho proposicao={proposicao} rito={ritoEmLinha} desfecho="aprovada" />);
      expect(container.querySelector(".ficha-cab .chip")?.textContent).toBe("Instrução");
    });
  });
});
