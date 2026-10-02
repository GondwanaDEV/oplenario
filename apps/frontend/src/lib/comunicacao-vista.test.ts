import { describe, expect, it } from "vitest";
import {
  adicionarAnexos,
  cienciaDoItem,
  derivarCaixa,
  enriquecerDestinos,
  entradaDoForm,
  estadoDaCiencia,
  extrairIdDoObjeto,
  faixaDeCiencia,
  formDoSubstituto,
  fraseDoAlcance,
  fraseSemAcesso,
  hrefDoObjetoLigado,
  linhaDoEnviado,
  linhaPara,
  marcaCurta,
  opcoesDoTipo,
  ordenarLinhasDeLeitura,
  paraDatetimeLocal,
  prazoParaIso,
  resumoDaLeitura,
  rotuloDoEscolhido,
  tamanhoLegivel,
  tiposDisponiveis,
  validarComunicado,
  type DestinoEscolhido,
  type FormDoComunicado,
} from "./comunicacao-vista";
import { corpoDoNovoComunicado, type CaixaOut, type ComunicadoOut, type DestinosOut, type ItemDaCaixaOut } from "./contrato-comunicacao";

// A suíte roda em America/Fortaleza (vitest.config.ts): as datas abaixo são escolhidas para o dia da CASA.
const AGORA = "2026-10-02T15:00:00.000Z"; // 12:00 em Fortaleza
const AGORA_MS = Date.parse(AGORA);

function itemCaixa(extra: Partial<ItemDaCaixaOut> = {}): ItemDaCaixaOut {
  return {
    id: "c1", protocolo: "COM-2026-000001", assunto: "Sessão extraordinária", remetenteNome: "Rita Campos",
    enviadoEm: "2026-10-02T14:00:00Z", exigeCiencia: false, cienciaAte: null, vencido: false,
    recebidoEm: "2026-10-02T14:01:00Z", lidoEm: null, cienteEm: null, ...extra,
  };
}

const caixa = (itens: ItemDaCaixaOut[], extra: Partial<CaixaOut> = {}): CaixaOut => ({
  itens, naoLidos: itens.filter((i) => !i.lidoEm).length, pendentesCiencia: 0, proximaCienciaAte: null, ...extra,
});

const aviso = (id: string, criadoEm: string, lidaEm: string | null = null) => ({
  id, categoria: "norma_publicada", assunto: `Aviso ${id}`, corpo: "corpo", objetoTipo: "proposicao", objetoId: "p1", criadoEm, lidaEm,
});

const href = (id: string) => `/comunicados/${id}`;

describe("derivarCaixa", () => {
  it("junta as duas origens por data e agrupa pelo dia da Casa", () => {
    const v = derivarCaixa(
      caixa([itemCaixa({ id: "c1", enviadoEm: "2026-10-02T14:00:00Z" }), itemCaixa({ id: "c2", enviadoEm: "2026-09-20T12:00:00Z" })]),
      { notificacoes: [aviso("a1", "2026-10-01T12:00:00Z")], naoLidas: 1, notificacoesTotal: 1 },
      AGORA, "tudo", href,
    );
    expect(v.grupos.map((g) => [g.rotulo, g.itens.map((i) => i.chave)])).toEqual([
      ["Hoje", ["comunicado:c1"]],
      ["Últimos 7 dias", ["aviso:a1"]],
      ["Antes", ["comunicado:c2"]],
    ]);
    expect(v.naoLidos).toBe(3);
    expect(v.grupos[0].itens[0].detalhe).toBe("De Rita Campos · COM-2026-000001");
    expect(v.grupos[0].itens[0].href).toBe("/comunicados/c1");
  });

  it("os quatro filtros aparecem sempre, mesmo com zero, para a barra não mudar de forma", () => {
    const v = derivarCaixa(caixa([itemCaixa({ lidoEm: "2026-10-02T14:05:00Z" })]), null, AGORA, "tudo", href);
    expect(v.filtros.map((f) => `${f.rotulo}:${f.quantidade}`)).toEqual(["Tudo:1", "Não lidos:0", "Para ciência:0", "Do sistema:0"]);
  });

  it("filtro desconhecido cai em Tudo; filtro vazio é distinto de caixa vazia", () => {
    const c = caixa([itemCaixa({ lidoEm: "2026-10-02T14:05:00Z" })]);
    expect(derivarCaixa(c, null, AGORA, "xyz" as never, href).filtroAtivo).toBe("tudo");
    const v = derivarCaixa(c, null, AGORA, "nao-lidos", href);
    expect(v.vaziaNoFiltro).toBe(true);
    expect(v.vazia).toBe(false);
    expect(derivarCaixa(null, null, AGORA, "tudo", href).vazia).toBe(true);
  });

  it("'Para ciência' só tem o comunicado que pede e ainda não teve ciência", () => {
    const v = derivarCaixa(
      caixa([
        itemCaixa({ id: "a", exigeCiencia: true }),
        itemCaixa({ id: "b", exigeCiencia: true, cienteEm: "2026-10-02T14:10:00Z" }),
        itemCaixa({ id: "c" }),
      ]),
      null, AGORA, "ciencia", href,
    );
    expect(v.grupos.flatMap((g) => g.itens.map((i) => i.id))).toEqual(["a"]);
  });

  it("avisa o corte da lista de comunicados quando há não lido fora dela", () => {
    const v = derivarCaixa(caixa([itemCaixa({ lidoEm: "2026-10-02T14:05:00Z" })], { naoLidos: 3 }), null, AGORA, "tudo", href);
    expect(v.avisoDeCorte).toBe("A caixa mostra só os itens mais recentes: 3 comunicados não lidos mais antigos ficaram fora dela.");
  });
});

describe("a ciência", () => {
  it("o chip de cada item", () => {
    expect(cienciaDoItem(itemCaixa())).toBeNull();
    expect(cienciaDoItem(itemCaixa({ exigeCiencia: true }))).toEqual({ estado: "pendente", rotulo: "Pede ciência" });
    expect(cienciaDoItem(itemCaixa({ exigeCiencia: true, cienciaAte: "2026-10-05T20:00:00Z" }))).toEqual({ estado: "pendente", rotulo: "Ciência até 05/10" });
    expect(cienciaDoItem(itemCaixa({ exigeCiencia: true, cienciaAte: "2026-09-30T20:00:00Z", vencido: true }))?.rotulo).toBe("Ciência vencida em 30/09");
    expect(cienciaDoItem(itemCaixa({ exigeCiencia: true, cienteEm: "x" }))!.estado).toBe("dada");
  });

  it("a faixa do topo: quantos, o mais próximo no futuro e os já vencidos", () => {
    const c = caixa(
      [itemCaixa({ id: "a", exigeCiencia: true, cienciaAte: "2026-10-05T20:00:00Z" }), itemCaixa({ id: "b", exigeCiencia: true, cienciaAte: "2026-09-30T20:00:00Z", vencido: true })],
      { pendentesCiencia: 2, proximaCienciaAte: "2026-10-05T20:00:00Z" },
    );
    expect(faixaDeCiencia(c, AGORA)).toBe("Você tem 2 comunicados aguardando ciência — o mais próximo vence em 05/10; 1 já passou do prazo.");
    expect(faixaDeCiencia(caixa([itemCaixa({ exigeCiencia: true })], { pendentesCiencia: 1 }), AGORA)).toBe("Você tem 1 comunicado aguardando ciência.");
    // um "mais próximo" que já passou não vira "vence em": a frase seria falsa
    expect(faixaDeCiencia(caixa([], { pendentesCiencia: 1, proximaCienciaAte: "2026-09-30T20:00:00Z" }), AGORA)).toBe("Você tem 1 comunicado aguardando ciência.");
    expect(faixaDeCiencia(caixa([]), AGORA)).toBeNull();
    expect(faixaDeCiencia(null, AGORA)).toBeNull();
  });

  it("o estado da ciência no comunicado aberto", () => {
    const base = { exigeCiencia: true, cienciaAte: null, minhasMarcas: { recebidoEm: "x", lidoEm: "x", cienteEm: null } } as unknown as ComunicadoOut;
    expect(estadoDaCiencia({ ...base, exigeCiencia: false }, AGORA_MS)).toBe("nao-exige");
    expect(estadoDaCiencia({ ...base, minhasMarcas: null }, AGORA_MS)).toBe("nao-destinatario");
    expect(estadoDaCiencia(base, AGORA_MS)).toBe("pendente");
    expect(estadoDaCiencia({ ...base, cienciaAte: "2026-10-01T12:00:00Z" }, AGORA_MS)).toBe("vencida");
    expect(estadoDaCiencia({ ...base, minhasMarcas: { recebidoEm: "x", lidoEm: "x", cienteEm: "y" } }, AGORA_MS)).toBe("dada");
  });
});

describe("os destinos", () => {
  const opcoes: DestinosOut = {
    podeEnviarAGrupos: true,
    setores: [{ id: "s2", nome: "Protocolo", membros: 2 }, { id: "s1", nome: "Jurídico", membros: 3 }],
    comissoes: [{ id: "k1", nome: "Comissão de Finanças", membros: 5 }],
    vereadores: [{ id: "v1", nome: "Helena Past" }],
    pessoas: [{ identidadeId: "i1", nome: "Rita Campos" }],
  };

  it("'Para:' por extenso", () => {
    expect(linhaPara([{ tipo: "setor", alvoNome: "Jurídico" }, { tipo: "comissao", alvoNome: "Comissão de Finanças" }, { tipo: "todos", alvoNome: null }]))
      .toBe("Para: setor Jurídico, Comissão de Finanças, todos os setores");
  });

  it("grupo só para quem pode (Eixo 3)", () => {
    expect(tiposDisponiveis(false).map((t) => t.tipo)).toEqual(["pessoa", "vereador"]);
    expect(tiposDisponiveis(true).map((t) => t.tipo)).toEqual(["pessoa", "vereador", "setor", "comissao", "todos"]);
  });

  it("as opções de cada tipo, em ordem alfabética", () => {
    expect(opcoesDoTipo("setor", opcoes).map((o) => o.nome)).toEqual(["Jurídico", "Protocolo"]);
    expect(opcoesDoTipo("pessoa", opcoes)).toEqual([{ id: "i1", nome: "Rita Campos", membros: 1 }]);
    expect(opcoesDoTipo("todos", opcoes)).toEqual([]);
  });

  it("o alcance é estimativa ('até'), exato só para uma pessoa; 'todos' sem número inventado", () => {
    const pessoa: DestinoEscolhido = { tipo: "pessoa", alvoId: "i1", nome: "Rita", membros: 1 };
    const setor: DestinoEscolhido = { tipo: "setor", alvoId: "s1", nome: "Jurídico", membros: 3 };
    const todos: DestinoEscolhido = { tipo: "todos", alvoId: null, nome: null, membros: null };
    expect(fraseDoAlcance([])).toBeNull();
    expect(fraseDoAlcance([pessoa])).toBe("Vai para 1 pessoa.");
    expect(fraseDoAlcance([setor, pessoa])).toBe("Vai para até 4 pessoas.");
    expect(fraseDoAlcance([todos])).toBe("Vai para todos os servidores e a administração da Casa.");
    expect(fraseDoAlcance([todos, setor])).toBe("Vai para todos os servidores e a administração da Casa, e até 3 pessoas pelos outros destinos.");
    expect(fraseDoAlcance([{ ...setor, membros: 0 }])).toBe("Os destinos escolhidos não têm ninguém por enquanto.");
    expect(rotuloDoEscolhido(setor)).toBe("setor Jurídico · 3 pessoas");
  });

  it("o substituto herda os destinos e ganha a contagem atual das opções", () => {
    const c = { assunto: "A", corpo: "B", exigeCiencia: true, destinos: [{ tipo: "setor", alvoId: "s1", alvoNome: "Jurídico" }, { tipo: "todos", alvoId: null, alvoNome: null }] } as ComunicadoOut;
    const f = formDoSubstituto(c);
    expect(f).toMatchObject({ assunto: "A", corpo: "B", exigeCiencia: true });
    expect(enriquecerDestinos(f.destinos, opcoes)).toEqual([
      { tipo: "setor", alvoId: "s1", nome: "Jurídico", membros: 3 },
      { tipo: "todos", alvoId: null, nome: null, membros: null },
    ]);
  });
});

describe("o formulário", () => {
  const vazio: FormDoComunicado = { assunto: "", corpo: "", destinos: [], exigeCiencia: false, prazo: "", objetoTipo: "", objetoRef: "", anexos: [] };
  const valido: FormDoComunicado = { ...vazio, assunto: "Sessão", corpo: "Texto", destinos: [{ tipo: "pessoa", alvoId: "i1", nome: "Rita", membros: 1 }] };

  it("diz o que falta, campo a campo", () => {
    expect(Object.keys(validarComunicado(vazio, AGORA_MS))).toEqual(["destinos", "assunto", "corpo"]);
    expect(validarComunicado(valido, AGORA_MS)).toEqual({});
    expect(validarComunicado({ ...valido, assunto: "x".repeat(201) }, AGORA_MS).assunto).toMatch(/200 caracteres/);
  });

  it("o prazo da ciência é opcional, mas no futuro", () => {
    expect(validarComunicado({ ...valido, exigeCiencia: true, prazo: "" }, AGORA_MS)).toEqual({});
    expect(validarComunicado({ ...valido, exigeCiencia: true, prazo: "2026-10-01T10:00" }, AGORA_MS).prazo).toBe("O prazo precisa ser depois de agora.");
    expect(validarComunicado({ ...valido, exigeCiencia: true, prazo: "2026-10-05T18:00" }, AGORA_MS)).toEqual({});
    // sem "exige ciência", o prazo esquecido no campo não reprova nem viaja
    expect(validarComunicado({ ...valido, prazo: "2026-10-01T10:00" }, AGORA_MS)).toEqual({});
  });

  it("o link do item: o último uuid do endereço colado, ou o identificador simples", () => {
    expect(extrairIdDoObjeto("https://camara.local/ficha-materia/0f8fad5b-d9cb-469f-a165-70867728950e?token=x")).toBe("0f8fad5b-d9cb-469f-a165-70867728950e");
    expect(extrairIdDoObjeto("  abc-123 ")).toBe("abc-123");
    expect(extrairIdDoObjeto("uma frase qualquer")).toBeNull();
    expect(validarComunicado({ ...valido, objetoTipo: "proposicao", objetoRef: "" }, AGORA_MS).objeto).toMatch(/Cole o endereço/);
  });

  it("anexos: até 5, de até 10 MB, sem repetir", () => {
    const arq = (nome: string) => new File([new Uint8Array(1)], nome, { type: "application/pdf" });
    const grande = Object.defineProperty(arq("grande.pdf"), "size", { value: 11 * 1024 * 1024 });
    const r = adicionarAnexos([], [arq("a.pdf"), arq("a.pdf"), grande, arq("b.pdf"), arq("c.pdf"), arq("d.pdf"), arq("e.pdf"), arq("f.pdf")]);
    expect(r.anexos.map((a) => a.name)).toEqual(["a.pdf", "b.pdf", "c.pdf", "d.pdf", "e.pdf"]);
    expect(r.recusados).toEqual(["“grande.pdf” passa de 10 MB e não foi incluído.", "“f.pdf” não foi incluído: são no máximo 5 anexos."]);
    expect(validarComunicado({ ...valido, anexos: [grande] }, AGORA_MS).anexos).toMatch(/passa de 10 MB/);
  });

  it("a entrada do POST e o corpo kebab do fio", () => {
    const f: FormDoComunicado = {
      ...valido,
      destinos: [...valido.destinos, { tipo: "todos", alvoId: null, nome: null, membros: null }],
      exigeCiencia: true, prazo: "2026-10-05T18:00", objetoTipo: "proposicao", objetoRef: "/ficha-materia/0f8fad5b-d9cb-469f-a165-70867728950e",
    };
    const e = entradaDoForm(f, "orig-1");
    expect(e.cienciaAte).toBe(prazoParaIso("2026-10-05T18:00"));
    expect(e.cienciaAte).toBe("2026-10-05T21:00:00.000Z"); // 18h em Fortaleza
    expect(corpoDoNovoComunicado(e)).toEqual({
      assunto: "Sessão", corpo: "Texto", "exige-ciencia": true, "ciencia-ate": "2026-10-05T21:00:00.000Z", "substitui-id": "orig-1",
      objeto: { tipo: "proposicao", id: "0f8fad5b-d9cb-469f-a165-70867728950e" },
      destinos: [{ tipo: "pessoa", "alvo-id": "i1" }, { tipo: "todos", "alvo-id": null }],
    });
  });

  it("o min do datetime-local é o agora no formato do campo", () => {
    expect(paraDatetimeLocal(AGORA_MS)).toBe("2026-10-02T12:00");
  });

  it("quem ficou de fora por não ter acesso", () => {
    expect(fraseSemAcesso(0, true)).toBeNull();
    expect(fraseSemAcesso(1, true)).toBe("1 vereador da comissão ainda não tem acesso ao sistema e não vai receber este comunicado. O acesso é concedido pelo administrador da Casa.");
    expect(fraseSemAcesso(2, false)).toMatch(/^2 vereadores ainda não têm acesso ao sistema e não vão receber/);
  });
});

describe("o comunicado, a leitura e os enviados", () => {
  it("o link do item ligado: matéria e sessão têm tela; protocolo não (sem link quebrado)", () => {
    expect(hrefDoObjetoLigado({ tipo: "proposicao", id: "p1" })).toBe("/ficha-materia/p1");
    expect(hrefDoObjetoLigado({ tipo: "sessao", id: "s1" })).toBe("/sessoes/s1/plenario");
    expect(hrefDoObjetoLigado({ tipo: "protocolo", id: "x" })).toBe("");
    expect(hrefDoObjetoLigado(null)).toBe("");
  });

  it("o resumo do painel de leitura", () => {
    const t = { destinatarios: 15, recebidos: 14, lidos: 12, cientes: 9, pendentesVencidos: 0 };
    expect(resumoDaLeitura(t, true)).toBe("12 de 15 leram · 9 cientes · 3 faltam ler");
    expect(resumoDaLeitura({ ...t, pendentesVencidos: 2 }, true)).toBe("12 de 15 leram · 9 cientes · 3 faltam ler · 2 com prazo vencido");
    expect(resumoDaLeitura({ ...t, lidos: 15 }, false)).toBe("15 de 15 leram · ninguém falta ler");
  });

  it("a tabela põe quem falta primeiro: vencidos, depois devendo, depois em dia", () => {
    const l = (id: string, x: Partial<{ vencido: boolean; lidoEm: string | null; cienteEm: string | null }>) =>
      ({ identidadeId: id, nome: id, via: "direto", recebidoEm: "r", lidoEm: null, cienteEm: null, vencido: false, ...x });
    const linhas = [l("ok", { lidoEm: "x", cienteEm: "y" }), l("deve", { lidoEm: "x" }), l("venc", { vencido: true }), l("deve2", {})];
    expect(ordenarLinhasDeLeitura(linhas, true).map((x) => x.identidadeId)).toEqual(["venc", "deve", "deve2", "ok"]);
    expect(ordenarLinhasDeLeitura(linhas, false).map((x) => x.identidadeId)).toEqual(["venc", "deve2", "ok", "deve"]);
  });

  it("a linha dos enviados", () => {
    const i = { id: "c", protocolo: "P", assunto: "A", enviadoEm: "x", remetenteNome: "R", exigeCiencia: true, cienciaAte: null, destinatarios: 15, recebidos: 14, lidos: 12, cientes: 9, pendentesVencidos: 2 };
    expect(linhaDoEnviado(i)).toEqual({ leram: "12 de 15 leram", cientes: "9 de 15 cientes", vencidos: "2 pendentes vencidos" });
    expect(linhaDoEnviado({ ...i, exigeCiencia: false, pendentesVencidos: 0 })).toEqual({ leram: "12 de 15 leram", cientes: null, vencidos: null });
  });

  it("formatos: a marca curta no fuso da Casa e o tamanho legível", () => {
    expect(marcaCurta("2026-10-02T17:05:00Z")).toBe("02/10 às 14:05");
    expect(marcaCurta(null)).toBeNull();
    expect(tamanhoLegivel(512)).toBe("512 B");
    expect(tamanhoLegivel(820 * 1024)).toBe("820 KB");
    expect(tamanhoLegivel(1.4 * 1024 * 1024)).toBe("1,4 MB");
  });
});
