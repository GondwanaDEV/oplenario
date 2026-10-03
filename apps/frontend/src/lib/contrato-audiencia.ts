// ADR-0021 Parte A — o contrato de fio da AUDIÊNCIA PÚBLICA (módulo `sessoes`), como o FE o vê DEPOIS de
// `camelizarChaves` (o backend fala kebab-case; ver boundary.ts). Escrito à mão, não gerado: o backend nasce em
// paralelo a partir do MESMO contrato (a tabela "Rotas — Parte A" da ADR), e o que a forma real ajustar se acerta
// AQUI, em `doFio` (aplicado logo depois de `camelizarChaves`), para os hooks e as telas seguirem com os tipos de
// sempre. Quando o codegen publicar estes modelos em `contrato-sessoes.gen.ts`, estes tipos dão lugar a ele.
//
// O que é contrato e o que é suposição deste lado:
//   - rotas, métodos, campos e estados vêm da ADR (seção "O contrato");
//   - as escritas da Mesa (chamar, encerrar, ausente, PATCH) NÃO têm a resposta lida: a tela recarrega a
//     audiência depois de cada uma. A forma exata do corpo de resposta não importa para o FE;
//   - campos opcionais da ADR (`local?`, `entidade?`, `referencia?`, `proposicao?`) podem chegar ausentes ou
//     `null`: `doFio` os normaliza para `null`, e as listas ausentes viram `[]` — nunca meio-dado na tela.

/** Para que a audiência foi convocada (ADR A4). Só `metas_fiscais` tem prazo legal (LRF art. 9 §4). */
export type Finalidade = "tematica" | "metas_fiscais" | "ldo" | "loa" | "ppa";

/** Como a pessoa se apresenta ao falar (A2). Fora de `individual`, a entidade é obrigatória. */
export type FalaComo = "individual" | "entidade" | "conselho_movimento";

/** Terminais: `falou`, `ausente`, `desistiu`. No máximo uma `falando` por sessão. */
export type EstadoInscricao = "inscrita" | "falando" | "falou" | "ausente" | "desistiu";

export type OrigemInscricao = "portal_govbr" | "presencial_secretaria";

export type EstadoSessao = "agendada" | "aberta" | "suspensa" | "encerrada" | "arquivada" | "nao_realizada";

export type ProposicaoRelacionada = { id: string; rotulo: string; ementa: string };

// ---- a Mesa (GET /sessoes/:id/audiencia) ----

export type InscricaoOut = {
  id: string;
  protocolo: string;
  ordem: number;
  nome: string;
  falaComo: FalaComo;
  entidade: string | null;
  tema: string;
  origem: OrigemInscricao;
  estado: EstadoInscricao;
  chamadaEm: string | null;
  encerradaEm: string | null;
  tempoUsadoSegundos: number | null;
};

export type AudienciaOut = {
  sessaoId: string;
  numero: number;
  estado: EstadoSessao;
  agendadaPara: string | null;
  modalidade: string;
  comissao: { id: string; nome: string };
  tema: string;
  local: string | null;
  proposicao: ProposicaoRelacionada | null;
  finalidade: Finalidade;
  referencia: string | null;
  tempoFalaSegundos: number;
  inscricoesAbertas: boolean;
  inscricoes: InscricaoOut[];
};

/** PATCH /sessoes/:id/audiencia — só o que muda vai no corpo. */
export type AjusteAudienciaIn = { tempoFalaSegundos?: number; inscricoesAbertas?: boolean; local?: string };

export function corpoDoAjuste(a: AjusteAudienciaIn) {
  const c: Record<string, unknown> = {};
  if (a.tempoFalaSegundos !== undefined) c["tempo-fala-segundos"] = a.tempoFalaSegundos;
  if (a.inscricoesAbertas !== undefined) c["inscricoes-abertas"] = a.inscricoesAbertas;
  if (a.local !== undefined) c.local = a.local;
  return c;
}

/** POST /sessoes/:id/audiencia/inscricoes — a Mesa inscreve quem está presente (o nome é digitado). */
export type InscricaoPresencialIn = { nome: string; falaComo: FalaComo; entidade: string | null; tema: string };

// ---- o portal (público) ----

export type ResumoAudiencia = {
  sessaoId: string;
  tema: string;
  comissaoNome: string;
  agendadaPara: string | null;
  estado: EstadoSessao;
  local: string | null;
  finalidade: Finalidade;
};

/** GET /portal/casa/:ente/audiencias — realizadas = as últimas 20. */
export type AudienciasPublicasOut = { proximas: ResumoAudiencia[]; realizadas: ResumoAudiencia[] };

export type FalaPublica = { nome: string; falaComo: FalaComo; entidade: string | null };

/** GET /portal/casa/:ente/audiencias/:sessao-id. `inscricoesAbertas` já é o EFETIVO (flag ∧ sessão agendada,
 *  aberta ou suspensa); `inscritos` não conta desistentes; `falaram` só vem depois de encerrada. */
export type AudienciaPublicaOut = ResumoAudiencia & {
  modalidade: string;
  proposicao: ProposicaoRelacionada | null;
  referencia: string | null;
  tempoFalaSegundos: number;
  inscricoesAbertas: boolean;
  inscritos: number;
  ataPublicada: boolean;
  falaram: FalaPublica[];
};

/** POST /portal/audiencias/:sessao-id/inscricoes. O nome NÃO vai: vem da identidade do gov.br. */
export type InscricaoCidadaIn = { falaComo: FalaComo; entidade: string | null; tema: string };

export function corpoDaInscricaoCidada(i: InscricaoCidadaIn) {
  return {
    "fala-como": i.falaComo,
    ...(i.falaComo === "individual" ? {} : { entidade: (i.entidade ?? "").trim() }),
    tema: i.tema.trim(),
    "ciente-publicidade": true,
  };
}

export function corpoDaInscricaoPresencial(i: InscricaoPresencialIn) {
  return {
    nome: i.nome.trim(),
    "fala-como": i.falaComo,
    ...(i.falaComo === "individual" ? {} : { entidade: (i.entidade ?? "").trim() }),
    tema: i.tema.trim(),
  };
}

/** O 201 da inscrição: o protocolo `AUD-AAAA-NNNNNN`, a hora e a posição na fila. */
export type ReciboInscricaoOut = { protocolo: string; reciboEm: string; ordem: number };

// ---- a área da cidadã ----

export type MinhaInscricaoOut = {
  id: string;
  protocolo: string;
  sessaoId: string;
  tema: string;
  comissaoNome: string;
  agendadaPara: string | null;
  ordem: number;
  estado: EstadoInscricao;
  reciboEm: string;
};

export type MinhasInscricoesOut = { inscricoes: MinhaInscricaoOut[] };

// ---- as rotas (relativas ao proxy same-origin /api/* → backend) ----

const enc = encodeURIComponent;

export const ROTAS_AUDIENCIA = {
  audiencia: (sessaoId: string) => `/api/sessoes/${enc(sessaoId)}/audiencia`,
  inscricoes: (sessaoId: string) => `/api/sessoes/${enc(sessaoId)}/audiencia/inscricoes`,
  chamada: (sessaoId: string, inscId: string) => `/api/sessoes/${enc(sessaoId)}/audiencia/inscricoes/${enc(inscId)}/chamada`,
  encerramento: (sessaoId: string, inscId: string) =>
    `/api/sessoes/${enc(sessaoId)}/audiencia/inscricoes/${enc(inscId)}/encerramento`,
  ausencia: (sessaoId: string, inscId: string) => `/api/sessoes/${enc(sessaoId)}/audiencia/inscricoes/${enc(inscId)}/ausencia`,
  /** Segmentos para `buscarPublico` (que prefixa `/api/portal/casa/` e codifica cada um). */
  publicas: (ente: string) => [ente, "audiencias"],
  publica: (ente: string, sessaoId: string) => [ente, "audiencias", sessaoId],
  /** Escritas da cidadã: `useEnvioCidadao` prefixa `/api`. */
  inscreverCidada: (sessaoId: string) => `/portal/audiencias/${enc(sessaoId)}/inscricoes`,
  minhas: "/api/portal/minhas-inscricoes",
  desistencia: (inscId: string) => `/portal/minhas-inscricoes/${enc(inscId)}/desistencia`,
} as const;

/** O endereço do livro de atas do portal já aberto nesta sessão. */
export function hrefAtaNoPortal(ente: string, sessaoId: string): string {
  return `/portal/casa/${enc(ente)}/atas?sessao=${enc(sessaoId)}`;
}

// ---- rótulos em palavras ----

export const FINALIDADES: { valor: Finalidade; rotulo: string }[] = [
  { valor: "tematica", rotulo: "Temática" },
  { valor: "metas_fiscais", rotulo: "Metas fiscais do quadrimestre (LRF)" },
  { valor: "ldo", rotulo: "Lei de Diretrizes Orçamentárias (LDO)" },
  { valor: "loa", rotulo: "Lei Orçamentária Anual (LOA)" },
  { valor: "ppa", rotulo: "Plano Plurianual (PPA)" },
];

export const FALA_COMO: { valor: FalaComo; rotulo: string; descricao: string }[] = [
  { valor: "individual", rotulo: "Cidadão(ã) individual", descricao: "você fala por você" },
  { valor: "entidade", rotulo: "Representante de entidade", descricao: "associação, sindicato, empresa, ONG" },
  { valor: "conselho_movimento", rotulo: "Conselho ou movimento", descricao: "conselho municipal, coletivo, movimento" },
];

const ESTADO_INSCRICAO: Record<EstadoInscricao, string> = {
  inscrita: "Na fila para falar",
  falando: "Falando agora",
  falou: "Falou",
  ausente: "Ausente na chamada",
  desistiu: "Desistiu",
};

const ESTADO_SESSAO: Record<EstadoSessao, string> = {
  agendada: "Agendada",
  aberta: "Acontecendo agora",
  suspensa: "Suspensa",
  encerrada: "Realizada",
  arquivada: "Realizada",
  nao_realizada: "Não realizada",
};

const rotuloDe = <T extends string>(lista: { valor: T; rotulo: string }[], v: string) =>
  lista.find((o) => o.valor === v)?.rotulo ?? v;

export const rotuloFinalidade = (f: string) => rotuloDe(FINALIDADES, f);
export const rotuloFalaComo = (f: string) => rotuloDe(FALA_COMO, f);
export const rotuloEstadoInscricao = (e: string) => ESTADO_INSCRICAO[e as EstadoInscricao] ?? e;
export const rotuloEstadoAudiencia = (e: string) => ESTADO_SESSAO[e as EstadoSessao] ?? e;

/** Os quadrimestres da LRF (art. 9 §4): a audiência de metas fiscais presta contas do quadrimestre anterior. */
export const QUADRIMESTRES: { valor: "Q1" | "Q2" | "Q3"; rotulo: string; meses: string }[] = [
  { valor: "Q1", rotulo: "1º quadrimestre", meses: "jan–abr" },
  { valor: "Q2", rotulo: "2º quadrimestre", meses: "mai–ago" },
  { valor: "Q3", rotulo: "3º quadrimestre", meses: "set–dez" },
];

export const REFERENCIA_VALIDA = /^\d{4}-Q[123]$/;

/** "2026-Q1" → "1º quadrimestre de 2026 (jan–abr)". Formato fora do padrão sai cru, nunca inventado. */
export function rotuloReferencia(ref: string | null | undefined): string | null {
  if (!ref) return null;
  if (!REFERENCIA_VALIDA.test(ref)) return ref;
  const [ano, q] = ref.split("-");
  const quad = QUADRIMESTRES.find((x) => x.valor === q)!;
  return `${quad.rotulo} de ${ano} (${quad.meses})`;
}

// ---- o acerto com o fio real (ver o cabeçalho) — aplicado depois de `camelizarChaves`, antes de `formaValida` ----

type Obj = Record<string, unknown>;
const ehObj = (v: unknown): v is Obj => !!v && typeof v === "object" && !Array.isArray(v);
const ouNull = (v: unknown) => (v === undefined ? null : v);
const lista = (v: unknown) => (Array.isArray(v) ? v : []);

function inscricao(i: unknown): unknown {
  if (!ehObj(i)) return i;
  return {
    ...i,
    entidade: ouNull(i.entidade),
    chamadaEm: ouNull(i.chamadaEm),
    encerradaEm: ouNull(i.encerradaEm),
    tempoUsadoSegundos: ouNull(i.tempoUsadoSegundos),
  };
}

function resumo(r: unknown): unknown {
  if (!ehObj(r)) return r;
  const comissaoNome =
    typeof r.comissaoNome === "string" ? r.comissaoNome : ehObj(r.comissao) && typeof r.comissao.nome === "string" ? r.comissao.nome : "";
  return { ...r, comissaoNome, local: ouNull(r.local), agendadaPara: ouNull(r.agendadaPara) };
}

export const doFio = {
  audiencia: (d: unknown): unknown => {
    if (!ehObj(d)) return d;
    const comissao = ehObj(d.comissao)
      ? d.comissao
      : { id: typeof d.comissaoId === "string" ? d.comissaoId : "", nome: typeof d.comissaoNome === "string" ? d.comissaoNome : "" };
    return {
      ...d,
      comissao,
      local: ouNull(d.local),
      proposicao: ouNull(d.proposicao),
      referencia: ouNull(d.referencia),
      agendadaPara: ouNull(d.agendadaPara),
      inscricoes: lista(d.inscricoes).map(inscricao),
    };
  },
  inscricao,
  publicas: (d: unknown): unknown =>
    ehObj(d) ? { ...d, proximas: lista(d.proximas).map(resumo), realizadas: lista(d.realizadas).map(resumo) } : d,
  publica: (d: unknown): unknown => {
    if (!ehObj(d)) return d;
    return {
      ...(resumo(d) as Obj),
      proposicao: ouNull(d.proposicao),
      referencia: ouNull(d.referencia),
      inscritos: typeof d.inscritos === "number" ? d.inscritos : 0,
      ataPublicada: d.ataPublicada === true,
      inscricoesAbertas: d.inscricoesAbertas === true,
      falaram: lista(d.falaram).map((f) => (ehObj(f) ? { ...f, entidade: ouNull(f.entidade) } : f)),
    };
  },
  minhas: (d: unknown): unknown =>
    ehObj(d) ? { ...d, inscricoes: lista(d.inscricoes).map((i) => (ehObj(i) ? (resumo(i) as Obj) : i)) } : d,
};

// ---- validação mínima de forma (fail-closed: corpo que não bate vira erro na tela, nunca meio-dado) ----

const ehTexto = (v: unknown) => typeof v === "string";
const ehLista = (v: unknown) => Array.isArray(v);

export const formaValida = {
  inscricao: (d: unknown) => {
    const x = d as InscricaoOut;
    return !!x && ehTexto(x.id) && ehTexto(x.estado) && typeof x.ordem === "number";
  },
  audiencia: (d: unknown) => {
    const x = d as AudienciaOut;
    return (
      !!x &&
      ehTexto(x.sessaoId) &&
      ehTexto(x.tema) &&
      typeof x.tempoFalaSegundos === "number" &&
      ehLista(x.inscricoes) &&
      x.inscricoes.every(formaValida.inscricao)
    );
  },
  publicas: (d: unknown) => {
    const x = d as AudienciasPublicasOut;
    return !!x && ehLista(x.proximas) && ehLista(x.realizadas);
  },
  publica: (d: unknown) => {
    const x = d as AudienciaPublicaOut;
    return !!x && ehTexto(x.sessaoId) && ehTexto(x.tema) && ehTexto(x.estado) && ehLista(x.falaram);
  },
  recibo: (d: unknown) => {
    const x = d as ReciboInscricaoOut;
    return !!x && ehTexto(x.protocolo) && typeof x.ordem === "number";
  },
  minhas: (d: unknown) => !!d && ehLista((d as MinhasInscricoesOut).inscricoes),
};
