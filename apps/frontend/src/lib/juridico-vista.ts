// Lógica pura do PARECER JURÍDICO (ADR-0019, fatia 1): a fila do jurídico, o pedido, o editor e a assinatura. Aqui só se
// decide o TEXTO e as regras de tela; nada de rede. O parecer é OPINATIVO — nunca decide a matéria — e isso é dito na
// UI (AVISO_OPINATIVO). Texto de máquina nunca é parecer: só o que um advogado assina (ADR-0019, contexto 6).

import { formatarData, formatarHora } from "./formatar-data";
import type {
  AssinaturaJuridicaOut,
  ConclusaoJuridica,
  EstadoPedido,
  ParecerJuridicoOut,
  PedidoJuridicoOut,
  QualificacaoJuridica,
} from "./contrato-juridico.gen";

export const AVISO_OPINATIVO =
  "O parecer jurídico é opinativo: orienta a Casa, mas não decide a matéria nem impede que ela siga.";

export const ASSUNTO_PADRAO_DA_MATERIA = "Análise jurídica da matéria";

// ---- estado do pedido ----

const ROTULO_ESTADO_PEDIDO: Record<EstadoPedido, string> = {
  pendente: "Pendente",
  atendido: "Atendido",
  cancelado: "Cancelado",
};

export function rotuloEstadoPedido(estado: string): string {
  return ROTULO_ESTADO_PEDIDO[estado as EstadoPedido] ?? estado;
}

export const ABAS_FILA: { estado: EstadoPedido; rotulo: string }[] = [
  { estado: "pendente", rotulo: "Pendentes" },
  { estado: "atendido", rotulo: "Atendidos" },
  { estado: "cancelado", rotulo: "Cancelados" },
];

export function vazioDaFila(estado: EstadoPedido, podePedir: boolean): string {
  if (estado === "atendido") return "Nenhum pedido atendido ainda.";
  if (estado === "cancelado") return "Nenhum pedido cancelado.";
  return podePedir
    ? "Nenhum pedido pendente. Peça um parecer pela ficha de uma matéria ou abra uma consulta avulsa."
    : "Nenhum pedido pendente para você.";
}

// ---- conclusão e qualificação ----

export const CONCLUSOES: { valor: ConclusaoJuridica; rotulo: string }[] = [
  { valor: "favoravel", rotulo: "Favorável" },
  { valor: "contrario", rotulo: "Contrário" },
  { valor: "com_ressalvas", rotulo: "Com ressalvas" },
  { valor: "orientacao", rotulo: "Orientação (sem posição sobre o mérito jurídico)" },
];

export function rotuloConclusao(c: string | null | undefined): string {
  if (!c) return "Sem conclusão";
  const achada = CONCLUSOES.find((x) => x.valor === c);
  // o rótulo curto na leitura: o parêntese explicativo é só do select
  if (achada?.valor === "orientacao") return "Orientação";
  return achada?.rotulo ?? c;
}

export const QUALIFICACOES: { valor: QualificacaoJuridica; rotulo: string }[] = [
  { valor: "efetivo", rotulo: "Procurador(a) efetivo(a)" },
  { valor: "comissionado", rotulo: "Assessor(a) jurídico(a) comissionado(a)" },
  { valor: "contratado", rotulo: "Advogado(a) contratado(a)" },
];

export function rotuloQualificacao(q: string | null | undefined): string {
  if (!q) return "";
  return QUALIFICACOES.find((x) => x.valor === q)?.rotulo ?? q;
}

// ---- OAB ----

// Espelha a regra do backend: duas letras (UF), número de 1 a 7 dígitos, letra opcional (suplementar), com separador livre.
const PADRAO_OAB = /^([A-Za-z]{2})\s?-?\s?(\d{1,7})([A-Za-z]?)$/;

export function oabValida(texto: string): boolean {
  return PADRAO_OAB.test(texto.trim());
}

/** "ce12345" -> "OAB/CE 12345". Formato que o backend não reconhece sai cru, precedido de "OAB" — nunca inventa. */
export function formatarOab(texto: string | null | undefined): string {
  const t = (texto ?? "").trim();
  if (!t) return "";
  const m = PADRAO_OAB.exec(t);
  return m ? `OAB/${m[1].toUpperCase()} ${m[2]}${m[3].toUpperCase()}` : `OAB ${t}`;
}

// ---- o pedido ----

export function refDoPedido(p: Pick<PedidoJuridicoOut, "proposicao">): string {
  return p.proposicao ? p.proposicao.ref : "Consulta avulsa";
}

export function quando(iso: string): string {
  return `${formatarData(iso)}, ${formatarHora(iso)}`;
}

/** "Pedido por Rita, em nome da Presidência · prazo 15/10/2026" — só o que o pedido de fato tem. */
export function linhaDoPedido(p: Pick<PedidoJuridicoOut, "pedidoPor" | "emNomeDe" | "prazo" | "origem" | "criadoEm">): string {
  const partes: string[] = [];
  if (p.origem === "relator") {
    partes.push(p.pedidoPor ? `Pedido pelo relator ${p.pedidoPor}` : "Pedido pelo relator da comissão");
  } else if (p.origem === "nota_tecnica") {
    partes.push(p.pedidoPor ? `Aberto por ${p.pedidoPor} a partir da nota técnica da IA` : "Aberto a partir da nota técnica da IA");
  } else if (p.pedidoPor) {
    partes.push(p.emNomeDe ? `Pedido por ${p.pedidoPor}, em nome de ${p.emNomeDe}` : `Pedido por ${p.pedidoPor}`);
  } else if (p.emNomeDe) {
    partes.push(`Pedido em nome de ${p.emNomeDe}`);
  } else {
    partes.push("Pedido pela secretaria");
  }
  partes.push(`em ${formatarData(p.criadoEm)}`);
  const base = partes.join(" ");
  return p.prazo ? `${base} · prazo ${formatarData(p.prazo)}` : base;
}

export type SeloDoParecer = { texto: string; tom: "rascunho" | "assinado" | "substituido" };

export function seloDoParecer(p: Pick<ParecerJuridicoOut, "estado" | "substituido">): SeloDoParecer {
  if (p.estado === "rascunho") return { texto: "Rascunho", tom: "rascunho" };
  if (p.substituido) return { texto: "Substituído", tom: "substituido" };
  return { texto: "Assinado", tom: "assinado" };
}

export function numeroDoParecer(p: Pick<ParecerJuridicoOut, "numero" | "ano">): string | null {
  return p.numero != null && p.ano != null ? `Parecer jurídico nº ${p.numero}/${p.ano}` : null;
}

// ---- origem do rascunho (ADR-0019 fatia 2a, Eixo 5) ----

/** Sobre a nota da IA: o texto de máquina nunca é chamado de parecer, e quem assina responde por ele. */
export const AVISO_TEXTO_DE_IA =
  "Texto de IA não é parecer: o que você assina é o que você revisou e assumiu.";

/** O que a ficha e o pedido dizem da origem do parecer. Só para pareceres que partiram da nota técnica da IA; o portal
 *  não mostra origem. Assinado: quem revisou e assinou. Rascunho: o lembrete a quem redige. */
export function linhaDeOrigemDoParecer(
  p: Pick<ParecerJuridicoOut, "estado" | "origemRascunho" | "assinatura">,
): string | null {
  if (p.origemRascunho !== "nota_tecnica") return null;
  if (p.estado === "assinado" && p.assinatura)
    return `Rascunho iniciado a partir de nota técnica da IA, revisado e assinado por ${p.assinatura.nome}.`;
  return "Rascunho iniciado a partir da nota técnica da IA. Revise cada ponto, escolha a conclusão e assine só o que assumir.";
}

/** A linha de estado de um pedido na fila: o que aconteceu com o parecer, sem texto do parecer. */
export function situacaoDoPedido(p: Pick<PedidoJuridicoOut, "estado" | "parecer">): string {
  if (p.estado === "cancelado") return "Pedido cancelado";
  const par = p.parecer;
  if (!par) return p.estado === "atendido" ? "Atendido" : "Aguardando o parecer";
  if (par.estado === "rascunho") {
    if (par.substituiId) return "Novo parecer em rascunho (substitui o anterior)";
    return par.origemRascunho === "nota_tecnica" ? "Rascunho a partir da nota técnica da IA, a revisar" : "Rascunho em andamento";
  }
  const num = numeroDoParecer(par);
  return `${num ?? "Parecer assinado"} · ${rotuloConclusao(par.conclusao)}`;
}

// ---- assinatura ----

export type BlocoDeAssinatura = { nome: string; registro: string; quando: string };

/** O bloco que fecha o parecer: quem assinou, com que título e quando. O registro vem do snapshot do ato. */
export function blocoDeAssinatura(a: AssinaturaJuridicaOut): BlocoDeAssinatura {
  const qual = rotuloQualificacao(a.qualificacao);
  return {
    nome: a.nome,
    registro: [formatarOab(a.oab), qual].filter(Boolean).join(" · "),
    quando: `Assinado em ${quando(a.em)}`,
  };
}

// ---- o editor ----

export type CamposDoParecer = { relatorio: string; fundamentacao: string; conclusao: string };

/** O que ainda falta para assinar (o servidor confere de novo: 400). Vazio = pode assinar. */
export function faltaParaAssinar(c: CamposDoParecer): string[] {
  const falta: string[] = [];
  if (!c.relatorio.trim()) falta.push("o relatório");
  if (!c.fundamentacao.trim()) falta.push("a fundamentação");
  if (!c.conclusao) falta.push("a conclusão");
  return falta;
}

export function frasesFaltaParaAssinar(c: CamposDoParecer): string | null {
  const f = faltaParaAssinar(c);
  if (f.length === 0) return null;
  const lista = f.length === 1 ? f[0] : `${f.slice(0, -1).join(", ")} e ${f[f.length - 1]}`;
  return `Para assinar, falta preencher ${lista}.`;
}

/** Salvar rascunho: basta ter algo escrito — o que é obrigatório só vale na assinatura. */
export function podeSalvarRascunho(c: CamposDoParecer): boolean {
  return c.relatorio.trim().length > 0 || c.fundamentacao.trim().length > 0 || c.conclusao !== "";
}

export function camposDoParecer(p: ParecerJuridicoOut | null): CamposDoParecer {
  return { relatorio: p?.relatorio ?? "", fundamentacao: p?.fundamentacao ?? "", conclusao: p?.conclusao ?? "" };
}

export function camposIguais(a: CamposDoParecer, b: CamposDoParecer): boolean {
  return a.relatorio === b.relatorio && a.fundamentacao === b.fundamentacao && a.conclusao === b.conclusao;
}

/** Corpo do PUT do rascunho (chaves do fio). A conclusão vazia não é enviada. */
export function corpoDoRascunho(c: CamposDoParecer): Record<string, string> {
  return {
    relatorio: c.relatorio,
    fundamentacao: c.fundamentacao,
    ...(c.conclusao ? { conclusao: c.conclusao } : {}),
  };
}

export type ModoDoDetalhe = "escrever" | "ler";

/** O jurídico escreve enquanto o pedido está aberto e o vigente é rascunho (ou não existe); nos demais casos lê. */
export function modoDoDetalhe(p: Pick<PedidoJuridicoOut, "estado" | "parecer">, ehJuridico: boolean): ModoDoDetalhe {
  if (!ehJuridico || p.estado === "cancelado") return "ler";
  if (!p.parecer || p.parecer.estado === "rascunho") return "escrever";
  return "ler";
}

export function podeSubstituir(p: Pick<PedidoJuridicoOut, "estado" | "parecer">, ehJuridico: boolean): boolean {
  return ehJuridico && p.estado === "atendido" && p.parecer?.estado === "assinado";
}

export function podeCancelar(p: Pick<PedidoJuridicoOut, "estado">, ehSecretaria: boolean): boolean {
  return ehSecretaria && p.estado === "pendente";
}

// ---- novo pedido (consulta avulsa e pedido da matéria) ----

export const ASSUNTO_MIN = 5;
export const ASSUNTO_MAX = 300;
export const EM_NOME_DE_MAX = 80;

export type EntradaPedido = { assunto: string; prazo: string; emNomeDe: string };

/** Erros por campo. Em consulta avulsa o assunto é obrigatório; com matéria, é opcional (o servidor põe o padrão). */
export function validarPedido(e: EntradaPedido, comMateria: boolean): Partial<Record<keyof EntradaPedido, string>> {
  const erros: Partial<Record<keyof EntradaPedido, string>> = {};
  const assunto = e.assunto.trim();
  if (!comMateria && assunto.length < ASSUNTO_MIN) erros.assunto = `Descreva o assunto (mínimo de ${ASSUNTO_MIN} caracteres).`;
  if (assunto.length > ASSUNTO_MAX) erros.assunto = `O assunto passa de ${ASSUNTO_MAX} caracteres.`;
  if (comMateria && assunto.length > 0 && assunto.length < ASSUNTO_MIN)
    erros.assunto = `Descreva o assunto (mínimo de ${ASSUNTO_MIN} caracteres) ou deixe em branco.`;
  if (e.prazo && !/^\d{4}-\d{2}-\d{2}$/.test(e.prazo)) erros.prazo = "Use uma data válida.";
  if (e.emNomeDe.trim().length > EM_NOME_DE_MAX) erros.emNomeDe = `Passa de ${EM_NOME_DE_MAX} caracteres.`;
  return erros;
}

/** Corpo do POST do pedido (chaves do fio). Campos vazios não vão. */
export function corpoDoPedido(e: EntradaPedido, proposicaoId?: string): Record<string, string> {
  return {
    ...(proposicaoId ? { "proposicao-id": proposicaoId } : {}),
    ...(e.assunto.trim() ? { assunto: e.assunto.trim() } : {}),
    ...(e.prazo ? { prazo: e.prazo } : {}),
    ...(e.emNomeDe.trim() ? { "em-nome-de": e.emNomeDe.trim() } : {}),
  };
}

// ---- concessão do papel (administração) ----

export function validarConcessaoJuridico(e: { qualificacao: string; oab: string }): Partial<Record<"qualificacao" | "oab", string>> {
  const erros: Partial<Record<"qualificacao" | "oab", string>> = {};
  if (!QUALIFICACOES.some((q) => q.valor === e.qualificacao)) erros.qualificacao = "Escolha a qualificação.";
  if (!e.oab.trim()) erros.oab = "Informe a OAB (ex.: CE 12345).";
  else if (!oabValida(e.oab)) erros.oab = "OAB no formato UF e número, como CE 12345.";
  return erros;
}

// ---- erros ----

export type AcaoJuridica =
  | "listar"
  | "abrir"
  | "pedir"
  | "cancelar"
  | "salvar"
  | "assinar"
  | "substituir"
  | "listar-materia"
  | "usar-nota"
  | "parametros"
  | "salvar-parametros";

const CONFLITO: Record<AcaoJuridica, string> = {
  listar: "Conflito ao ler os pedidos.",
  abrir: "Conflito ao abrir o pedido.",
  pedir: "Já existe um pedido igual em aberto para esta matéria.",
  cancelar: "Este pedido já foi atendido: não dá mais para cancelar.",
  salvar:
    "O pedido foi cancelado ou o parecer já está assinado. Para mudar o texto de um parecer assinado, emita um novo que o substitua.",
  assinar: "Este parecer já foi assinado. Recarregue a página para ver a versão atual.",
  substituir: "Não há parecer assinado vigente para substituir, ou já existe um rascunho em andamento.",
  "listar-materia": "Conflito ao ler os pareceres da matéria.",
  "usar-nota":
    "Esta nota já foi usada ou decidida, ou a matéria já tem um rascunho de parecer em curso. Abra o pedido na fila e continue de onde parou.",
  parametros: "Conflito ao ler a configuração do parecer no portal.",
  "salvar-parametros": "Conflito ao salvar a configuração do parecer no portal.",
};

const PROIBIDO: Partial<Record<AcaoJuridica, string>> = {
  assinar:
    "Seu acesso não tem a qualificação e a OAB registradas, e sem elas o parecer não pode ser assinado. Peça ao administrador da Casa para atualizar seu acesso.",
  salvar: "Só o jurídico da Casa escreve o parecer.",
  substituir: "Só o jurídico da Casa emite parecer.",
  cancelar: "Só a secretaria cancela um pedido.",
  pedir: "Só a secretaria pede parecer.",
  "usar-nota": "Só o jurídico da Casa usa a nota técnica como rascunho de parecer.",
  parametros: "Só o administrador da Casa vê e altera esta configuração.",
  "salvar-parametros": "Só o administrador da Casa vê e altera esta configuração.",
};

/** A frase da tela para uma resposta que não foi ok. `erroDoServidor` só entra no 400 (corpo inválido), onde ele é o
 *  que explica ao usuário o que corrigir. */
export function mensagemDeErroJuridico(status: number, acao: AcaoJuridica, erroDoServidor?: string): string {
  if (status === 0) return "Falha de rede. Nada foi gravado; tente de novo em instantes.";
  if (status === 401) return "Sua sessão expirou. Entre de novo.";
  if (status === 403)
    return PROIBIDO[acao] ?? "Seu acesso não permite ver ou fazer isso. O jurídico e a secretaria usam esta área.";
  if (status === 404)
    return acao === "usar-nota"
      ? "Não encontramos esta nota técnica — ela pode não existir nesta Casa."
      : "Não encontramos este pedido — ele pode não existir nesta Casa.";
  if (status === 409) return CONFLITO[acao];
  if (status === 400) {
    if (acao === "assinar") return erroDoServidor ?? "Falta preencher o relatório, a fundamentação ou a conclusão.";
    return erroDoServidor ?? "Confira os campos e tente de novo.";
  }
  return "Não foi possível concluir agora. Tente de novo em instantes.";
}

// ---- parecer no portal (administração, Eixo 4) ----

export const ROTULO_PORTAL = {
  titulo: "Parecer jurídico no portal",
  depois: "Só depois da deliberação da matéria (padrão)",
  ao_assinar: "Assim que o jurídico assinar",
};

/** A frase que diz ao administrador o que o parâmetro faz hoje. */
export function frasePortal(publicarAoAssinar: boolean): string {
  return publicarAoAssinar
    ? "O parecer assinado, o vigente, aparece no portal assim que o jurídico o assina, mesmo com a matéria ainda em tramitação."
    : "O parecer assinado, o vigente, só aparece no portal depois que a matéria é deliberada (votada ou arquivada), como a LAI permite.";
}

export const AVISO_CONSULTA_AVULSA = "A consulta avulsa nunca vai ao portal: responde-se por e-SIC.";
