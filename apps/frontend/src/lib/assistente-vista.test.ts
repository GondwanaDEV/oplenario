import { describe, expect, it } from "vitest";
import {
  agruparPorDia,
  comRotuloDoPasso,
  conversaDaInteracao,
  lerConversa,
  mensagemDeErroAssistente,
  metaDoItem,
  rotuloDoDia,
  rotuloDoPasso,
  sinalDeIndisponivel,
  type InteracaoGuardada,
  type ItemHistorico,
} from "./assistente-vista";

const SSE = [
  'event: passo\ndata: {"ferramenta":"situacao_da_materia","argumentos":{"tipo":"projeto_lei","sequencial":12,"ano":2026},"ok":true}',
  'event: resposta\ndata: {"texto":"Segundo o sistema. [[ferramenta:situacao_da_materia#1 | ementa: Merenda]]","citacoes":[{"fonte-id":"ferramenta:situacao_da_materia#1","trecho":"ementa: Merenda","status":"conferida"}],"paragrafos-sem-fonte":[],"incerteza":"normal","modelo":"fake-1","contaminado":false}',
  'event: fim\ndata: {"execucao-id":"e1"}',
  "",
].join("\n\n");

describe("lerConversa", () => {
  it("lê passos, resposta e fim, em camelCase", () => {
    const c = lerConversa(SSE);
    expect(c.passos).toHaveLength(1);
    expect(c.resposta?.citacoes[0].fonteId).toBe("ferramenta:situacao_da_materia#1");
    expect(c.resposta?.paragrafosSemFonte).toEqual([]);
    expect(c.execucaoId).toBe("e1");
    expect(c.indisponivel).toBeNull();
    expect(c.resposta?.execucaoIa).toBeUndefined();
  });

  it("8.4: a resposta pode trazer o id da execução NA IA (para o 'reportar erro'), distinto do `fim`", () => {
    const c = lerConversa(
      'event: resposta\ndata: {"texto":"x","citacoes":[],"paragrafos-sem-fonte":[],"incerteza":"normal","modelo":"m","contaminado":false,"execucao-ia":"ia-7"}\n\nevent: fim\ndata: {"execucao-id":"e1"}\n\n',
    );
    expect(c.resposta?.execucaoIa).toBe("ia-7");
    expect(c.execucaoId).toBe("e1");
  });

  it("ADR-0024: o `fim` traz a linha do histórico e a conversa (a próxima pergunta continua nela)", () => {
    const c = lerConversa('event: fim\ndata: {"execucao-id":"e1","interacao-id":"i1","conversa-id":"c1"}\n\n');
    expect([c.execucaoId, c.interacaoId, c.conversaId]).toEqual(["e1", "i1", "c1"]);
    const antigo = lerConversa('event: fim\ndata: {"execucao-id":"e1"}\n\n');
    expect([antigo.interacaoId, antigo.conversaId]).toEqual([null, null]);
  });

  it("indisponível e lixo no meio não quebram", () => {
    const c = lerConversa('event: x\ndata: {torto\n\nevent: indisponivel\ndata: {"mensagem":"Siga pela tela."}\n\n');
    expect(c.indisponivel).toBe("Siga pela tela.");
    expect(c.resposta).toBeNull();
  });

  it("B.6: a proposta de ato criada na execução vira um cartão para confirmar", () => {
    const c = lerConversa(
      'event: proposta\ndata: {"id":"p1","titulo":"Protocolar o requerimento “X”","ritual":"assinatura"}\n\nevent: fim\ndata: {}\n\n',
    );
    expect(c.propostas).toEqual([{ id: "p1", titulo: "Protocolar o requerimento “X”", ritual: "assinatura" }]);
    expect(lerConversa(SSE).propostas).toEqual([]);
  });
});

describe("rótulos", () => {
  it("conta o que foi consultado em palavras da Casa", () => {
    const args = { tipo: "projeto_lei", sequencial: 12, ano: 2026 };
    expect(rotuloDoPasso({ ferramenta: "situacao_da_materia", argumentos: args, ok: true })).toBe("Consultou a situação do PL 12/2026");
    expect(rotuloDoPasso({ ferramenta: "tramitacao_da_materia", argumentos: args, ok: false })).toBe("Não encontrou a tramitação do PL 12/2026");
    expect(rotuloDoPasso({ ferramenta: "pauta_da_sessao", argumentos: {}, ok: true })).toBe("Consultou a pauta da próxima sessão");
  });

  it("a citação ganha o rótulo do passo que a sustenta", () => {
    const passos = [{ ferramenta: "pauta_da_sessao", argumentos: {}, ok: true }];
    const c = comRotuloDoPasso({ fonteId: "ferramenta:pauta_da_sessao#1", trecho: "x", status: "conferida" }, passos);
    expect(c.rotulo).toBe("A pauta da próxima sessão");
    expect(comRotuloDoPasso({ fonteId: "outra", trecho: null, status: "conferida" }, passos).rotulo).toBeUndefined();
  });

  it("B.5: conta a consulta às normas pelo artigo, e a citação de dispositivo mantém o rótulo que veio", () => {
    expect(rotuloDoPasso({ ferramenta: "buscar_dispositivos", argumentos: { consulta: "quórum do veto" }, ok: true })).toBe(
      "Consultou as normas da Casa sobre “quórum do veto”",
    );
    expect(
      rotuloDoPasso({ ferramenta: "ler_dispositivo", argumentos: { especie: "regimento_interno", endereco: "art45_par1" }, ok: true }),
    ).toBe("Consultou o art. 45 do Regimento Interno");
    expect(rotuloDoPasso({ ferramenta: "ler_dispositivo", argumentos: { especie: "lei_organica", endereco: "art9" }, ok: false })).toBe(
      "Não encontrou o art. 9 da Lei Orgânica",
    );
    expect(rotuloDoPasso({ ferramenta: "ler_dispositivo", argumentos: { "norma-id": "x", endereco: "art3" }, ok: true })).toBe(
      "Consultou o art. 3 da norma",
    );
    const c = { fonteId: "norma:abc#art45_par1", trecho: "t", status: "conferida" as const, rotulo: "Regimento Interno, art. 45, § 1º (consolidada até 30/06/2026)" };
    expect(comRotuloDoPasso(c, [{ ferramenta: "ler_dispositivo", argumentos: {}, ok: true }]).rotulo).toBe(c.rotulo);
  });

  it("B.6: o passo de ato diz que só preparou a proposta", () => {
    expect(rotuloDoPasso({ ferramenta: "modelos_de_requerimento", argumentos: {}, ok: true })).toBe(
      "Consultou os modelos de requerimento da Casa",
    );
    expect(rotuloDoPasso({ ferramenta: "protocolar_requerimento", argumentos: {}, ok: true })).toBe(
      "Preparou uma proposta de requerimento — nada foi protocolado",
    );
    expect(rotuloDoPasso({ ferramenta: "protocolar_requerimento", argumentos: {}, ok: false })).toBe(
      "Não conseguiu preparar a proposta de requerimento",
    );
  });

  it("erros em linguagem de quem usa", () => {
    expect(mensagemDeErroAssistente(403)).toBe("A Clara é da secretaria e dos vereadores.");
    expect(mensagemDeErroAssistente(500)).toMatch(/Siga pela tela/);
  });
});

describe("o histórico da Clara (ADR-0024)", () => {
  const agora = new Date("2026-10-05T20:00:00Z"); // 17h em Fortaleza, segunda-feira
  const item = (id: string, ocorridoEm: string, extra: Partial<ItemHistorico> = {}): ItemHistorico => ({
    id, conversaId: `c-${id}`, pergunta: `pergunta ${id}`, desfecho: "resposta", ocorridoEm, nFontes: 1, nPropostas: 0, ...extra,
  });

  it("o dia no fuso da Casa: hoje, ontem, dia da semana e data", () => {
    expect(rotuloDoDia("2026-10-05T13:42:00Z", agora)).toBe("Hoje");
    // 01h30 UTC do dia 5 ainda é dia 4 em Fortaleza
    expect(rotuloDoDia("2026-10-05T01:30:00Z", agora)).toBe("Ontem");
    expect(rotuloDoDia("2026-10-03T19:05:00Z", agora)).toBe("Sábado, 03/10");
    expect(rotuloDoDia("2026-09-28T12:00:00Z", agora)).toBe("28/09/2026");
    expect(rotuloDoDia("lixo", agora)).toBe("");
  });

  it("agrupa na ordem do servidor, sem reordenar", () => {
    const g = agruparPorDia(
      [item("a", "2026-10-05T13:42:00Z"), item("b", "2026-10-05T12:00:00Z"), item("c", "2026-10-03T19:05:00Z")],
      agora,
    );
    expect(g.map((x) => [x.rotulo, x.itens.map((i) => i.id)])).toEqual([["Hoje", ["a", "b"]], ["Sábado, 03/10", ["c"]]]);
  });

  it("a linha de dados: hora, fontes, propostas e o que ficou sem resposta", () => {
    expect(metaDoItem(item("a", "2026-10-05T13:42:00Z", { nFontes: 3, nPropostas: 1 }))).toBe("10h42 · 3 fontes · 1 proposta");
    expect(metaDoItem(item("a", "2026-10-05T13:00:00Z"))).toBe("10h · 1 fonte");
    expect(metaDoItem(item("a", "2026-10-05T13:00:00Z", { desfecho: "indisponivel", nFontes: 0 }))).toBe("10h · sem resposta");
  });

  it("a pergunta guardada vira a conversa que a tela de resposta desenha", () => {
    const i: InteracaoGuardada = {
      id: "i1", pergunta: "q", desfecho: "resposta",
      resposta: { texto: "t", citacoes: [], paragrafosSemFonte: [], incerteza: "normal", contaminado: false },
      passos: [{ ferramenta: "pauta_da_sessao", argumentos: {}, ok: true }], propostas: [],
      modelo: "m-1", execucaoIa: "ia-1", ocorridoEm: "2026-10-05T13:42:00Z", conteudoSha256: "a".repeat(64), integra: true,
    };
    const c = conversaDaInteracao(i);
    expect(c.resposta?.modelo).toBe("m-1");
    expect(c.resposta?.execucaoIa).toBe("ia-1");
    expect(c.indisponivel).toBeNull();
    expect(c.interacaoId).toBe("i1");
    const sem = conversaDaInteracao({ ...i, desfecho: "indisponivel", resposta: null, modelo: null, execucaoIa: null });
    expect(sem.resposta).toBeNull();
    expect(sem.indisponivel).toMatch(/Ficou sem resposta/);
  });

  it("R-IA-1 vira sinal: título do que houve, e se a pergunta ficou guardada", () => {
    expect(sinalDeIndisponivel("A Clara está indisponível agora. Siga pela tela — nada do seu trabalho depende dela.", true)).toEqual({
      titulo: "A Clara está indisponível agora",
      texto: "Siga pela tela — nada do seu trabalho depende dela. Sua pergunta ficou no histórico, sem resposta.",
    });
    const cota = sinalDeIndisponivel("A IA da Casa atingiu o limite de uso deste mês (cota da Casa). Siga pela tela.", false);
    expect(cota.titulo).toBe("A cota de IA da Casa deste mês acabou");
    expect(cota.texto).not.toMatch(/histórico/);
  });
});
