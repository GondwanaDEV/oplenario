import { describe, expect, it } from "vitest";
import {
  ABAS_FILA,
  CONCLUSOES,
  blocoDeAssinatura,
  camposDoParecer,
  camposIguais,
  corpoDoPedido,
  corpoDoRascunho,
  faltaParaAssinar,
  formatarOab,
  AVISO_CONSULTA_AVULSA,
  AVISO_TEXTO_DE_IA,
  frasePortal,
  frasesFaltaParaAssinar,
  linhaDeOrigemDoParecer,
  linhaDoPedido,
  mensagemDeErroJuridico,
  modoDoDetalhe,
  numeroDoParecer,
  oabValida,
  podeCancelar,
  podeSalvarRascunho,
  podeSubstituir,
  refDoPedido,
  rotuloConclusao,
  rotuloEstadoPedido,
  rotuloQualificacao,
  seloDoParecer,
  situacaoDoPedido,
  vazioDaFila,
  validarConcessaoJuridico,
  validarPedido,
} from "./juridico-vista";
import type { ParecerJuridicoOut, PedidoJuridicoOut } from "./contrato-juridico.gen";

const parecer = (extra: Partial<ParecerJuridicoOut> = {}): ParecerJuridicoOut => ({
  id: "pj1", numero: null, ano: null, estado: "rascunho", relatorio: "", fundamentacao: "", conclusao: null,
  assinatura: null, substituiId: null, substituido: false, ...extra,
});
const pedido = (extra: Partial<PedidoJuridicoOut> = {}): PedidoJuridicoOut => ({
  id: "ped1", proposicao: { id: "p1", ref: "PL 7/2026", ementa: "Cria o programa" }, assunto: "Análise jurídica da matéria",
  prazo: null, estado: "pendente", pedidoPor: "Rita Campos", emNomeDe: null, origem: "secretaria",
  criadoEm: "2026-09-29T13:00:00Z", parecer: null, ...extra,
});

describe("rótulos", () => {
  it("estado do pedido e abas da fila", () => {
    expect(rotuloEstadoPedido("pendente")).toBe("Pendente");
    expect(rotuloEstadoPedido("atendido")).toBe("Atendido");
    expect(rotuloEstadoPedido("cancelado")).toBe("Cancelado");
    expect(rotuloEstadoPedido("outro")).toBe("outro"); // estado desconhecido sai cru, nunca inventado
    expect(ABAS_FILA.map((a) => a.rotulo)).toEqual(["Pendentes", "Atendidos", "Cancelados"]);
  });

  it("conclusão: as 4 do vocabulário, sem underscore na tela", () => {
    expect(CONCLUSOES.map((c) => c.valor)).toEqual(["favoravel", "contrario", "com_ressalvas", "orientacao"]);
    expect(rotuloConclusao("favoravel")).toBe("Favorável");
    expect(rotuloConclusao("contrario")).toBe("Contrário");
    expect(rotuloConclusao("com_ressalvas")).toBe("Com ressalvas");
    expect(rotuloConclusao("orientacao")).toBe("Orientação");
    expect(rotuloConclusao(null)).toBe("Sem conclusão");
    expect(CONCLUSOES.every((c) => !c.rotulo.includes("_"))).toBe(true);
  });

  it("qualificação", () => {
    expect(rotuloQualificacao("efetivo")).toBe("Procurador(a) efetivo(a)");
    expect(rotuloQualificacao("comissionado")).toBe("Assessor(a) jurídico(a) comissionado(a)");
    expect(rotuloQualificacao("contratado")).toBe("Advogado(a) contratado(a)");
    expect(rotuloQualificacao(null)).toBe("");
    expect(rotuloQualificacao("estagiario")).toBe("estagiario");
  });

  it("fila vazia: a mensagem depende de quem olha e da aba", () => {
    expect(vazioDaFila("pendente", true)).toMatch(/Peça um parecer/);
    expect(vazioDaFila("pendente", false)).toBe("Nenhum pedido pendente para você.");
    expect(vazioDaFila("atendido", true)).toBe("Nenhum pedido atendido ainda.");
    expect(vazioDaFila("cancelado", false)).toBe("Nenhum pedido cancelado.");
  });
});

describe("OAB", () => {
  it("aceita UF + número, com separador livre e letra suplementar", () => {
    for (const ok of ["CE 12345", "CE12345", "ce-12345", "CE - 12345", "SP 1234A", " CE 1 "]) expect(oabValida(ok)).toBe(true);
  });
  it("recusa o que o backend recusa", () => {
    for (const ruim of ["12345", "CEE 12345", "CE", "CE 12345678", "CE 12AB", ""]) expect(oabValida(ruim)).toBe(false);
  });
  it("formata 'OAB/UF número'; o que não reconhece sai cru com o prefixo, sem inventar", () => {
    expect(formatarOab("ce12345")).toBe("OAB/CE 12345");
    expect(formatarOab("CE - 12345a")).toBe("OAB/CE 12345A");
    expect(formatarOab("registro estranho")).toBe("OAB registro estranho");
    expect(formatarOab(null)).toBe("");
  });
});

describe("pedido", () => {
  it("ref: a matéria, ou 'Consulta avulsa'", () => {
    expect(refDoPedido(pedido())).toBe("PL 7/2026");
    expect(refDoPedido(pedido({ proposicao: null }))).toBe("Consulta avulsa");
  });

  it("linha do pedido: só o que existe", () => {
    expect(linhaDoPedido(pedido())).toBe("Pedido por Rita Campos em 29/09/2026");
    expect(linhaDoPedido(pedido({ emNomeDe: "Presidência", prazo: "2026-10-15" }))).toBe(
      "Pedido por Rita Campos, em nome de Presidência em 29/09/2026 · prazo 15/10/2026",
    );
    expect(linhaDoPedido(pedido({ pedidoPor: null }))).toBe("Pedido pela secretaria em 29/09/2026");
    expect(linhaDoPedido(pedido({ origem: "relator", pedidoPor: "Ver. Helena" }))).toBe("Pedido pelo relator Ver. Helena em 29/09/2026");
    expect(linhaDoPedido(pedido({ origem: "relator", pedidoPor: null }))).toBe("Pedido pelo relator da comissão em 29/09/2026");
  });

  it("o prazo date-only não recua um dia no fuso local", () => {
    expect(linhaDoPedido(pedido({ prazo: "2026-08-15" }))).toContain("prazo 15/08/2026");
  });

  it("situação do pedido na fila", () => {
    expect(situacaoDoPedido(pedido())).toBe("Aguardando o parecer");
    expect(situacaoDoPedido(pedido({ estado: "cancelado" }))).toBe("Pedido cancelado");
    expect(situacaoDoPedido(pedido({ parecer: parecer() }))).toBe("Rascunho em andamento");
    expect(situacaoDoPedido(pedido({ parecer: parecer({ substituiId: "x" }) }))).toMatch(/substitui o anterior/);
    expect(
      situacaoDoPedido(pedido({ estado: "atendido", parecer: parecer({ estado: "assinado", numero: 3, ano: 2026, conclusao: "contrario" }) })),
    ).toBe("Parecer jurídico nº 3/2026 · Contrário");
  });
});

describe("parecer", () => {
  it("selo: rascunho, assinado ou substituído", () => {
    expect(seloDoParecer(parecer())).toEqual({ texto: "Rascunho", tom: "rascunho" });
    expect(seloDoParecer(parecer({ estado: "assinado" }))).toEqual({ texto: "Assinado", tom: "assinado" });
    expect(seloDoParecer(parecer({ estado: "assinado", substituido: true }))).toEqual({ texto: "Substituído", tom: "substituido" });
  });

  it("número só existe depois de assinado", () => {
    expect(numeroDoParecer(parecer())).toBeNull();
    expect(numeroDoParecer(parecer({ numero: 3, ano: 2026 }))).toBe("Parecer jurídico nº 3/2026");
  });

  it("bloco de assinatura: nome, OAB e qualificação do snapshot, e a data", () => {
    const b = blocoDeAssinatura({ nome: "Lúcia Prado", oab: "CE 12345", qualificacao: "efetivo", em: "2026-09-30T14:05:00" });
    expect(b.nome).toBe("Lúcia Prado");
    expect(b.registro).toBe("OAB/CE 12345 · Procurador(a) efetivo(a)");
    expect(b.quando).toBe("Assinado em 30/09/2026, 14:05");
  });
});

describe("editor", () => {
  const vazio = { relatorio: "", fundamentacao: "", conclusao: "" };
  const cheio = { relatorio: "R", fundamentacao: "F", conclusao: "favoravel" };

  it("o que falta para assinar", () => {
    expect(faltaParaAssinar(vazio)).toEqual(["o relatório", "a fundamentação", "a conclusão"]);
    expect(faltaParaAssinar({ ...cheio, relatorio: "   " })).toEqual(["o relatório"]);
    expect(faltaParaAssinar(cheio)).toEqual([]);
    expect(frasesFaltaParaAssinar(vazio)).toBe("Para assinar, falta preencher o relatório, a fundamentação e a conclusão.");
    expect(frasesFaltaParaAssinar({ ...cheio, conclusao: "" })).toBe("Para assinar, falta preencher a conclusão.");
    expect(frasesFaltaParaAssinar(cheio)).toBeNull();
  });

  it("salvar rascunho basta ter algo; assinar exige tudo", () => {
    expect(podeSalvarRascunho(vazio)).toBe(false);
    expect(podeSalvarRascunho({ ...vazio, relatorio: "x" })).toBe(true);
    expect(podeSalvarRascunho({ ...vazio, conclusao: "contrario" })).toBe(true);
  });

  it("campos vêm do parecer (ou vazios) e a comparação é exata", () => {
    expect(camposDoParecer(null)).toEqual(vazio);
    expect(camposDoParecer(parecer({ relatorio: "R", conclusao: "favoravel" }))).toEqual({ relatorio: "R", fundamentacao: "", conclusao: "favoravel" });
    expect(camposIguais(cheio, { ...cheio })).toBe(true);
    expect(camposIguais(cheio, { ...cheio, relatorio: "R " })).toBe(false);
  });

  it("corpo do PUT: chaves do fio; sem conclusão, a chave não vai", () => {
    expect(corpoDoRascunho(cheio)).toEqual({ relatorio: "R", fundamentacao: "F", conclusao: "favoravel" });
    expect(corpoDoRascunho({ ...cheio, conclusao: "" })).toEqual({ relatorio: "R", fundamentacao: "F" });
  });

  it("modo do detalhe: só o jurídico escreve, enquanto o vigente é rascunho ou não existe", () => {
    expect(modoDoDetalhe(pedido(), true)).toBe("escrever");
    expect(modoDoDetalhe(pedido({ parecer: parecer() }), true)).toBe("escrever");
    expect(modoDoDetalhe(pedido({ estado: "atendido", parecer: parecer({ estado: "assinado" }) }), true)).toBe("ler");
    expect(modoDoDetalhe(pedido({ estado: "cancelado" }), true)).toBe("ler");
    expect(modoDoDetalhe(pedido(), false)).toBe("ler"); // a secretaria só lê
  });

  it("substituir: só o jurídico, com o pedido atendido e o parecer assinado", () => {
    const atendido = pedido({ estado: "atendido", parecer: parecer({ estado: "assinado" }) });
    expect(podeSubstituir(atendido, true)).toBe(true);
    expect(podeSubstituir(atendido, false)).toBe(false);
    expect(podeSubstituir(pedido({ parecer: parecer() }), true)).toBe(false);
  });

  it("cancelar: só a secretaria e só o pendente", () => {
    expect(podeCancelar(pedido(), true)).toBe(true);
    expect(podeCancelar(pedido(), false)).toBe(false);
    expect(podeCancelar(pedido({ estado: "atendido" }), true)).toBe(false);
    expect(podeCancelar(pedido({ estado: "cancelado" }), true)).toBe(false);
  });
});

describe("novo pedido", () => {
  const e = (extra = {}) => ({ assunto: "", prazo: "", emNomeDe: "", ...extra });

  it("consulta avulsa exige o assunto (5 a 300)", () => {
    expect(validarPedido(e(), false).assunto).toMatch(/mínimo de 5/);
    expect(validarPedido(e({ assunto: "abc" }), false).assunto).toBeDefined();
    expect(validarPedido(e({ assunto: "Decoro do vereador X" }), false)).toEqual({});
    expect(validarPedido(e({ assunto: "x".repeat(301) }), false).assunto).toMatch(/passa de 300/);
  });

  it("com matéria o assunto é opcional, mas se vier não pode ser curtinho demais", () => {
    expect(validarPedido(e(), true)).toEqual({});
    expect(validarPedido(e({ assunto: "abc" }), true).assunto).toMatch(/ou deixe em branco/);
  });

  it("prazo e 'em nome de'", () => {
    expect(validarPedido(e({ assunto: "Decoro do vereador X", prazo: "15/10/2026" }), false).prazo).toBeDefined();
    expect(validarPedido(e({ assunto: "Decoro do vereador X", prazo: "2026-10-15" }), false)).toEqual({});
    expect(validarPedido(e({ assunto: "Decoro do vereador X", emNomeDe: "y".repeat(81) }), false).emNomeDe).toBeDefined();
  });

  it("corpo do POST: chaves do fio, campos vazios não vão", () => {
    expect(corpoDoPedido(e({ assunto: " Decoro do vereador X ", prazo: "2026-10-15", emNomeDe: " Presidência " }))).toEqual({
      assunto: "Decoro do vereador X", prazo: "2026-10-15", "em-nome-de": "Presidência",
    });
    expect(corpoDoPedido(e(), "p1")).toEqual({ "proposicao-id": "p1" });
  });
});

describe("concessão do papel", () => {
  it("qualificação e OAB obrigatórias, OAB no formato", () => {
    expect(validarConcessaoJuridico({ qualificacao: "", oab: "" })).toEqual({ qualificacao: "Escolha a qualificação.", oab: "Informe a OAB (ex.: CE 12345)." });
    expect(validarConcessaoJuridico({ qualificacao: "estagiario", oab: "CE 1" }).qualificacao).toBeDefined();
    expect(validarConcessaoJuridico({ qualificacao: "efetivo", oab: "12345" }).oab).toMatch(/UF e número/);
    expect(validarConcessaoJuridico({ qualificacao: "contratado", oab: "CE 12345" })).toEqual({});
  });
});

describe("mensagens de erro (401/403/404/409/400)", () => {
  it("cada status tem frase própria e honesta", () => {
    expect(mensagemDeErroJuridico(0, "salvar")).toMatch(/Nada foi gravado/);
    expect(mensagemDeErroJuridico(401, "abrir")).toMatch(/sessão expirou/);
    expect(mensagemDeErroJuridico(404, "abrir")).toMatch(/Não encontramos este pedido/);
  });

  it("403 ao assinar diz que falta qualificação e OAB; nas demais, quem pode", () => {
    expect(mensagemDeErroJuridico(403, "assinar")).toMatch(/qualificação e a OAB/);
    expect(mensagemDeErroJuridico(403, "cancelar")).toMatch(/Só a secretaria/);
    expect(mensagemDeErroJuridico(403, "listar")).toMatch(/jurídico e a secretaria/);
  });

  it("409 depende da ação", () => {
    expect(mensagemDeErroJuridico(409, "cancelar")).toMatch(/já foi atendido/);
    expect(mensagemDeErroJuridico(409, "salvar")).toMatch(/substitua/);
    expect(mensagemDeErroJuridico(409, "assinar")).toMatch(/já foi assinado/);
    expect(mensagemDeErroJuridico(409, "substituir")).toMatch(/rascunho em andamento/);
  });

  it("400 usa o erro do servidor quando há; senão uma frase genérica", () => {
    expect(mensagemDeErroJuridico(400, "pedir", "assunto muito curto")).toBe("assunto muito curto");
    expect(mensagemDeErroJuridico(400, "pedir")).toMatch(/Confira os campos/);
    expect(mensagemDeErroJuridico(400, "assinar")).toMatch(/relatório, a fundamentação ou a conclusão/);
  });

  it("5xx: frase neutra, sem vazar detalhe", () => {
    expect(mensagemDeErroJuridico(500, "listar", "stack trace")).not.toMatch(/stack/);
  });
});

describe("ADR-0019 fatia 2a — a origem do rascunho (nota técnica da IA)", () => {
  const assinatura = { nome: "Lúcia Prado", oab: "CE 12345", qualificacao: "contratado", em: "2026-09-30T14:05:00" };

  it("assinado a partir da nota: diz que foi revisado e assinado por quem assinou", () => {
    expect(linhaDeOrigemDoParecer(parecer({ estado: "assinado", origemRascunho: "nota_tecnica", assinatura })))
      .toBe("Rascunho iniciado a partir de nota técnica da IA, revisado e assinado por Lúcia Prado.");
  });

  it("rascunho a partir da nota: lembra que o advogado revisa, decide a conclusão e assina", () => {
    const l = linhaDeOrigemDoParecer(parecer({ estado: "rascunho", origemRascunho: "nota_tecnica" }));
    expect(l).toMatch(/nota técnica da IA/);
    expect(l).toMatch(/escolha a conclusão e assine só o que assumir/);
  });

  it("sem origem (escrito do zero) não há linha", () => {
    expect(linhaDeOrigemDoParecer(parecer({ estado: "assinado", assinatura }))).toBeNull();
    expect(linhaDeOrigemDoParecer(parecer({ origemRascunho: null }))).toBeNull();
  });

  it("o texto da IA nunca é chamado de parecer: nenhuma frase da origem chama a nota de 'parecer' da IA", () => {
    const frases = [
      linhaDeOrigemDoParecer(parecer({ estado: "assinado", origemRascunho: "nota_tecnica", assinatura })),
      linhaDeOrigemDoParecer(parecer({ estado: "rascunho", origemRascunho: "nota_tecnica" })),
      AVISO_TEXTO_DE_IA,
    ].join(" ");
    expect(frases).not.toMatch(/parecer (da|de) IA|parecer da inteligência/i);
    expect(AVISO_TEXTO_DE_IA).toMatch(/Texto de IA não é parecer/);
  });

  it("o pedido aberto a partir da nota diz isso, com quem clicou", () => {
    expect(linhaDoPedido(pedido({ origem: "nota_tecnica", pedidoPor: "Lúcia Prado" })))
      .toBe("Aberto por Lúcia Prado a partir da nota técnica da IA em 29/09/2026");
    expect(linhaDoPedido(pedido({ origem: "nota_tecnica", pedidoPor: null }))).toMatch(/^Aberto a partir da nota técnica da IA/);
  });

  it("na fila: rascunho vindo da nota tem frase própria; substituição segue a sua", () => {
    expect(situacaoDoPedido(pedido({ parecer: parecer({ origemRascunho: "nota_tecnica" }) })))
      .toBe("Rascunho a partir da nota técnica da IA, a revisar");
    expect(situacaoDoPedido(pedido({ parecer: parecer({ substituiId: "x", origemRascunho: null }) })))
      .toBe("Novo parecer em rascunho (substitui o anterior)");
    expect(situacaoDoPedido(pedido({ parecer: parecer() }))).toBe("Rascunho em andamento");
  });

  it("erros de 'usar como rascunho': 404 fala da nota; 409 manda abrir o pedido; 403 diz quem pode", () => {
    expect(mensagemDeErroJuridico(404, "usar-nota")).toMatch(/nota técnica/);
    expect(mensagemDeErroJuridico(409, "usar-nota")).toMatch(/Abra o pedido na fila/);
    expect(mensagemDeErroJuridico(403, "usar-nota")).toMatch(/Só o jurídico da Casa/);
    expect(mensagemDeErroJuridico(404, "abrir")).toMatch(/Não encontramos este pedido/);
  });
});

describe("ADR-0019 fatia 2a — o parecer no portal (administração)", () => {
  it("diz o que cada escolha faz", () => {
    expect(frasePortal(false)).toMatch(/só aparece no portal depois que a matéria é deliberada/);
    expect(frasePortal(true)).toMatch(/assim que o jurídico o assina/);
  });

  it("a consulta avulsa nunca vai ao portal", () => {
    expect(AVISO_CONSULTA_AVULSA).toMatch(/nunca vai ao portal/);
  });

  it("erros da configuração: 403 diz que é do administrador", () => {
    expect(mensagemDeErroJuridico(403, "parametros")).toMatch(/administrador da Casa/);
    expect(mensagemDeErroJuridico(403, "salvar-parametros")).toMatch(/administrador da Casa/);
    expect(mensagemDeErroJuridico(400, "salvar-parametros")).toMatch(/Confira os campos/);
  });
});
