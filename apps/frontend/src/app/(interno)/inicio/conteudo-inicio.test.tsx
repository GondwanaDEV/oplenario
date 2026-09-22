// Gate da FIAÇÃO de /inicio. `inicio-vista` (a derivação) e `PainelInicio` (o desenho) já têm teste
// próprio; o que não tinha dono era o meio-de-campo: quais hooks alimentam a derivação, e o guard que
// segura o render enquanto os papéis não chegaram.
//
// Esse guard é o ponto caro do arquivo. Sem ele a tela decide a persona com `papeis=[]` e PISCA a home do
// cidadão para a secretária antes de se corrigir — e um teste que só olhasse o estado final não veria
// nada de errado. Por isso o caso "carregando" é assertado pelo que NÃO existe no DOM.
//
// `../topo` é mockado com um marcador: TopoInterno faz IO próprio (useMeuIdentidade) e exige TemaProvider,
// nada disso é assunto deste arquivo. O que importa aqui é QUEM recebe o topo, e com qual área.
// `PainelInicio` renderiza de verdade — é puro, e assim as asserções falam do que a pessoa vê.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ConteudoInicio } from "./conteudo-inicio";
import type { SessaoOut } from "@/lib/contrato-sessoes.gen";

type EstadoPapeis = "carregando" | "pronto" | "erro";
type EstadoSessoes = "carregando" | "pronto" | "erro";

const { estado } = vi.hoisted(() => ({
  estado: {
    token: "tok" as string | null,
    papeis: [] as string[],
    estadoPapeis: "pronto" as EstadoPapeis,
    sessoes: [] as SessaoOut[] | null,
    estadoSessoes: "pronto" as EstadoSessoes,
    // Registra o token com que `useSessoes` foi chamado — é a única forma de provar que o token do
    // `useAuth` chega até a busca. Se essa ligação quebrar, a tela some com as sessões em silêncio.
    tokenRecebido: undefined as string | null | undefined,
  },
}));

vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: estado.token }),
  usePapeis: () => ({ papeis: estado.papeis, estado: estado.estadoPapeis }),
}));

vi.mock("@/lib/use-sessoes", () => ({
  useSessoes: (token: string | null) => {
    estado.tokenRecebido = token;
    return { sessoes: estado.sessoes, estado: estado.estadoSessoes };
  },
}));

vi.mock("../topo", () => ({
  TopoInterno: ({ area }: { area: string }) => <nav data-testid="topo-interno">{area}</nav>,
}));

const ABERTA = {
  id: "s-viva",
  estado: "aberta",
  sessaoLegislativaId: "sl1",
  tipoSessao: "ordinaria",
  numeroSequencial: 2,
  modalidade: "presencial",
  delibera: true,
  transmitePublica: true,
  geraAtaRegimental: true,
  permiteVotoSecreto: false,
  permiteModalidadeRemota: false,
  lockVersion: 0,
} as SessaoOut;

beforeEach(() => {
  estado.token = "tok";
  estado.papeis = [];
  estado.estadoPapeis = "pronto";
  estado.sessoes = [];
  estado.estadoSessoes = "pronto";
  estado.tokenRecebido = undefined;
});

afterEach(() => cleanup());

describe("ConteudoInicio — o guard de papéis", () => {
  it("com os papéis ainda carregando não renderiza NADA — nem o topo, nem a home de outra persona", () => {
    estado.estadoPapeis = "carregando";
    estado.papeis = [];
    const { container } = render(<ConteudoInicio />);

    // Vazio de verdade, não "vazio de conteúdo útil": qualquer coisa aqui seria uma decisão de persona
    // tomada com `papeis=[]`, e é justamente o flash que o guard existe para evitar.
    expect(container.innerHTML).toBe("");
    expect(screen.queryByTestId("topo-interno")).toBeNull();
    // A home do cidadão é o que apareceria se `[]` fosse lido como "não tem papel" em vez de "não sei".
    expect(screen.queryByRole("link", { name: /Acompanhamentos/ })).toBeNull();
  });

  it("papéis prontos e vazios (a cidadã) renderiza a área dela — o vazio do guard é 'não sei', não 'nada'", () => {
    estado.estadoPapeis = "pronto";
    estado.papeis = [];
    const { container } = render(<ConteudoInicio />);

    // O contraste com o teste acima é o que dá sentido aos dois: MESMOS papéis (`[]`), estados
    // diferentes, telas diferentes. Sem este caso, o anterior passaria mesmo se a tela nunca renderizasse.
    expect(container.innerHTML).not.toBe("");
    expect(screen.getByRole("link", { name: /Acompanhamentos/ })).toBeTruthy();
  });
});

describe("ConteudoInicio — o topo é o menu da secretaria", () => {
  it("secretaria recebe o TopoInterno, rotulado com a área", () => {
    estado.papeis = ["secretario"];
    render(<ConteudoInicio />);

    expect(screen.getByTestId("topo-interno").textContent).toBe("Início");
  });

  it("vereador NÃO recebe o topo da secretaria (o chrome dele é do grupo (vereador))", () => {
    estado.papeis = ["vereador"];
    estado.sessoes = [ABERTA]; // com sessão viva, "Votar" é a ação primária — nome acessível exato
    render(<ConteudoInicio />);

    expect(screen.queryByTestId("topo-interno")).toBeNull();
    // A tela do vereador renderizou de verdade (não é um `queryByTestId` nulo por a tela estar vazia).
    expect(screen.getByRole("link", { name: /^Votar$/ }).getAttribute("href")).toBe("/votar");
    expect(screen.queryByRole("link", { name: /Painéis da Mesa/ })).toBeNull();
  });

  it("cidadã (sem papel de trabalho) também não recebe o topo", () => {
    estado.papeis = [];
    render(<ConteudoInicio />);

    expect(screen.queryByTestId("topo-interno")).toBeNull();
  });
});

describe("ConteudoInicio — a fiação dos dados", () => {
  it("o token do useAuth chega ao useSessoes", () => {
    estado.token = "tok-da-sessao";
    estado.papeis = ["secretario"];
    render(<ConteudoInicio />);

    expect(estado.tokenRecebido).toBe("tok-da-sessao");
  });

  it("as sessões buscadas chegam à derivação — a sessão viva vira o Comando da Mesa", () => {
    estado.papeis = ["secretario"];
    estado.sessoes = [ABERTA];
    render(<ConteudoInicio />);

    expect(screen.getByRole("link", { name: /Comando da Mesa/ }).getAttribute("href")).toBe(
      "/sessoes/s-viva/conduzir",
    );
  });

  it("o estadoSessoes é REPASSADO, não engolido: com a busca em voo a tela não afirma que não há sessão", () => {
    // Se a fiação passasse um `estadoSessoes` fixo (ou omitisse o campo), este caso viraria "nenhuma
    // sessão agendada" — a tela mentiria com cara de certeza enquanto o fetch ainda está voando.
    estado.papeis = ["secretario"];
    estado.sessoes = null;
    estado.estadoSessoes = "carregando";
    const { container } = render(<ConteudoInicio />);

    expect(screen.queryByText(/Nenhuma sessão agendada/i)).toBeNull();
    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
  });

  it("erro ao carregar as sessões é dito, e é distinto de 'não há sessão'", () => {
    estado.papeis = ["secretario"];
    estado.sessoes = null;
    estado.estadoSessoes = "erro";
    render(<ConteudoInicio />);

    expect(screen.queryByText(/Nenhuma sessão agendada/i)).toBeNull();
    expect(screen.getByText(/não foi possível carregar as sessões/i)).toBeTruthy();
  });
});
