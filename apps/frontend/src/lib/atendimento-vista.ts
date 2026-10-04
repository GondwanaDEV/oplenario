// Lógica pura do BALCÃO DE ATENDIMENTO AO CIDADÃO (features 6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD): a secretaria vê, numa
// fila por espécie, o que o cidadão protocolou no portal, pelo prazo que vence primeiro, e responde. Aqui só se decide
// o TEXTO: os rótulos, o selo do prazo, a linha de cada item, o histórico e os erros. A regra (o que está aberto, o
// prazo que vale, o que cabe fazer) vem pronta do servidor — a tela não deduz nada.

import { formatarData, formatarDataSimples, formatarHora } from "./formatar-data";
import { MENSAGEM_DE_REDE_NO_ANEXO, TIPOS_ACEITOS_EM_TEXTO } from "./anexos-do-atendimento";
import type { EventoOut } from "./contrato-atendimento.gen";

export type Especie = "esic" | "ouvidoria" | "lgpd";
export type Situacao = "abertos" | "respondidos" | "todos";

export const ESPECIES: { especie: Especie; rotulo: string; titulo: string; lei: string }[] = [
  {
    especie: "esic",
    rotulo: "e-SIC",
    titulo: "Pedidos de acesso à informação",
    lei: "Lei de Acesso à Informação: 20 dias para responder, prorrogáveis uma vez por mais 10, com justificativa ao requerente.",
  },
  {
    especie: "ouvidoria",
    rotulo: "Ouvidoria",
    titulo: "Manifestações de ouvidoria",
    lei: "Lei 13.460/2017: 30 dias para responder, prorrogáveis uma vez por igual período, com justificativa.",
  },
  {
    especie: "lgpd",
    rotulo: "LGPD",
    titulo: "Pedidos do titular de dados",
    lei: "LGPD, art. 18: a pessoa pede acesso, correção ou eliminação dos próprios dados. Quem responde é o encarregado de dados da Casa.",
  },
];

export function especieValida(s: string | null | undefined): s is Especie {
  return s === "esic" || s === "ouvidoria" || s === "lgpd";
}

export const SITUACOES: { situacao: Situacao; rotulo: string }[] = [
  { situacao: "abertos", rotulo: "Em aberto" },
  { situacao: "respondidos", rotulo: "Encerrados" },
  { situacao: "todos", rotulo: "Todos" },
];

/** Lei 13.460/2017, art. 10, §7º: a identificação do manifestante é informação pessoal de acesso restrito. */
export const AVISO_IDENTIDADE_OUVIDORIA =
  "A identidade de quem se manifesta é protegida por lei (Lei 13.460/2017, art. 10, §7º). Aqui aparece só se a manifestação é identificada ou anônima.";

const TIPOS_MANIFESTACAO: Record<string, string> = {
  reclamacao: "Reclamação",
  denuncia: "Denúncia",
  sugestao: "Sugestão",
  elogio: "Elogio",
  solicitacao: "Solicitação",
};

const DIREITOS_LGPD: Record<string, string> = {
  acessar: "Acesso aos dados",
  corrigir: "Correção de dados",
  eliminar: "Eliminação de dados",
  com_quem_compartilhado: "Com quem os dados foram compartilhados",
  revogar_consentimento: "Revogação do consentimento",
};

export function rotuloTipoManifestacao(tipo: string): string {
  return TIPOS_MANIFESTACAO[tipo] ?? tipo;
}

export function rotuloDireitoLgpd(tipo: string): string {
  return DIREITOS_LGPD[tipo] ?? tipo;
}

const ESTADOS: Record<string, string> = {
  protocolado: "Recebido",
  protocolada: "Recebida",
  em_analise: "Em análise",
  respondido: "Respondido",
  respondida: "Respondida",
  indeferido: "Indeferido",
  indeferida: "Indeferida",
  arquivada: "Arquivada",
};

export function rotuloEstado(estado: string): string {
  return ESTADOS[estado] ?? estado;
}

export type TomPrazo = "vencido" | "hoje" | "perto" | "ok" | "encerrado";

/** O selo do prazo de um item: vencido / vence hoje / dias restantes; encerrado não tem prazo correndo. */
export function seloDoPrazo(i: { aberto: boolean; diasRestantes: number | null }): { tom: TomPrazo; texto: string } {
  if (!i.aberto || i.diasRestantes === null) return { tom: "encerrado", texto: "Encerrado" };
  const d = i.diasRestantes;
  if (d < 0) return { tom: "vencido", texto: d === -1 ? "Venceu ontem" : `Venceu há ${-d} dias` };
  if (d === 0) return { tom: "hoje", texto: "Vence hoje" };
  if (d === 1) return { tom: "perto", texto: "Vence amanhã" };
  return { tom: d <= 5 ? "perto" : "ok", texto: `Faltam ${d} dias` };
}

/** "Prazo: 15/07/2026 (prorrogado)". */
export function linhaDoPrazo(i: { prazoVigente: string | null; prorrogado: boolean }): string | null {
  if (!i.prazoVigente) return null;
  return `Prazo: ${formatarDataSimples(i.prazoVigente)}${i.prorrogado ? " (prorrogado)" : ""}`;
}

export function recebidoEm(iso: string): string {
  return `Recebido em ${formatarData(iso)}`;
}

/** O título de um item na fila e no detalhe. */
export function tituloDoItem(
  especie: Especie,
  i: { assunto?: string; tipo?: string },
): string {
  if (especie === "esic") return i.assunto ?? "";
  if (especie === "ouvidoria") return `${rotuloTipoManifestacao(i.tipo ?? "")}: ${i.assunto ?? ""}`;
  return rotuloDireitoLgpd(i.tipo ?? "");
}

const VAZIO: Record<Especie, Record<Situacao, string>> = {
  esic: {
    abertos: "Nenhum pedido de informação esperando resposta. Tudo em dia.",
    respondidos: "Nenhum pedido de informação encerrado ainda.",
    todos: "Nenhum pedido de informação recebido ainda.",
  },
  ouvidoria: {
    abertos: "Nenhuma manifestação esperando resposta. Tudo em dia.",
    respondidos: "Nenhuma manifestação encerrada ainda.",
    todos: "Nenhuma manifestação recebida ainda.",
  },
  lgpd: {
    abertos: "Nenhum pedido do titular esperando resposta. Tudo em dia.",
    respondidos: "Nenhum pedido do titular encerrado ainda.",
    todos: "Nenhum pedido do titular recebido ainda.",
  },
};

export function vazioDaFila(especie: Especie, situacao: Situacao): string {
  return VAZIO[especie][situacao];
}

function quando(iso: string): string {
  return `${formatarData(iso)}, ${formatarHora(iso)}`;
}

/** O título de uma entrada do histórico. */
export function tituloDoEvento(e: EventoOut): string {
  switch (e.tipo) {
    case "resposta":
      return "Resposta da Casa";
    case "indeferimento":
      return "Indeferimento (fundamentação da Casa)";
    case "recurso":
      return `Recurso do requerente${e.protocolo ? ` (${e.protocolo})` : ""}`;
    case "decisao-recurso":
      return "Decisão do recurso";
    case "prorrogacao":
      return e.deData && e.paraData
        ? `Prazo prorrogado de ${formatarDataSimples(e.deData)} para ${formatarDataSimples(e.paraData)}`
        : "Prazo prorrogado";
    case "arquivamento":
      return "Arquivada sem resposta de mérito";
  }
}

export function linhaDoEvento(e: EventoOut): string {
  return `${quando(e.em)}${e.por ? ` · ${e.por}` : ""}`;
}

export type AcaoAtendimento =
  | "listar"
  | "abrir"
  | "responder"
  | "indeferir"
  | "decidir-recurso"
  | "prorrogar"
  | "arquivar"
  | "anexar"
  | "baixar-anexo"
  | "retirar-anexo"
  | "encarregado"
  | "salvar-encarregado";

const CONFLITO: Record<AcaoAtendimento, string> = {
  listar: "",
  abrir: "",
  responder: "Este protocolo já foi respondido ou encerrado por outra pessoa. Recarregue para ver o que mudou.",
  indeferir: "Este protocolo já foi respondido ou indeferido por outra pessoa. Recarregue para ver o que mudou.",
  "decidir-recurso": "Este recurso já foi decidido. Recarregue para ver a decisão.",
  prorrogar: "O prazo já foi prorrogado uma vez, ou não está mais correndo. A prorrogação só cabe uma vez, antes de vencer.",
  arquivar: "Esta manifestação já foi respondida ou arquivada. Recarregue para ver o que mudou.",
  anexar: "A Casa não pode anexar agora: o protocolo ainda não tem resposta, passaram os 10 minutos depois dela ou já são 5 anexos.",
  "baixar-anexo": "",
  "retirar-anexo": "",
  encarregado: "",
  "salvar-encarregado": "",
};

/** `erroDoServidor` só vale ao ANEXAR: ali o servidor explica o que a pessoa pode corrigir (qual regra, em português). */
export function mensagemDeErroAtendimento(status: number, acao: AcaoAtendimento, erroDoServidor?: string): string {
  if (status === 0) {
    if (acao === "anexar") return MENSAGEM_DE_REDE_NO_ANEXO;
    if (acao === "retirar-anexo") return "Falha de rede: não deu para confirmar a retirada. Tente de novo; retirar duas vezes tem o mesmo efeito de uma.";
    return "Falha de rede. Nada foi gravado; tente de novo em instantes.";
  }
  if (status === 401) return "Sua sessão expirou. Entre de novo.";
  if (acao === "retirar-anexo") {
    if (status === 403) return "Só a secretaria retira anexos.";
    if (status === 404) return "Não encontramos este anexo — ele pode não existir neste protocolo.";
    if (status === 400) return "Escreva o motivo da retirada.";
    if (status === 423) return "O sistema desta Casa está com acesso restrito. Fale com a administração.";
    return "Não foi possível retirar agora. Tente de novo em instantes.";
  }
  if (acao === "anexar" || acao === "baixar-anexo") return mensagemDeErroDeAnexo(status, acao, erroDoServidor);
  if (status === 403) return "Esta área é da secretaria. Seu acesso não permite ver ou responder estes protocolos.";
  if (status === 404) return "Não encontramos este protocolo — ele pode não existir nesta Casa.";
  if (status === 409) return CONFLITO[acao] || "O protocolo mudou enquanto você trabalhava. Recarregue a página.";
  if (status === 423) return "O sistema desta Casa está com acesso restrito. Fale com a administração.";
  if (status === 400) return "Confira o texto e tente de novo.";
  return "Não foi possível concluir agora. Tente de novo em instantes.";
}

/** O aviso do formulário de prorrogar. A LAI (art. 11 §2º) exige que a justificativa seja dada a conhecer ao requerente:
 *  ela aparece no protocolo dele em "Meus protocolos". Numa manifestação ANÔNIMA não há dono persistido, então não há a
 *  quem mostrá-la — o aviso diz isso em vez de prometer o que não acontece. */
export function avisoDaProrrogacao(especie: "esic" | "ouvidoria", identificacao?: "identificada" | "anonima"): string {
  const regra = "A prorrogação só pode ser feita uma vez e exige justificativa.";
  if (especie === "esic")
    return `${regra} A justificativa é mostrada ao requerente, no protocolo dele em "Meus protocolos": escreva pensando em quem vai ler.`;
  if (identificacao === "anonima")
    return `${regra} Esta manifestação é anônima: a justificativa fica só no registro da Casa, porque não há a quem mostrá-la.`;
  return `${regra} A justificativa é mostrada ao manifestante, no protocolo dele em "Meus protocolos": escreva pensando em quem vai ler.`;
}

function mensagemDeErroDeAnexo(status: number, acao: "anexar" | "baixar-anexo", erroDoServidor?: string): string {
  if (acao === "baixar-anexo") {
    if (status === 403) return "Só a secretaria baixa os anexos por aqui.";
    if (status === 404) return "O anexo não foi encontrado.";
    return "Não foi possível baixar agora. Tente de novo em instantes.";
  }
  if (status === 413) return "O arquivo passa de 10 MB, o limite por anexo.";
  if (status === 415) return erroDoServidor || `Tipo de arquivo não aceito. Aceitamos ${TIPOS_ACEITOS_EM_TEXTO}.`;
  if (status === 409) return erroDoServidor || CONFLITO.anexar;
  if (status === 403) return "Só a secretaria anexa arquivos à resposta.";
  if (status === 404) return "Não encontramos este protocolo para receber o anexo — ele pode não existir nesta Casa.";
  if (status === 423) return "O sistema desta Casa está com acesso restrito. Fale com a administração.";
  if (status === 400) return "O arquivo está vazio ou veio malformado. Escolha-o de novo.";
  return "Não foi possível anexar agora. Tente de novo em instantes.";
}

export const TETO_RESPOSTA = 50000;
export const TETO_JUSTIFICATIVA = 5000;

/** O que falta para enviar um texto obrigatório. null = pode enviar. */
export function faltaNoTexto(texto: string, teto: number, oQue: string): string | null {
  const t = texto.trim();
  if (!t) return `Escreva ${oQue}.`;
  if (t.length > teto) return `O texto tem até ${teto.toLocaleString("pt-BR")} caracteres.`;
  return null;
}

/** O recibo que fica na tela depois de uma ação. */
export function textoDoRecibo(
  acao: "responder" | "indeferir" | "decidir-recurso" | "prorrogar" | "arquivar",
  quandoIso: string,
  protocolo: string,
): string {
  if (acao === "prorrogar")
    return `Prazo do ${protocolo} prorrogado até ${formatarDataSimples(quandoIso)}. A nova data já aparece para quem acompanha o protocolo.`;
  const q = quando(quandoIso);
  if (acao === "arquivar") return `${protocolo} arquivado em ${q}. O motivo fica registrado no protocolo.`;
  if (acao === "indeferir")
    return `${protocolo} indeferido em ${q}. A fundamentação fica registrada no protocolo e a pessoa a lê em "Meus protocolos".`;
  if (acao === "decidir-recurso") return `Decisão do recurso do ${protocolo} registrada em ${q}.`;
  return `Resposta ao ${protocolo} registrada em ${q}.`;
}

/** De um prazo do painel de pendências (objeto_tipo + id) para o lugar do balcão onde se responde. O recurso do e-SIC
 *  não tem detalhe próprio (o id é do recurso, não do pedido): leva à fila do e-SIC, onde ele aparece no pedido. */
export function hrefDoPrazo(objetoTipo: string, objetoId: string): string | null {
  const id = encodeURIComponent(objetoId);
  switch (objetoTipo) {
    case "pedido_esic":
      return `/atendimento/esic/${id}`;
    case "recurso_esic":
      return "/atendimento?aba=esic";
    case "manifestacao_ouvidoria":
      return `/atendimento/ouvidoria/${id}`;
    case "solicitacao_titular":
      return `/atendimento/lgpd/${id}`;
    default:
      return null;
  }
}

/** Encarregado de dados (LGPD art. 41): o que falta para salvar. null = pode. */
export function faltaNoEncarregado(e: { nome: string; rotulo: string; email: string }): string | null {
  if (!e.nome.trim()) return "Escreva o nome do encarregado.";
  if (!e.rotulo.trim()) return "Escreva como o cargo aparece no portal (ex.: Encarregada de Dados).";
  if (!e.email.trim()) return "Escreva o e-mail de contato.";
  if (!/^[^\s@]+@[^\s@]+$/.test(e.email.trim())) return "O e-mail parece incompleto.";
  return null;
}
