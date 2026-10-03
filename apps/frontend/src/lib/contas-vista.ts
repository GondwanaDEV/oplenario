// View-model PURO do julgamento das contas (ADR-0021 Parte B) — sem React, testável isolado. Põe em palavras o que o
// backend decide: o parecer prévio do TCE, a regra dos 2/3 (CF art. 31 §2) em "N de M", os prazos com a data, o motivo
// de a matéria ainda não ir à pauta e a frase do resultado. O servidor é a autoridade (estado derivado, pautável,
// quórum); aqui nada se recalcula que mude o que a Câmara decidiu — só se diz.

import {
  ROTULO_DOCUMENTO,
  ROTULO_ESTADO,
  ROTULO_PARECER,
  ROTULO_TIPO,
  rotulo,
  type EstadoPrestacao,
  type ParecerPrevio,
  type PrestacaoOut,
  type PrestacaoResumo,
  type QuorumContas,
  type TipoDocumentoContas,
  type TipoPrestacao,
} from "./contrato-contas";
import { formatarData, formatarDataSimples } from "./formatar-data";

export const REFERENCIA_CF = "CF art. 31 §2";

/** O texto da Constituição que a ficha cita (art. 31 §2), para a regra não parecer invenção do sistema. */
export const TEXTO_CF_31_2 =
  "O parecer prévio, emitido pelo órgão competente sobre as contas que o Prefeito deve anualmente prestar, só deixará de prevalecer por decisão de dois terços dos membros da Câmara Municipal.";

/** O `[GAP]` do design, dito como é: a tela não crava valores nem índices — eles são do relatório do TCE. */
export const NOTA_VALORES =
  "Valores e índices da prestação seguem o relatório do TCE — não são reproduzidos nesta tela. Abra o relatório para conferir.";

export const PERGUNTA_VOTACAO = "Rejeitar o parecer prévio do TCE?";
export const LEGENDA_SIM = "Sim = rejeitar o parecer";

export const rotuloTipo = (t: TipoPrestacao | string) => rotulo(ROTULO_TIPO, t);
export const rotuloParecer = (p: ParecerPrevio | string | null | undefined) => rotulo(ROTULO_PARECER, p);
export const rotuloEstado = (e: EstadoPrestacao | string) => rotulo(ROTULO_ESTADO, e);
export const rotuloDocumento = (t: TipoDocumentoContas | string) => rotulo(ROTULO_DOCUMENTO, t);

/** A classe do chip de estado (cor é reforço; o texto do chip diz tudo). */
export function tomDoEstado(e: EstadoPrestacao | string): "ok" | "aviso" | "neutro" | "info" {
  if (e === "pronta_para_pauta") return "ok";
  if (e === "prazo_de_defesa" || e === "aguardando_notificacao") return "aviso";
  if (e === "julgada") return "info";
  return "neutro";
}

/** Data de calendário ("AAAA-MM-DD") ou instante, em dd/mm/aaaa — sem recuar um dia no fuso a oeste de Greenwich. */
export function dataLegivel(iso: string | null | undefined): string {
  if (!iso) return "";
  return /^\d{4}-\d{2}-\d{2}$/.test(iso) ? formatarDataSimples(iso) : formatarData(iso);
}

// ---- o herói: o parecer prévio ----

export type HeroiParecer = { titulo: string; complemento: string | null; descricao: string; favoravel: boolean };

export function heroiDoParecer(p: ParecerPrevio | string | null | undefined): HeroiParecer {
  const regra = "O parecer prévio só deixa de prevalecer por decisão de dois terços dos membros da Câmara.";
  if (p === "favoravel_com_ressalvas") {
    return {
      titulo: "Favorável à aprovação",
      complemento: "com ressalvas e recomendações",
      descricao: `O Tribunal recomendou a aprovação das contas, apontando ressalvas a serem sanadas. ${regra}`,
      favoravel: true,
    };
  }
  if (p === "favoravel") {
    return { titulo: "Favorável à aprovação", complemento: null, descricao: `O Tribunal recomendou a aprovação das contas. ${regra}`, favoravel: true };
  }
  if (p === "desfavoravel") {
    return { titulo: "Desfavorável à aprovação", complemento: "o Tribunal recomendou a rejeição", descricao: `O Tribunal recomendou a rejeição das contas. ${regra}`, favoravel: false };
  }
  return { titulo: "Parecer prévio não informado", complemento: null, descricao: regra, favoravel: true };
}

// ---- "Como a Câmara decide": N de M ----

export type NDeM = { n: number; m: number; texto: string; fracao: number; rotuloAcessivel: string };

/** "14 de 21" a partir do `quorum` do servidor. `null` se o quórum não veio (o anel não se desenha com número inventado). */
export function nDeM(q: QuorumContas | null | undefined): NDeM | null {
  if (!q || !(q.baseMembros > 0) || !(q.necessariosParaRejeitar > 0)) return null;
  const { necessariosParaRejeitar: n, baseMembros: m } = q;
  return {
    n,
    m,
    texto: `${n} de ${m}`,
    fracao: Math.min(1, n / m),
    rotuloAcessivel: `${n} votos de ${m} membros são necessários para rejeitar o parecer (dois terços)`,
  };
}

/** O parágrafo ao lado do anel: o que a votação decide e o que acontece sem os 2/3. */
export function textoComoDecide(parecer: ParecerPrevio | string | null | undefined, q: QuorumContas | null | undefined): string {
  const nm = nDeM(q);
  const qual = parecer === "desfavoravel" ? "desfavorável" : "favorável";
  const efeito = parecer === "desfavoravel" ? "as contas ficam rejeitadas" : "as contas ficam aprovadas";
  const quanto = nm ? ` — ${nm.n} dos ${nm.m}` : "";
  return `Para rejeitar o parecer ${qual} do TCE, são necessários os votos de 2/3 dos membros da Câmara${quanto}. Sem esse quórum, o parecer prevalece e ${efeito}, qualquer que seja o placar simples.`;
}

/** "São precisos 14 votos pela rejeição." — usada na ficha e no painel de votação. */
export function fraseDosPrecisos(q: QuorumContas | null | undefined): string {
  const nm = nDeM(q);
  return nm ? `São precisos ${nm.n} votos pela rejeição (2/3 dos ${nm.m} membros).` : "São precisos os votos de 2/3 dos membros da Câmara pela rejeição.";
}

// ---- o resultado ----

/** A frase do resultado: a do servidor quando vem; senão, montada do placar e do quórum. `null` = ainda não julgada. */
export function fraseDoResultado(p: Pick<PrestacaoOut, "resultado" | "fraseResultado" | "votacao" | "quorum">): string | null {
  if (p.fraseResultado && p.fraseResultado.trim()) return p.fraseResultado.trim();
  if (!p.resultado) return null;
  const sim = p.votacao?.sim;
  const precisos = p.quorum?.necessariosParaRejeitar;
  const placar = typeof sim === "number" && typeof precisos === "number" ? `: ${sim} ${sim === 1 ? "voto" : "votos"} pela rejeição, eram precisos ${precisos}` : "";
  return p.resultado === "parecer_rejeitado" ? `O parecer foi rejeitado${placar}.` : `O parecer prevalece${placar}.`;
}

// ---- os prazos ----

export type PrazoLinha = { rotulo: string; valor: string; nota: string | null };

export function prazosDaPrestacao(p: PrestacaoOut): PrazoLinha[] {
  const defesa: PrazoLinha = p.prazoDefesaAte
    ? {
        rotulo: "Prazo de defesa",
        valor: `até ${dataLegivel(p.prazoDefesaAte)}`,
        nota: p.defesaJuntadaEm ? `Defesa juntada em ${dataLegivel(p.defesaJuntadaEm)}.` : "Contado da notificação do responsável.",
      }
    : { rotulo: "Prazo de defesa", valor: "começa na notificação", nota: "Registre a notificação do responsável para o prazo correr." };
  const julgamento: PrazoLinha = p.prazoJulgamentoAte
    ? { rotulo: "Prazo para julgar", valor: `até ${dataLegivel(p.prazoJulgamentoAte)}`, nota: "Contado do recebimento. O efeito do vencimento depende da Lei Orgânica: o sistema avisa, não decide." }
    : { rotulo: "Prazo para julgar", valor: "não informado", nota: null };
  return [defesa, julgamento];
}

// ---- a pauta ----

/** O motivo de a matéria ainda não ir à pauta, em palavras. Frase do servidor passa (com maiúscula e ponto); código
 *  conhecido é traduzido; nada vira texto cru de máquina. */
export function motivoEmPalavras(motivo: string | null | undefined, p?: Pick<PrestacaoOut, "prazoDefesaAte">): string {
  const m = (motivo ?? "").trim();
  if (!m) return "A matéria ainda não pode ir à pauta.";
  const codigos: Record<string, string> = {
    aguardando_notificacao: "Falta registrar a notificação do responsável: a defesa precisa de prazo antes da pauta.",
    prazo_de_defesa: p?.prazoDefesaAte
      ? `O prazo de defesa vai até ${dataLegivel(p.prazoDefesaAte)}. A pauta aceita o PDL depois disso, ou quando a defesa for juntada.`
      : "O prazo de defesa ainda corre. A pauta aceita o PDL depois dele, ou quando a defesa for juntada.",
    julgada: "As contas já foram julgadas.",
    sem_pdl: "Esta prestação não tem Projeto de Decreto Legislativo.",
    gestao_camara: "As contas da Mesa são só acompanhadas: não vão à pauta.",
  };
  if (codigos[m]) return codigos[m];
  if (/^[a-z0-9_]+$/.test(m)) return "A matéria ainda não pode ir à pauta.";
  const frase = m.charAt(0).toUpperCase() + m.slice(1);
  return /[.!?]$/.test(frase) ? frase : `${frase}.`;
}

// ---- a lista ----

export function tituloDaPrestacao(p: Pick<PrestacaoResumo, "tipo" | "exercicio">): string {
  return `${rotuloTipo(p.tipo)} · exercício ${p.exercicio}`;
}

/** A linha de situação da lista: estado em palavras + o que importa dele. */
export function situacaoDaLinha(p: PrestacaoResumo): string {
  if (p.tipo === "gestao_camara") return "Acompanhamento do processo no TCE";
  if (p.resultado) return p.resultado === "parecer_rejeitado" ? "Julgada: o parecer do TCE foi rejeitado" : "Julgada: o parecer do TCE prevaleceu";
  const prazo = p.prazoJulgamentoAte ? ` · julgar até ${dataLegivel(p.prazoJulgamentoAte)}` : "";
  return `${rotuloEstado(p.estado)}${prazo}`;
}

/** As de governo primeiro, depois as da Mesa; dentro de cada, o exercício mais recente primeiro. */
export function ordenarPrestacoes<T extends Pick<PrestacaoResumo, "tipo" | "exercicio">>(ps: T[]): T[] {
  const peso = (t: string) => (t === "governo_prefeito" ? 0 : 1);
  return [...ps].sort((a, b) => peso(a.tipo) - peso(b.tipo) || b.exercicio - a.exercicio);
}

export function vazioDaLista(ehSecretaria: boolean): string {
  return ehSecretaria
    ? "Nenhuma prestação de contas registrada. Quando o parecer prévio do TCE chegar, registre a prestação: o PDL é protocolado junto."
    : "Nenhuma prestação de contas registrada nesta Casa.";
}

// ---- o formulário de registro ----

export type FormNovaPrestacao = {
  tipo: TipoPrestacao;
  exercicio: string;
  responsavel: string;
  recebidaEm: string;
  processoTce: string;
  parecerPrevio: ParecerPrevio | "";
  comissaoAutoraId: string;
  situacaoTce: string;
};

export type ErrosNovaPrestacao = Partial<Record<keyof FormNovaPrestacao, string>>;

/** Confere antes de enviar (o servidor confere de novo). `hoje` = "AAAA-MM-DD" (injetável no teste). */
export function validarNovaPrestacao(f: FormNovaPrestacao, hoje: string): ErrosNovaPrestacao {
  const e: ErrosNovaPrestacao = {};
  const ano = Number(f.exercicio);
  const anoHoje = Number(hoje.slice(0, 4));
  if (!/^\d{4}$/.test(f.exercicio.trim()) || ano < 1988 || ano > anoHoje) e.exercicio = `Informe o ano do exercício, de 1988 a ${anoHoje}.`;
  if (!f.responsavel.trim()) {
    e.responsavel = f.tipo === "governo_prefeito" ? "Informe o Prefeito daquele exercício." : "Informe o Presidente da Câmara daquele exercício.";
  } else if (f.responsavel.trim().length > 200) e.responsavel = "O nome passa de 200 caracteres.";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(f.recebidaEm)) e.recebidaEm = "Informe a data em que a prestação chegou à Câmara.";
  else if (f.recebidaEm > hoje) e.recebidaEm = "A data de recebimento não pode estar no futuro.";
  if (f.tipo === "governo_prefeito") {
    if (!f.parecerPrevio) e.parecerPrevio = "Escolha o parecer prévio do TCE.";
    if (!f.comissaoAutoraId) e.comissaoAutoraId = "Escolha a comissão que assina o Projeto de Decreto Legislativo.";
  }
  return e;
}
