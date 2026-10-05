// Regras puras dos formulários do cidadão no portal (ADR-0015): os vocabulários (espelham os enums do backend —
// participacao/logic.clj) e a montagem do corpo que vai ao backend, com os mesmos limites do wire/in. A validação
// aqui é para a pessoa corrigir antes de enviar; o backend continua validando (fail-closed, 400).

import { humanizarChave } from "./humanizar-chave";

export type Opcao = { valor: string; rotulo: string; descricao: string };

// Rótulos e descrições do design (ouvidoria.html).
export const TIPOS_MANIFESTACAO: Opcao[] = [
  { valor: "reclamacao", rotulo: "Reclamação", descricao: "algo não funcionou como deveria" },
  { valor: "denuncia", rotulo: "Denúncia", descricao: "irregularidade ou conduta indevida" },
  { valor: "sugestao", rotulo: "Sugestão", descricao: "uma ideia para melhorar" },
  { valor: "elogio", rotulo: "Elogio", descricao: "um serviço que foi bem prestado" },
  { valor: "solicitacao", rotulo: "Solicitação", descricao: "uma providência ou serviço" },
];

// Rótulos dos botões do balcão LGPD (portal-cidadao.html) — LGPD art. 18.
export const DIREITOS_LGPD: Opcao[] = [
  { valor: "acessar", rotulo: "Acessar meus dados", descricao: "saber quais dados a Câmara tem sobre você" },
  { valor: "corrigir", rotulo: "Corrigir um dado", descricao: "um dado incompleto, errado ou desatualizado" },
  { valor: "eliminar", rotulo: "Eliminar meus dados", descricao: "o que não precisa mais ser guardado" },
  { valor: "com_quem_compartilhado", rotulo: "Com quem foram compartilhados", descricao: "com quais órgãos ou empresas" },
  { valor: "revogar_consentimento", rotulo: "Revogar um consentimento que dei", descricao: "retirar uma autorização" },
];

export const LIMITES = { assunto: 500, descricao: 20000, detalhe: 5000, motivo: 20000 };

export type Resultado<T> = { ok: true; corpo: T } | { ok: false; campo: string };

const aparado = (s: string, max: number): string | null => {
  const t = s.trim();
  return t && t.length <= max ? t : null;
};

export function corpoEsic(f: { assunto: string; descricao: string }): Resultado<{ assunto: string; descricao: string }> {
  const assunto = aparado(f.assunto, LIMITES.assunto);
  if (!assunto) return { ok: false, campo: "assunto" };
  const descricao = aparado(f.descricao, LIMITES.descricao);
  if (!descricao) return { ok: false, campo: "descricao" };
  return { ok: true, corpo: { assunto, descricao } };
}

export function corpoLgpd(f: { tipo: string; detalhe: string }): Resultado<{ tipo: string; detalhe?: string }> {
  if (!DIREITOS_LGPD.some((d) => d.valor === f.tipo)) return { ok: false, campo: "tipo" };
  const t = f.detalhe.trim();
  if (t.length > LIMITES.detalhe) return { ok: false, campo: "detalhe" };
  return { ok: true, corpo: t ? { tipo: f.tipo, detalhe: t } : { tipo: f.tipo } };
}

export function corpoManifestacao(f: {
  tipo: string;
  assunto: string;
  descricao: string;
  anonima: boolean;
}): Resultado<{ tipo: string; assunto: string; descricao: string; anonima: boolean }> {
  if (!TIPOS_MANIFESTACAO.some((t) => t.valor === f.tipo)) return { ok: false, campo: "tipo" };
  const assunto = aparado(f.assunto, LIMITES.assunto);
  if (!assunto) return { ok: false, campo: "assunto" };
  const descricao = aparado(f.descricao, LIMITES.descricao);
  if (!descricao) return { ok: false, campo: "descricao" };
  return { ok: true, corpo: { tipo: f.tipo, assunto, descricao, anonima: f.anonima } };
}

export function direitoDaUrl(v: string | undefined): string {
  return v && DIREITOS_LGPD.some((d) => d.valor === v) ? v : "";
}

export const MENSAGEM_CAMPO: Record<string, string> = {
  tipo: "Escolha uma das opções.",
  assunto: `Escreva o assunto (até ${LIMITES.assunto} caracteres).`,
  descricao: "Descreva o que você precisa.",
  detalhe: `O detalhe passou de ${LIMITES.detalhe} caracteres.`,
  motivo: "Conte por que você não concordou com a resposta.",
};

// Estados do backend (e-SIC, LGPD e ouvidoria) em palavras.
const ESTADOS: Record<string, string> = {
  protocolado: "Protocolado",
  protocolada: "Protocolada",
  em_analise: "Em análise",
  respondido: "Respondido",
  respondida: "Respondida",
  indeferido: "Indeferido",
  indeferida: "Indeferida",
  arquivada: "Arquivada",
};

export function rotuloEstado(estado: string): string {
  return ESTADOS[estado] ?? humanizarChave(estado, "Situação não informada");
}

/** O rótulo de um tipo/direito da lista; valor que a tela não conhece sai em palavras, nunca a chave. */
export function rotuloDaLista(lista: Opcao[], valor: string): string {
  return lista.find((o) => o.valor === valor)?.rotulo ?? humanizarChave(valor);
}
