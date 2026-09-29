// Lógica pura do painel da IA DA CASA (Faixa B / B.9, ADR-0014, docs/25 Eixo 8.4): quanto a IA custou no mês × o
// orçamento do plano, em que pé está a cota, o que cada capacidade fez e o que as pessoas fizeram com o resultado.
// Só o TEXTO e as contas de exibição — o que vale (cota, pausa) é decidido no satélite.

import type { CapacidadeIAOut, PainelIAOut } from "./contrato-paineis.gen";

const NOMES: Record<string, string> = {
  "ata.redigir": "Rascunho da ata",
  "resumo.redigir": "Resumo cidadão",
  "conferencia.redigir": "Conferência das proposições",
  "agente.planejar": "Assistente da Casa",
  "agente.responder": "Assistente da Casa",
  "requerimento.preencher": "Copiloto do requerimento",
  "requerimento.justificar": "Copiloto do requerimento",
};

/** O nome que a Casa conhece para uma operação do satélite (a operação crua quando não há nome). */
export function nomeDaOperacao(operacao: string): string {
  return NOMES[operacao] ?? operacao;
}

/** O que a IA faz sozinha: pausa primeiro quando o orçamento acaba. */
const SEGUNDO_PLANO = new Set(["Resumo cidadão", "Conferência das proposições"]);

export type LinhaCapacidade = {
  nome: string;
  segundoPlano: boolean;
  execucoes: number;
  naoRodaram: number;
  custo: number;
  revisados: number;
  aprovados: number;
  editados: number;
  descartados: number;
  errosReportados: number;
};

/** As operações do satélite agrupadas pelo nome que a Casa conhece (o assistente planeja e responde: uma linha). */
export function porCapacidade(ops: CapacidadeIAOut[]): LinhaCapacidade[] {
  const m = new Map<string, LinhaCapacidade>();
  for (const o of ops) {
    const nome = NOMES[o.operacao] ?? o.operacao;
    const l =
      m.get(nome) ??
      { nome, segundoPlano: SEGUNDO_PLANO.has(nome), execucoes: 0, naoRodaram: 0, custo: 0, revisados: 0, aprovados: 0,
        editados: 0, descartados: 0, errosReportados: 0 };
    l.execucoes += o.execucoes;
    l.naoRodaram += o.indisponiveis;
    l.custo += Number(o.custo) || 0;
    l.aprovados += o.aprovados;
    l.editados += o.editados;
    l.descartados += o.descartados;
    l.revisados += o.aprovados + o.editados + o.descartados;
    l.errosReportados += o.errosReportados;
    m.set(nome, l);
  }
  return [...m.values()].sort((a, b) => b.custo - a.custo || a.nome.localeCompare(b.nome));
}

export function dinheiro(valor: number | string | null | undefined, moeda: string | null | undefined): string {
  const n = Number(valor ?? 0);
  try {
    return new Intl.NumberFormat("pt-BR", { style: "currency", currency: moeda || "USD" }).format(n);
  } catch {
    return `${n.toFixed(2)} ${moeda ?? ""}`.trim();
  }
}

/** Quanto do orçamento mensal já foi (0–100+), ou null sem orçamento/sem consumo. */
export function percentual(p: Pick<PainelIAOut, "gasto" | "orcamento">): number | null {
  if (!p.orcamento || p.gasto === null) return null;
  const mensal = Number(p.orcamento.mensal);
  if (!(mensal > 0)) return Number(p.gasto) > 0 ? 100 : 0;
  return Math.round((Number(p.gasto) / mensal) * 100);
}

export type Situacao = { titulo: string; detalhe: string; tom: "neutro" | "ok" | "aviso" | "alerta" };

/** A frase do estado da cota, em linguagem de quem administra a Casa. */
export function situacaoDaCota(p: PainelIAOut): Situacao {
  const teto = p.orcamento ? dinheiro(p.orcamento.tetoDuro, p.orcamento.moeda) : "";
  if (!p.consumoDisponivel)
    return { titulo: "Consumo indisponível agora", tom: "neutro",
      detalhe: "A IA não respondeu agora. O consumo do mês aparece quando ela voltar; nada do trabalho da Casa depende dela." };
  switch (p.estado) {
    case "sem_orcamento":
      return { titulo: "Sem orçamento definido", tom: "neutro",
        detalhe: "O uso da IA é só medido. O orçamento mensal segue o plano contratado e é definido pela equipe do O Plenário." };
    case "aviso":
      return { titulo: "Passou de 80% do orçamento", tom: "aviso",
        detalhe: `Quando o orçamento acabar, o que a IA faz sozinha pausa primeiro; o que as pessoas pedem segue até o teto de ${teto}.` };
    case "segundo_plano_pausado":
      return { titulo: "Orçamento do mês esgotado", tom: "alerta",
        detalhe: `O resumo cidadão e a conferência das proposições estão pausados até o mês que vem (ou até o plano aumentar). O que as pessoas pedem na tela segue até o teto de ${teto}.` };
    case "esgotada":
      return { titulo: "Teto do mês atingido", tom: "alerta",
        detalhe: "A IA está indisponível para a Casa até o mês que vem. Todo o trabalho segue pela tela, como sempre." };
    default:
      return { titulo: "Dentro do orçamento", tom: "ok", detalhe: "A IA está disponível para tudo o que a Casa usa." };
  }
}

const MESES = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro",
  "novembro", "dezembro"];

export function rotuloDoMes(mes: string): string {
  const [a, m] = mes.split("-").map(Number);
  return m >= 1 && m <= 12 ? `${MESES[m - 1]} de ${a}` : mes;
}

export function deslocarMes(mes: string, delta: number): string {
  const [a, m] = mes.split("-").map(Number);
  const t = a * 12 + (m - 1) + delta;
  return `${Math.floor(t / 12)}-${String((t % 12) + 1).padStart(2, "0")}`;
}

export function linhaDeRevisao(l: LinhaCapacidade): string | null {
  if (l.revisados === 0) return null;
  const partes = [
    l.aprovados && `${l.aprovados} ${l.aprovados === 1 ? "aprovado" : "aprovados"} como veio`,
    l.editados && `${l.editados} ${l.editados === 1 ? "editado" : "editados"}`,
    l.descartados && `${l.descartados} ${l.descartados === 1 ? "descartado" : "descartados"}`,
  ].filter(Boolean);
  const aceitos = Math.round(((l.aprovados + l.editados) / l.revisados) * 100);
  return `${aceitos}% aproveitado (${partes.join(", ")})`;
}

export function mensagemDeErroIaCasa(status: number): string {
  if (status === 403) return "O painel da IA é do administrador da Casa.";
  if (status === 400) return "Mês inválido.";
  return "Não foi possível carregar o painel agora. Tente de novo.";
}
