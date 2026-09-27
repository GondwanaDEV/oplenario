import { describe, expect, it } from "vitest";
import {
  avisoDeOrigem,
  mensagemDeErroProposta,
  reciboDaProposta,
  rotuloDoEstado,
  textoAtualizado,
  validadeDaProposta,
  type PropostaOut,
} from "./propostas-vista";

const base: PropostaOut = {
  id: "p1",
  ferramenta: "protocolar_requerimento",
  agente: "assistente-da-casa",
  titulo: "Protocolar o requerimento “Obra da praça”",
  texto: "Ana Prado requer … Fortaleza, 27 de setembro de 2026.",
  ritual: "assinatura",
  estado: "aguardando",
  contaminadaPor: [],
  criadaEm: "2026-09-27T15:00:00Z",
  expiraEm: "2026-09-30T15:00:00Z",
  decididaEm: null,
  resultado: null,
  erro: null,
};

describe("propostas", () => {
  it("estado em palavras de quem usa", () => {
    expect(rotuloDoEstado("aguardando")).toBe("Esperando você");
    expect(rotuloDoEstado("confirmada")).toBe("Confirmada");
    expect(rotuloDoEstado("recusada")).toBe("Recusada");
    expect(rotuloDoEstado("expirada")).toBe("Expirou");
  });

  it("avisa quando a proposta foi feita depois de ler conteúdo de fora (Eixo 4.5)", () => {
    expect(avisoDeOrigem(base)).toBeNull();
    expect(
      avisoDeOrigem({
        ...base,
        contaminadaPor: [
          { ferramenta: "pedido_esic", origem: "e-SIC", referencia: "nº 12/2026" },
          { ferramenta: "manifestacao", origem: "Ouvidoria", referencia: "nº 3/2026" },
        ],
      }),
    ).toBe("Feita depois de ler conteúdo de fora da Casa (e-SIC nº 12/2026; Ouvidoria nº 3/2026). Confira com atenção.");
  });

  it("o texto de hoje pode diferir do proposto só pela data — e a tela diz", () => {
    expect(textoAtualizado(base)).toBeNull();
    expect(textoAtualizado({ ...base, apresentacaoAtual: { titulo: base.titulo, texto: base.texto } })).toBeNull();
    expect(textoAtualizado({ ...base, apresentacaoAtual: { titulo: base.titulo, texto: "… 28 de setembro de 2026." } })).toBe(
      "O texto foi montado de novo agora (a data é a de hoje). É este que será assinado.",
    );
  });

  it("validade e recibo", () => {
    expect(validadeDaProposta(base)).toBe("Vale até 30/09 às 12:00.");
    expect(reciboDaProposta({ ...base, estado: "confirmada", resultado: { ano: 2026, sequencial: 7, assinaturaAlgoritmo: "STUB-ICP-v0" } })).toBe(
      "Requerimento nº 7/2026 protocolado e assinado.",
    );
    expect(reciboDaProposta(base)).toBeNull();
  });

  it("erros em linguagem de quem usa", () => {
    expect(mensagemDeErroProposta(409, "expirada")).toMatch(/expirou/);
    expect(mensagemDeErroProposta(409, "decidida")).toMatch(/já foi decidida/);
    expect(mensagemDeErroProposta(409, "sem-objeto")).toMatch(/não existe mais/);
    expect(mensagemDeErroProposta(403)).toMatch(/permissão/);
    expect(mensagemDeErroProposta(404)).toMatch(/não encontrada/);
    expect(mensagemDeErroProposta(0)).toMatch(/Não foi possível/);
  });
});
