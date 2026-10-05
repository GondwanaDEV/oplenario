import { describe, expect, it } from "vitest";
import {
  avisoDaProrrogacao,
  especieValida,
  faltaNoEncarregado,
  faltaNoTexto,
  tituloQueRecebeOFoco,
  hrefDoPrazo,
  linhaDoPrazo,
  mensagemDeErroAtendimento,
  rotuloDireitoLgpd,
  rotuloEstado,
  rotuloTipoManifestacao,
  seloDoPrazo,
  avisoDoComplemento,
  textoDoRecibo,
  tituloDoEvento,
  tituloDoItem,
  vazioDaFila,
} from "./atendimento-vista";

describe("seloDoPrazo", () => {
  it("vencido, vence hoje, amanhã e dias restantes", () => {
    expect(seloDoPrazo({ aberto: true, diasRestantes: -3 })).toEqual({ tom: "vencido", texto: "Venceu há 3 dias" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: -1 })).toEqual({ tom: "vencido", texto: "Venceu ontem" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 0 })).toEqual({ tom: "hoje", texto: "Vence hoje" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 1 })).toEqual({ tom: "perto", texto: "Vence amanhã" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 4 })).toEqual({ tom: "perto", texto: "Faltam 4 dias" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 12 })).toEqual({ tom: "ok", texto: "Faltam 12 dias" });
  });

  it("encerrado não tem prazo correndo", () => {
    expect(seloDoPrazo({ aberto: false, diasRestantes: null })).toEqual({ tom: "encerrado", texto: "Encerrado" });
    expect(seloDoPrazo({ aberto: false, diasRestantes: -5 }).tom).toBe("encerrado");
  });
});

describe("textos", () => {
  it("o prazo em dd/mm/aaaa, sem mexer no dia (date-only, sem fuso)", () => {
    expect(linhaDoPrazo({ prazoVigente: "2026-07-15", prorrogado: false })).toBe("Prazo: 15/07/2026");
    expect(linhaDoPrazo({ prazoVigente: "2026-07-25", prorrogado: true })).toBe("Prazo: 25/07/2026 (prorrogado)");
    expect(linhaDoPrazo({ prazoVigente: null, prorrogado: false })).toBeNull();
  });

  it("o título por espécie", () => {
    expect(tituloDoItem("esic", { assunto: "Diárias" })).toBe("Diárias");
    expect(tituloDoItem("ouvidoria", { tipo: "reclamacao", assunto: "Fila" })).toBe("Reclamação: Fila");
    expect(tituloDoItem("lgpd", { tipo: "com_quem_compartilhado" })).toBe("Com quem os dados foram compartilhados");
  });

  it("o histórico em palavras", () => {
    expect(tituloDoEvento({ tipo: "prorrogacao", em: "2026-07-03T15:00:00Z", texto: "x", por: null, deData: "2026-07-15", paraData: "2026-07-25" }))
      .toBe("Prazo prorrogado de 15/07/2026 para 25/07/2026");
    expect(tituloDoEvento({ tipo: "recurso", em: "2026-07-03T15:00:00Z", texto: "x", por: null, protocolo: "REC-2026-000001" }))
      .toBe("Recurso do requerente (REC-2026-000001)");
    expect(tituloDoEvento({ tipo: "arquivamento", em: "2026-07-03T15:00:00Z", texto: "x", por: "Ana" })).toBe("Arquivada sem resposta de mérito");
  });

  it("o indeferimento tem título próprio, distinto da resposta, e o estado sai em palavras (nunca o enum cru)", () => {
    const e = { em: "2026-07-03T15:00:00Z", texto: "Dado pessoal de terceiro.", por: "Joana" } as const;
    expect(tituloDoEvento({ ...e, tipo: "indeferimento" })).toBe("Indeferimento (fundamentação da Casa)");
    expect(tituloDoEvento({ ...e, tipo: "indeferimento" })).not.toBe(tituloDoEvento({ ...e, tipo: "resposta" }));
    expect(rotuloEstado("indeferido")).toBe("Indeferido");
    expect(rotuloEstado("indeferida")).toBe("Indeferida");
  });

  it("estado, tipo de manifestação e direito LGPD desconhecidos saem em palavras, nunca a chave do backend", () => {
    expect(rotuloEstado("aguardando_orgao")).toBe("Aguardando orgao");
    expect(rotuloEstado("")).toBe("Situação não informada");
    expect(rotuloTipoManifestacao("pedido_especial")).toBe("Pedido especial");
    expect(rotuloTipoManifestacao("")).toBe("Manifestação");
    expect(rotuloDireitoLgpd("portabilidade_dos_dados")).toBe("Portabilidade dos dados");
    expect(rotuloDireitoLgpd("")).toBe("Pedido do titular");
    // os conhecidos seguem como estão
    expect(rotuloEstado("em_analise")).toBe("Em análise");
    expect(rotuloTipoManifestacao("denuncia")).toBe("Denúncia");
    expect(rotuloDireitoLgpd("com_quem_compartilhado")).toBe("Com quem os dados foram compartilhados");
  });

  it("o título de item sem tipo conhecido não vira ': assunto' nem traz a chave", () => {
    expect(tituloDoItem("ouvidoria", { tipo: "pedido_especial", assunto: "Rua escura" })).toBe("Pedido especial: Rua escura");
    expect(tituloDoItem("lgpd", { tipo: "portabilidade_dos_dados" })).toBe("Portabilidade dos dados");
  });

  it("o vazio de cada fila concorda em gênero", () => {
    expect(vazioDaFila("ouvidoria", "abertos")).toBe("Nenhuma manifestação esperando resposta. Tudo em dia.");
    expect(vazioDaFila("esic", "respondidos")).toBe("Nenhum pedido de informação encerrado ainda.");
  });

  it("o recibo diz o protocolo e a data", () => {
    expect(textoDoRecibo("prorrogar", "2026-07-25", "ESIC-2026-000001")).toBe(
      "Prazo do ESIC-2026-000001 prorrogado até 25/07/2026. A nova data já aparece para quem acompanha o protocolo.",
    );
    expect(textoDoRecibo("responder", "2026-07-03T15:00:00Z", "ESIC-2026-000001")).toMatch(/^Resposta ao ESIC-2026-000001 registrada em \d{2}\/\d{2}\/2026/);
    expect(textoDoRecibo("indeferir", "2026-07-03T15:00:00Z", "ESIC-2026-000001")).toMatch(
      /^ESIC-2026-000001 indeferido em \d{2}\/\d{2}\/2026, .*A fundamentação fica registrada no protocolo/,
    );
  });
});

describe("os erros de anexar (a resposta do servidor explica o que a pessoa pode corrigir)", () => {
  it("413, 415 e os 409 de anexar usam a frase do servidor; sem ela, uma frase honesta", () => {
    expect(mensagemDeErroAtendimento(413, "anexar")).toBe("O arquivo passa de 10 MB, o limite por anexo.");
    // F10: falha de rede ao anexar: nao deu para confirmar, e reenviar e' seguro (o servidor nao duplica o mesmo arquivo)
    expect(mensagemDeErroAtendimento(0, "anexar")).toMatch(/não deu para confirmar/);
    expect(mensagemDeErroAtendimento(0, "anexar")).toMatch(/seguro/);
    expect(mensagemDeErroAtendimento(0, "anexar")).not.toMatch(/Nada foi gravado/);
    expect(mensagemDeErroAtendimento(0, "responder")).toMatch(/Nada foi gravado/);   // nos atos de texto, segue valendo
    expect(mensagemDeErroAtendimento(415, "anexar", "Tipo de arquivo não aceito. Aceitamos PDF.")).toBe("Tipo de arquivo não aceito. Aceitamos PDF.");
    expect(mensagemDeErroAtendimento(415, "anexar")).toMatch(/Tipo de arquivo não aceito\. Aceitamos PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS/);
    expect(mensagemDeErroAtendimento(409, "anexar", "Os anexos vão junto com a resposta: os 10 minutos depois do último ato já passaram."))
      .toMatch(/10 minutos/);
    expect(mensagemDeErroAtendimento(409, "anexar")).toMatch(/não pode anexar agora/);
    expect(mensagemDeErroAtendimento(403, "anexar")).toMatch(/só a secretaria/i);
    expect(mensagemDeErroAtendimento(404, "anexar")).toMatch(/não encontramos/i);
    expect(mensagemDeErroAtendimento(400, "anexar")).toMatch(/vazio ou veio malformado/);
    // F10 (contrato mudou, de proposito): a falha de rede ao anexar nao diz mais "Falha de rede. Nada foi gravado" (o servidor pode
    // ter recebido o arquivo): diz que nao deu para confirmar e que reenviar e' seguro
    expect(mensagemDeErroAtendimento(0, "anexar")).toMatch(/Sem conexão: não deu para confirmar/);
  });

  it("a frase do servidor só vale em anexar: nas outras ações o 409 segue o texto de sempre", () => {
    expect(mensagemDeErroAtendimento(409, "responder", "qualquer coisa do servidor")).toMatch(/já foi respondido ou encerrado/);
  });

  it("baixar: 404 e 403 em palavras", () => {
    expect(mensagemDeErroAtendimento(404, "baixar-anexo")).toMatch(/anexo não foi encontrado/i);
    expect(mensagemDeErroAtendimento(403, "baixar-anexo")).toMatch(/secretaria/i);
  });
});

describe("o aviso do formulário de prorrogar", () => {
  it("diz que a justificativa é mostrada a quem pediu — e é honesto quando não há a quem mostrar (ouvidoria anônima)", () => {
    expect(avisoDaProrrogacao("esic")).toMatch(/exige justificativa\./);
    expect(avisoDaProrrogacao("esic")).toMatch(/A justificativa é mostrada ao requerente/);
    expect(avisoDaProrrogacao("ouvidoria", "identificada")).toMatch(/A justificativa é mostrada ao manifestante/);
    const anonima = avisoDaProrrogacao("ouvidoria", "anonima");
    expect(anonima).toMatch(/manifestação é anônima/);
    expect(anonima).toMatch(/só no registro da Casa/);
    expect(anonima).not.toMatch(/é mostrada ao/);
    // a regra de sempre continua na frente
    for (const a of [avisoDaProrrogacao("esic"), avisoDaProrrogacao("ouvidoria", "identificada"), anonima])
      expect(a.startsWith("A prorrogação só pode ser feita uma vez e exige justificativa.")).toBe(true);
  });
});

describe("erros e validação", () => {
  it("cada status vira uma frase; 409 depende da ação", () => {
    expect(mensagemDeErroAtendimento(403, "listar")).toMatch(/secretaria/);
    expect(mensagemDeErroAtendimento(409, "prorrogar")).toMatch(/só cabe uma vez/);
    expect(mensagemDeErroAtendimento(409, "responder")).toMatch(/já foi respondido/);
    expect(mensagemDeErroAtendimento(409, "indeferir")).toMatch(/já foi respondido ou indeferido/);
    expect(mensagemDeErroAtendimento(400, "indeferir")).toMatch(/Confira o texto/);
    expect(mensagemDeErroAtendimento(0, "responder")).toMatch(/Falha de rede/);
  });

  it("texto obrigatório e teto", () => {
    expect(faltaNoTexto("   ", 10, "a resposta")).toBe("Escreva a resposta.");
    expect(faltaNoTexto("x".repeat(11), 10, "a resposta")).toMatch(/até 10 caracteres/);
    expect(faltaNoTexto("ok", 10, "a resposta")).toBeNull();
  });

  it("o encarregado precisa de nome, cargo e e-mail", () => {
    expect(faltaNoEncarregado({ nome: "", rotulo: "x", email: "a@b" })).toMatch(/nome/);
    expect(faltaNoEncarregado({ nome: "Ana", rotulo: "x", email: "ana" })).toMatch(/incompleto/);
    expect(faltaNoEncarregado({ nome: "Ana", rotulo: "Encarregada", email: "ana@camara.leg.br" })).toBeNull();
  });
});

describe("navegação", () => {
  it("só as três filas existem", () => {
    expect(especieValida("esic")).toBe(true);
    expect(especieValida("lgpd")).toBe(true);
    expect(especieValida("moderacao")).toBe(false);
    expect(especieValida(null)).toBe(false);
  });

  it("o prazo do painel de pendências leva ao balcão", () => {
    expect(hrefDoPrazo("pedido_esic", "p1")).toBe("/atendimento/esic/p1");
    expect(hrefDoPrazo("manifestacao_ouvidoria", "m1")).toBe("/atendimento/ouvidoria/m1");
    expect(hrefDoPrazo("solicitacao_titular", "s1")).toBe("/atendimento/lgpd/s1");
    expect(hrefDoPrazo("recurso_esic", "r1")).toBe("/atendimento?aba=esic");
    expect(hrefDoPrazo("obrigacao", "o1")).toBeNull();
  });

  it("foco do indeferimento: o pedido só vale no commit do passo a que ele se refere", () => {
    expect(tituloQueRecebeOFoco("confirmar", true)).toBe("confirmar");
    expect(tituloQueRecebeOFoco("editor", false)).toBe("editor");
    // efeito atrasado do passo anterior: o pedido é do passo que ainda vai aparecer, e fica guardado
    expect(tituloQueRecebeOFoco("confirmar", false)).toBeNull();
    expect(tituloQueRecebeOFoco("editor", true)).toBeNull();
    expect(tituloQueRecebeOFoco(null, true)).toBeNull();
    expect(tituloQueRecebeOFoco(null, false)).toBeNull();
  });

  it("o complemento da resposta: título no histórico, recibo e erros em palavras", () => {
    expect(tituloDoEvento({ tipo: "complemento", em: "2026-07-03T15:00:00Z", texto: "x", por: "Ana" })).toBe("Complemento da resposta");
    expect(textoDoRecibo("complementar", "2026-07-03T15:00:00Z", "ESIC-2026-000007")).toMatch(
      /^Complemento à resposta do ESIC-2026-000007 registrado em .*histórico/,
    );
    expect(mensagemDeErroAtendimento(409, "complementar")).toMatch(/Responda o pedido antes de complementar/);
    expect(mensagemDeErroAtendimento(400, "complementar")).toBe("Confira o texto e tente de novo.");
    expect(mensagemDeErroAtendimento(403, "complementar")).toMatch(/secretaria/);
  });

  it("o aviso do complemento: imutável e sem mexer em prazo; a manifestação anônima não tem a quem mostrar", () => {
    expect(avisoDoComplemento("identificada")).toMatch(/definitivo.*não muda o estado nem o prazo/);
    expect(avisoDoComplemento("identificada")).toMatch(/Meus protocolos/);
    expect(avisoDoComplemento(undefined)).toMatch(/Meus protocolos/);
    const anonima = avisoDoComplemento("anonima");
    expect(anonima).toMatch(/anônima: o complemento fica só no registro da Casa/);
    expect(anonima).not.toMatch(/Meus protocolos/);
  });
});
