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

    // Decisão de 05/10/2026: na ficha a mesma etapa não pode ter dois nomes na mesma tela. O selo usa o nome que a
    // Casa deu à etapa atual (o mesmo da faixa); sem rito utilizável, segue o rótulo único de estado (o da lista).
    it("o selo de situação diz o nome que a Casa deu à etapa atual, o mesmo da faixa", () => {
      const rito: RitoDaMateriaOut = {
        ordemUnica: true,
        etapas: [etapa("entrada", "Entrada"), etapa("em_pauta", "Na Ordem do Dia"), etapa("final", "Concluída", true)],
        atual: etapa("em_pauta", "Na Ordem do Dia"),
        anteriores: null,
        proximas: [etapa("final", "Concluída", true)],
      };
      render(<FichaCabecalho proposicao={{ ...proposicao, estado: "em_pauta" }} rito={rito} />);
      expect(screen.getByText("Na Ordem do Dia", { selector: ".chip" })).toBeTruthy();
      expect(screen.queryByText("Em pauta")).toBeNull();
      const faixa = screen.getByRole("img", { name: /Tramitação/ }).getAttribute("aria-label") ?? "";
      expect(faixa).toContain("atual Na Ordem do Dia");
    });

    it("sem ordem única, o selo também usa o nome da etapa atual no rito", () => {
      const rito: RitoDaMateriaOut = {
        ordemUnica: false,
        etapas: [],
        atual: etapa("analise", "Análise"),
        anteriores: [etapa("recebida", "Recebida")],
        proximas: [etapa("via_a", "Via A"), etapa("via_b", "Via B")],
      };
      render(<FichaCabecalho proposicao={{ ...proposicao, estado: "analise" }} rito={rito} />);
      expect(screen.getByText("Análise", { selector: ".chip" })).toBeTruthy();
    });

    it("a cor do selo continua vindo do estado, não do nome da etapa", () => {
      const rito: RitoDaMateriaOut = {
        ordemUnica: true,
        etapas: [etapa("aguardando_pauta", "Pronta para a Ordem do Dia"), etapa("fim", "Fim", true)],
        atual: etapa("aguardando_pauta", "Pronta para a Ordem do Dia"),
        anteriores: null,
        proximas: [etapa("fim", "Fim", true)],
      };
      render(<FichaCabecalho proposicao={{ ...proposicao, estado: "aguardando_pauta" }} rito={rito} />);
      expect(screen.getByText("Pronta para a Ordem do Dia", { selector: ".chip" }).className).toContain("chip-aguarda");
    });

    it("rito que não declara a etapa atual: o selo segue o rótulo único de estado (o mesmo da lista)", () => {
      render(<FichaCabecalho proposicao={proposicao} rito={{ ...ritoEmLinha, atual: etapa("em_comissoes", "Em Comissões") }} />);
      expect(screen.getByText("Em comissões")).toBeTruthy();
      expect(screen.queryByText("Em Comissões")).toBeNull();
    });

    it("rito sem etapa atual ou nulo: o selo segue o rótulo único de estado", () => {
      const { unmount } = render(<FichaCabecalho proposicao={proposicao} rito={{ ...ritoEmLinha, atual: null }} />);
      expect(screen.getByText("Em comissões")).toBeTruthy();
      unmount();
      render(<FichaCabecalho proposicao={proposicao} rito={null} />);
      expect(screen.getByText("Em comissões")).toBeTruthy();
    });
  });
});
