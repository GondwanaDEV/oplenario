// ADR-0018 (fatia 2) — a lógica de TELA do encerramento de uma Câmara, pura (sem rede, sem React): as etapas na ordem
// do Eixo 4 (exportação → confirmação → guarda → destino do acervo → apagamento → encerrada), o tamanho e o código do
// arquivo em português, e qual exportação ainda espera a confirmação. Quem decide de verdade é o servidor; isto só
// diz em que ponto a câmara está.

import type { Casa, Encerramento, Exportacao, Pedido } from "./use-operacao";

export type EstadoEtapa = "feita" | "atual" | "a-seguir";

export type Etapa = {
  chave: "exportacao" | "confirmacao" | "guarda" | "destino" | "apagamento" | "encerrada";
  titulo: string;
  estado: EstadoEtapa;
};

const FORMATO_NUMERO = new Intl.NumberFormat("pt-BR", { maximumFractionDigits: 1 });
const FORMATO_INTEIRO = new Intl.NumberFormat("pt-BR");

/** Bytes → "12,4 MB" (base 1024, como o sistema de arquivos mostra). */
export function tamanho(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  const unidades = ["KB", "MB", "GB", "TB"];
  let v = bytes / 1024;
  let i = 0;
  while (v >= 1024 && i < unidades.length - 1) {
    v /= 1024;
    i++;
  }
  return `${FORMATO_NUMERO.format(v)} ${unidades[i]}`;
}

export function inteiro(n: number | null | undefined): string {
  return n === null || n === undefined ? "—" : FORMATO_INTEIRO.format(n);
}

/** O código (SHA-256) no formato curto, para a linha: os 8 primeiros e os 8 últimos. O inteiro aparece onde se confere. */
export function codigoCurto(sha: string | null | undefined): string {
  if (!sha) return "—";
  return sha.length > 20 ? `${sha.slice(0, 8)}…${sha.slice(-8)}` : sha;
}

/** O código em grupos de 8, para ler em voz alta ou conferir com o arquivo guardado. */
export function codigoEmGrupos(sha: string): string {
  return sha.match(/.{1,8}/g)?.join(" ") ?? sha;
}

/** A exportação pronta mais recente que ainda não teve o recebimento confirmado (a que se confirma agora). */
export function exportacaoParaConfirmar(exportacoes: Exportacao[]): Exportacao | null {
  return exportacoes.find((e) => e.estado === "pronta" && !e.confirmadaEm) ?? null;
}

/** A geração em andamento, se houver (uma por câmara). */
export function exportacaoGerando(exportacoes: Exportacao[]): Exportacao | null {
  return exportacoes.find((e) => e.estado === "gerando") ?? null;
}

const TITULOS: Record<Etapa["chave"], string> = {
  exportacao: "Exportação completa",
  confirmacao: "Confirmação de recebimento",
  guarda: "Guarda de 90 dias",
  destino: "Destino do acervo público",
  apagamento: "Apagamento dos dados",
  encerrada: "Câmara encerrada",
};

/** As etapas na ordem do Eixo 4, com a etapa atual marcada. O destino do acervo é opcional: fica "feita" quando
 *  informado, e nunca é a etapa atual (não segura o caminho). */
export function etapasDoEncerramento(
  casa: Casa,
  enc: Encerramento,
  agora: Date = new Date(),
): Etapa[] {
  const encerrada = casa.estado === "encerrado";
  const temPronta = enc.exportacoes.some((e) => e.estado === "pronta");
  const confirmada = !!enc.confirmacao;
  const guardaCumprida =
    confirmada && !!enc.apagamentoPossivelEm && new Date(enc.apagamentoPossivelEm).getTime() <= agora.getTime();
  const feitas: Record<Etapa["chave"], boolean> = {
    exportacao: encerrada || temPronta,
    confirmacao: encerrada || confirmada,
    guarda: encerrada || guardaCumprida,
    destino: !!enc.destinoAcervoUrl,
    apagamento: encerrada,
    encerrada,
  };
  const ordem: Etapa["chave"][] = ["exportacao", "confirmacao", "guarda", "destino", "apagamento", "encerrada"];
  const atual = encerrada ? null : ordem.find((k) => k !== "destino" && !feitas[k]) ?? null;
  return ordem.map((chave) => ({
    chave,
    titulo: TITULOS[chave],
    estado: feitas[chave] ? "feita" : chave === atual ? "atual" : "a-seguir",
  }));
}

export function rotuloEstadoEtapa(e: EstadoEtapa): string {
  return { feita: "Concluída", atual: "Em andamento", "a-seguir": "A seguir" }[e];
}

/** O pedido de apagamento que espera o 2º operador (o mesmo `pedidoAberto` da ficha, quando é de apagar). */
export function pedidoDeApagamento(pedidoAberto: Pedido | null | undefined): Pedido | null {
  return pedidoAberto && pedidoAberto.acao === "apagar" ? pedidoAberto : null;
}

/** "estado" de uma exportação, em frase curta. */
export function rotuloExportacao(e: Exportacao): string {
  if (e.estado === "gerando") return "Gerando o arquivo…";
  if (e.estado === "falhou") return "A geração falhou";
  return e.confirmadaEm ? "Pronta · recebimento confirmado" : "Pronta";
}

/** As tabelas do resumo do apagamento, maior primeiro (a prova que o console mostra). */
export function tabelasDoResumo(tabelas: Record<string, number> | undefined): [string, number][] {
  return Object.entries(tabelas ?? {}).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
}
