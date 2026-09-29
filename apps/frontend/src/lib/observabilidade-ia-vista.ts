// Lógica pura da OBSERVABILIDADE DA IA no console do operador (Onda E, porte de
// produto/design-system/o-plenario/telas/observabilidade-ia.html, arquétipo cockpit): a saúde da IA em todas as
// Casas numa janela — volume, tempo de resposta, o que não rodou e por quê, custo, por capacidade e por fornecedor.
//
// DESVIOS DO DESIGN, todos por honestidade:
//   • "Taxa de fallback / provedor alternativo" — não existe fallback entre fornecedores: quando o fornecedor falha a
//     execução não roda (R-IA-1) e a tela da Casa segue sem IA. Sai "Não rodaram", com o motivo;
//   • ASR de áudio e embeddings/pgvector como provedores — a transcrição e a busca não passam pelo registro da Camada
//     de Confiança; entram só os fornecedores do modelo de linguagem que o registro viu, com o p95 medido;
//   • o selo "Operacional / Lentidão" por provedor — não há alvo de latência definido; sai o fato (quantas execuções
//     não rodaram), sem adjetivo inventado;
//   • "Custo · mês em R$" e "vs. ontem" — o custo é o da janela, na moeda da tabela de preços; comparação com o período
//     anterior não existe nesta fatia.

import { dinheiro, nomeDaOperacao } from "./ia-casa-vista";

export type AgregadoIA = {
  execucoes: number;
  indisponiveis: number;
  latenciaP50Ms: number | null;
  latenciaP95Ms: number | null;
  custo: string;
  parcial: boolean;
};

export type ObservabilidadeIA = {
  disponivel: boolean;
  horas: number;
  desde: string | null;
  ate: string | null;
  casas: number | null;
  moeda: string | null;
  total: AgregadoIA | null;
  porOperacao: (AgregadoIA & { operacao: string })[];
  porFornecedor: (AgregadoIA & { vendor: string; modelo: string | null })[];
  motivosIndisponivel: { motivo: string; execucoes: number }[];
  porHora: { inicio: string; execucoes: number; indisponiveis: number }[];
};

export type Metrica = { titulo: string; valor: string; detalhe: string | null; alerta: boolean };
export type LinhaRecurso = { nome: string; codigo: string; detalhe: string; p95: string; largura: number };
export type LinhaFornecedor = { nome: string; detalhe: string; p95: string; falhas: string | null };
export type Barra = { rotulo: string; altura: number; execucoes: number; indisponiveis: number };

export type VistaObservabilidade = {
  janela: string; // "24 horas" | "7 dias"
  metricas: Metrica[];
  recursos: LinhaRecurso[];
  fornecedores: LinhaFornecedor[];
  motivos: { rotulo: string; execucoes: string }[];
  barras: Barra[];
  legenda: string[];
  vazio: string | null;
  custoParcial: boolean;
};

const MOTIVOS: Record<string, string> = {
  sigilo: "Barrada pela governança (dado sigiloso)",
  nada_a_enviar: "Nada a enviar depois da governança",
  fornecedor_fora: "Fornecedor fora do ar",
  sobrecarga: "Fornecedor sobrecarregado",
  entrada_invalida: "Pedido fora do formato",
  saida_invalida: "Resposta reprovada na conferência",
  recusa: "O modelo recusou",
  cota: "Cota da Casa atingida",
  desconhecido: "Motivo não registrado",
};

const inteiro = new Intl.NumberFormat("pt-BR");
const umaCasa = new Intl.NumberFormat("pt-BR", { maximumFractionDigits: 1 });

export function rotuloJanela(horas: number): string {
  return horas % 24 === 0 && horas > 24 ? `${horas / 24} dias` : `${horas} horas`;
}

/** 120 ms · 1,9 s · — (sem medida). */
export function tempo(ms: number | null): string {
  if (ms === null) return "—";
  return ms < 1000 ? `${inteiro.format(ms)} ms` : `${umaCasa.format(ms / 1000)} s`;
}

function execucoes(n: number): string {
  return `${inteiro.format(n)} ${n === 1 ? "execução" : "execuções"}`;
}

function proporcao(parte: number, todo: number): string {
  if (todo === 0) return "0%";
  const p = (parte / todo) * 100;
  return `${p > 0 && p < 0.1 ? "<0,1" : umaCasa.format(p)}%`;
}

const HORA = new Intl.DateTimeFormat("pt-BR", { hour: "2-digit", timeZone: "America/Fortaleza" });
const DIA = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit", timeZone: "America/Fortaleza" });

function rotuloHora(iso: string, dias: boolean): string {
  const d = new Date(iso);
  return dias ? DIA.format(d) : `${HORA.format(d)}h`;
}

export function derivarObservabilidade(o: ObservabilidadeIA): VistaObservabilidade {
  const janela = rotuloJanela(o.horas);
  const t = o.total;
  const n = t?.execucoes ?? 0;
  const falhas = t?.indisponiveis ?? 0;
  const metricas: Metrica[] = [
    { titulo: `Execuções · ${janela}`, valor: t ? inteiro.format(n) : "—", detalhe: "do modelo de linguagem", alerta: false },
    { titulo: "Tempo de resposta p95", valor: tempo(t?.latenciaP95Ms ?? null),
      detalhe: t?.latenciaP50Ms != null ? `mediana ${tempo(t.latenciaP50Ms)}` : null, alerta: false },
    { titulo: `Custo · ${janela}`, valor: t ? dinheiro(t.custo, o.moeda) : "—",
      detalhe: t?.parcial ? "parcial: modelo sem preço" : "tabela de preços do satélite", alerta: false },
    { titulo: "Não rodaram", valor: t ? proporcao(falhas, n) : "—",
      detalhe: t ? `${inteiro.format(falhas)} de ${inteiro.format(n)}` : null, alerta: n > 0 && falhas / n >= 0.05 },
    { titulo: "Câmaras que usaram", valor: o.casas === null ? "—" : inteiro.format(o.casas), detalhe: null, alerta: false },
  ];
  const maiorP95 = Math.max(1, ...o.porOperacao.map((x) => x.latenciaP95Ms ?? 0));
  const recursos = o.porOperacao.map((x) => ({
    nome: nomeDaOperacao(x.operacao),
    codigo: x.operacao,
    detalhe: [execucoes(x.execucoes), x.indisponiveis ? `${inteiro.format(x.indisponiveis)} não rodaram` : null,
      dinheiro(x.custo, o.moeda) + (x.parcial ? " (parcial)" : "")].filter(Boolean).join(" · "),
    p95: tempo(x.latenciaP95Ms),
    largura: x.latenciaP95Ms === null ? 0 : Math.max(2, Math.round((x.latenciaP95Ms / maiorP95) * 100)),
  }));
  const fornecedores = o.porFornecedor.map((f) => ({
    nome: f.modelo ? `${f.vendor} · ${f.modelo}` : f.vendor,
    detalhe: `${execucoes(f.execucoes)} · mediana ${tempo(f.latenciaP50Ms)}`,
    p95: tempo(f.latenciaP95Ms),
    falhas: f.indisponiveis ? `${inteiro.format(f.indisponiveis)} não rodaram` : null,
  }));
  const maior = Math.max(1, ...o.porHora.map((h) => h.execucoes));
  const dias = o.horas > 24;
  const barras = o.porHora.map((h) => ({
    rotulo: `${rotuloHora(h.inicio, false)}${dias ? ` de ${rotuloHora(h.inicio, true)}` : ""}: ${execucoes(h.execucoes)}`,
    altura: Math.round((h.execucoes / maior) * 100),
    execucoes: h.execucoes,
    indisponiveis: h.indisponiveis,
  }));
  const k = o.porHora.length;
  const legenda =
    k === 0 ? [] : [0, Math.floor(k / 4), Math.floor(k / 2), Math.floor((3 * k) / 4)].map((i) =>
      rotuloHora(o.porHora[i].inicio, dias)).concat("agora");
  return {
    janela,
    metricas,
    recursos,
    fornecedores,
    motivos: o.motivosIndisponivel.map((m) => ({ rotulo: MOTIVOS[m.motivo] ?? m.motivo, execucoes: execucoes(m.execucoes) })),
    barras,
    legenda,
    vazio: o.disponivel && n === 0 ? `Nenhuma execução da IA nas últimas ${janela}, em nenhuma câmara.` : null,
    custoParcial: Boolean(t?.parcial),
  };
}
