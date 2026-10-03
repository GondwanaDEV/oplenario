// ADR-0021 Parte B — o contrato de fio do JULGAMENTO DAS CONTAS (módulo `legislativo`), como o FE o vê DEPOIS de
// `camelizarChaves` (o backend fala kebab-case; ver boundary.ts). Escrito à mão, não gerado: o módulo nasce em paralelo
// com este FE, a partir do MESMO contrato (a seção "O contrato" da ADR), e os detalhes de forma que o backend real
// ajustar se acertam AQUI — em `doFio`, aplicado logo depois de `camelizarChaves` —, um arquivo só, para não espalhar o
// acerto pelos hooks e telas. Quando o backend publicar o `contrato-contas.gen.ts` (o codegen dos outros módulos), estes
// tipos dão lugar a ele.
//
// O que é contrato e o que é suposição deste lado:
//   - rotas, métodos, campos e status vêm da tabela "Rotas — Parte B" da ADR;
//   - o ESTADO da prestação é derivado no backend (lógica pura, não coluna) e chega pronto; o FE só o põe em palavras;
//   - `motivo-nao-pautavel` chega como FRASE ("o prazo de defesa vai até 20/10/2026", o mesmo texto do 409 da pauta).
//     Se chegar um código (`prazo_de_defesa`…), `motivoEmPalavras` (contas-vista.ts) traduz — nunca se mostra cru;
//   - as RESPOSTAS das escritas (notificação, documento, PATCH, registro) só são lidas no registro, para levar à ficha;
//     nas outras a tela recarrega a prestação depois de cada escrita, e a forma exata do corpo não importa.

/** `governo_prefeito` = contas de governo do Prefeito (PDL + votação); `gestao_camara` = contas da Mesa (só acompanha). */
export type TipoPrestacao = "governo_prefeito" | "gestao_camara";

export type ParecerPrevio = "favoravel" | "favoravel_com_ressalvas" | "desfavoravel";

/** Derivado no backend: governo → aguardando_notificacao → prazo_de_defesa → pronta_para_pauta → julgada; Mesa →
 *  acompanhamento. */
export type EstadoPrestacao = "aguardando_notificacao" | "prazo_de_defesa" | "pronta_para_pauta" | "julgada" | "acompanhamento";

export type ResultadoJulgamento = "parecer_mantido" | "parecer_rejeitado";

export type TipoDocumentoContas = "parecer_previo" | "relatorio_tce" | "notificacao" | "defesa" | "decisao_tce" | "outro";

// ---- GET /contas ----

export type PrestacaoResumo = {
  id: string;
  tipo: TipoPrestacao;
  exercicio: number;
  responsavel: string;
  parecerPrevio?: ParecerPrevio | null;
  estado: EstadoPrestacao;
  resultado?: ResultadoJulgamento | null;
  prazoJulgamentoAte?: string | null;
};

export type ListaPrestacoesOut = { prestacoes: PrestacaoResumo[] };

// ---- GET /contas/:id (e a resposta de POST /contas e de GET /contas-da-proposicao/:id) ----

export type DocumentoContas = { id: string; tipo: TipoDocumentoContas; nome: string; tamanhoBytes: number; criadoEm: string };

/** "Como a Câmara decide": a base é o número de MEMBROS da Câmara, não os presentes (CF art. 31 §2). */
export type QuorumContas = { baseMembros: number; necessariosParaRejeitar: number };

export type VotacaoContas = { id: string; sim: number; nao: number; abstencao: number };

export type PrestacaoOut = PrestacaoResumo & {
  recebidaEm: string;
  processoTce?: string | null;
  /** O Projeto de Decreto Legislativo (só governo). `rotulo` = "PDL 3/2026". */
  proposicao?: { id: string; rotulo: string; estado: string } | null;
  notificadoEm?: string | null;
  notificacaoMeio?: string | null;
  prazoDefesaAte?: string | null;
  defesaJuntadaEm?: string | null;
  julgadaEm?: string | null;
  situacaoTce?: string | null;
  quorum?: QuorumContas | null;
  votacao?: VotacaoContas | null;
  fraseResultado?: string | null;
  pautavel: boolean;
  motivoNaoPautavel?: string | null;
  documentos: DocumentoContas[];
};

// ---- GET /portal/casa/:ente/contas ----

export type PrestacaoPublica = {
  id: string;
  tipo: TipoPrestacao;
  exercicio: number;
  responsavel: string;
  parecerPrevio?: ParecerPrevio | null;
  estado: EstadoPrestacao;
  resultado?: ResultadoJulgamento | null;
  julgadaEm?: string | null;
  proposicaoRotulo?: string | null;
  situacaoTce?: string | null;
  fraseResultado?: string | null;
  /** Só os documentos do TCE (parecer prévio, relatório, decisão) — o servidor filtra. */
  documentos: { id: string; tipo: TipoDocumentoContas; nome: string }[];
};

export type ListaPrestacoesPublicasOut = { prestacoes: PrestacaoPublica[] };

// ---- GET|PUT /parametros-de-contas ----

export type ParametrosContas = { prazoDefesaDias: number; prazoJulgamentoDias: number; padrao?: boolean };

/** Os padrões da ADR (B3/B4), a conferir na LOM de cada Casa. O servidor é quem os aplica; aqui só se dizem. */
export const PADRAO_PRAZO_DEFESA_DIAS = 15;
export const PADRAO_PRAZO_JULGAMENTO_DIAS = 60;
export const LIMITES_PRAZO_DEFESA = { min: 1, max: 120 } as const;
export const LIMITES_PRAZO_JULGAMENTO = { min: 1, max: 365 } as const;

// ---- as entradas ----

export type NovaPrestacaoIn = {
  tipo: TipoPrestacao;
  exercicio: number;
  responsavel: string;
  /** "AAAA-MM-DD" */
  recebidaEm: string;
  processoTce: string;
  parecerPrevio: ParecerPrevio | null;
  comissaoAutoraId: string | null;
  situacaoTce: string;
};

/** O corpo de POST /contas no formato do fio. Só vai o que o tipo usa: governo leva parecer e comissão autora (o PDL);
 *  a Mesa leva a situação no TCE. Opcional vazio vai como `null`, não como "". */
export function corpoDaNovaPrestacao(e: NovaPrestacaoIn) {
  const ouNulo = (s: string) => (s.trim() ? s.trim() : null);
  const base = {
    tipo: e.tipo,
    exercicio: e.exercicio,
    responsavel: e.responsavel.trim(),
    "recebida-em": e.recebidaEm,
    "processo-tce": ouNulo(e.processoTce),
  };
  return e.tipo === "governo_prefeito"
    ? { ...base, "parecer-previo": e.parecerPrevio, "comissao-autora-id": e.comissaoAutoraId }
    : { ...base, "situacao-tce": ouNulo(e.situacaoTce) };
}

export function corpoDaNotificacao(notificadoEm: string, meio: string) {
  return { "notificado-em": notificadoEm, meio: meio.trim() };
}

export function corpoDosParametros(p: { prazoDefesaDias: number; prazoJulgamentoDias: number }) {
  return { "prazo-defesa-dias": p.prazoDefesaDias, "prazo-julgamento-dias": p.prazoJulgamentoDias };
}

// ---- as rotas (relativas ao proxy same-origin /api/* → backend) ----

const enc = encodeURIComponent;

export const ROTAS_CONTAS = {
  lista: "/api/contas",
  registrar: "/api/contas",
  prestacao: (id: string) => `/api/contas/${enc(id)}`,
  notificacao: (id: string) => `/api/contas/${enc(id)}/notificacao`,
  documentos: (id: string, tipo: TipoDocumentoContas) => `/api/contas/${enc(id)}/documentos?tipo=${enc(tipo)}`,
  documento: (id: string, docId: string) => `/api/contas/${enc(id)}/documentos/${enc(docId)}`,
  daProposicao: (proposicaoId: string) => `/api/contas-da-proposicao/${enc(proposicaoId)}`,
  parametros: "/api/parametros-de-contas",
  /** Portal: os segmentos depois de /api/portal/casa/ (buscarPublico codifica cada um). */
  portalSegmentos: (ente: string) => [ente, "contas"],
  portalDocumento: (ente: string, id: string, docId: string) =>
    `/api/portal/casa/${enc(ente)}/contas/${enc(id)}/documentos/${enc(docId)}`,
} as const;

/** O nome do campo do multipart de `POST /contas/:id/documentos` (o mesmo dos comunicados). */
export const CAMPO_DO_DOCUMENTO = "arquivo";

/** O limite da ADR (Banco, `prestacao_contas_documento`): 10 MB por arquivo. O servidor confere de novo. */
export const TAMANHO_MAXIMO_DO_DOCUMENTO = 10 * 1024 * 1024;

// ---- os rótulos em palavras ----

export const ROTULO_TIPO: Record<TipoPrestacao, string> = {
  governo_prefeito: "Contas de governo do Prefeito",
  gestao_camara: "Contas de gestão da Câmara",
};

export const ROTULO_PARECER: Record<ParecerPrevio, string> = {
  favoravel: "Favorável",
  favoravel_com_ressalvas: "Favorável com ressalvas",
  desfavoravel: "Desfavorável",
};

export const ROTULO_ESTADO: Record<EstadoPrestacao, string> = {
  aguardando_notificacao: "Aguardando notificação",
  prazo_de_defesa: "Prazo de defesa",
  pronta_para_pauta: "Pronta para pauta",
  julgada: "Julgada",
  acompanhamento: "Acompanhamento",
};

export const ROTULO_RESULTADO: Record<ResultadoJulgamento, string> = {
  parecer_mantido: "Parecer do TCE mantido",
  parecer_rejeitado: "Parecer do TCE rejeitado",
};

export const ROTULO_DOCUMENTO: Record<TipoDocumentoContas, string> = {
  parecer_previo: "Parecer prévio do TCE",
  relatorio_tce: "Relatório do TCE",
  notificacao: "Notificação ao responsável",
  defesa: "Defesa do responsável",
  decisao_tce: "Decisão do TCE",
  outro: "Outro documento",
};

/** Os tipos que a secretaria escolhe ao subir um documento. A defesa tem bloco próprio (marca a defesa juntada). */
export const TIPOS_DOCUMENTO_UPLOAD: TipoDocumentoContas[] = ["parecer_previo", "relatorio_tce", "notificacao", "decisao_tce", "outro"];

/** Rótulo de um valor do fio; valor desconhecido sai como veio, nunca some. */
export function rotulo<T extends string>(mapa: Record<T, string>, v: T | string | null | undefined): string {
  if (!v) return "";
  return (mapa as Record<string, string>)[v] ?? v;
}

// ---- o acerto com o fio real — aplicado depois de `camelizarChaves`, antes de `formaValida` ----
//
// Nada divergiu ainda (o backend nasce junto). O que está aqui é o TOLERÁVEL sem mentir: lista ausente vira lista
// vazia, a resposta envelopada (`{prestacao: …}`) é aberta, `pautavel` ausente é `false` (fail-closed: sem a palavra
// do servidor, a pauta não se oferece).

type Obj = Record<string, unknown>;
const ehObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);

function prestacaoDoFio(d: unknown): unknown {
  const x = ehObj(d) && ehObj(d.prestacao) && d.id === undefined ? d.prestacao : d;
  if (!ehObj(x)) return x;
  return { ...x, documentos: Array.isArray(x.documentos) ? x.documentos : [], pautavel: x.pautavel === true };
}

export const doFio = {
  prestacao: prestacaoDoFio,
  lista: (d: unknown): unknown => (Array.isArray(d) ? { prestacoes: d } : d),
  publica: (d: unknown): unknown => {
    const x = Array.isArray(d) ? { prestacoes: d } : d;
    return ehObj(x) && Array.isArray(x.prestacoes)
      ? { ...x, prestacoes: x.prestacoes.map((p) => (ehObj(p) ? { ...p, documentos: Array.isArray(p.documentos) ? p.documentos : [] } : p)) }
      : x;
  },
};

// ---- validação mínima de forma (fail-closed: corpo que não bate vira erro na tela, nunca meio-dado) ----

const ehTexto = (v: unknown) => typeof v === "string";
const ehLista = (v: unknown) => Array.isArray(v);
const ehNumero = (v: unknown) => typeof v === "number" && Number.isFinite(v);

const resumoValido = (d: unknown) => {
  const x = d as PrestacaoResumo;
  return !!x && ehTexto(x.id) && ehTexto(x.tipo) && ehNumero(x.exercicio) && ehTexto(x.estado);
};

export const formaValida = {
  lista: (d: unknown) => !!d && ehLista((d as ListaPrestacoesOut).prestacoes) && (d as ListaPrestacoesOut).prestacoes.every(resumoValido),
  prestacao: (d: unknown) => resumoValido(d) && ehLista((d as PrestacaoOut).documentos) && typeof (d as PrestacaoOut).pautavel === "boolean",
  publica: (d: unknown) =>
    !!d && ehLista((d as ListaPrestacoesPublicasOut).prestacoes) && (d as ListaPrestacoesPublicasOut).prestacoes.every(resumoValido),
  parametros: (d: unknown) => {
    const x = d as ParametrosContas;
    return !!x && ehNumero(x.prazoDefesaDias) && ehNumero(x.prazoJulgamentoDias);
  },
};
