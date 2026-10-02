import { describe, expect, it } from "vitest";
import { doFio, formaValida } from "./contrato-comunicacao";

// O acerto com o backend real (integração de 02/10/2026): as formas que o backend devolve viram os tipos que as
// telas usam. Cada caso aqui é uma resposta REAL do backend, já camelizada.
describe("doFio — o fio real nos tipos das telas", () => {
  it("o 201 do envio é o próprio comunicado; vira {comunicado, destinatarios, semAcesso}", () => {
    const fio = { id: "c1", protocolo: "COM-2026-000001", assunto: "A", destinos: [], destinatarios: 3, semAcesso: 1 };
    const e = doFio.envio(fio) as { comunicado: { id: string }; destinatarios: number; semAcesso: number };
    expect(e.comunicado.id).toBe("c1");
    expect(e.destinatarios).toBe(3);
    expect(e.semAcesso).toBe(1);
    expect(formaValida.envio(e)).toBe(true);
    // já no envelope (o contrato antigo): passa como está
    expect(doFio.envio({ comunicado: fio, destinatarios: 3, semAcesso: 0 })).toEqual({ comunicado: fio, destinatarios: 3, semAcesso: 0 });
  });

  it("caixa e enviados: o nome do remetente sai do objeto `remetente`", () => {
    const caixa = doFio.caixa({ itens: [{ id: "c1", remetente: { identidadeId: "i", nome: "Rita" } }], naoLidos: 1 }) as { itens: { remetenteNome: string }[] };
    expect(caixa.itens[0].remetenteNome).toBe("Rita");
    const env = doFio.enviados({ itens: [{ id: "c1", remetente: { identidadeId: "i", nome: "Rita" }, pendentesVencidos: 2 }] }) as {
      itens: { remetenteNome: string; pendentesVencidos: number }[];
    };
    expect(env.itens[0]).toMatchObject({ remetenteNome: "Rita", pendentesVencidos: 2 });
  });

  it("leitura: os vencidos dos totais chegam como `pendentesVencidos`", () => {
    const l = doFio.leitura({ comunicado: {}, totais: { destinatarios: 3, vencidos: 2, faltamLer: 1 }, linhas: [] }) as {
      totais: { pendentesVencidos: number };
    };
    expect(l.totais.pendentesVencidos).toBe(2);
  });

  it("ciência: `cienteEm` sai das marcas da pessoa", () => {
    expect(doFio.ciencia({ id: "c1", minhasMarcas: { cienteEm: "2026-10-02T15:00:00Z" } })).toMatchObject({ cienteEm: "2026-10-02T15:00:00Z" });
  });

  it("a contagem do topo só precisa do número", () => {
    expect(formaValida.contagem({ naoLidos: 2, pendentesCiencia: 0, proximaCienciaAte: null })).toBe(true);
    expect(formaValida.contagem({})).toBe(false);
  });
});
