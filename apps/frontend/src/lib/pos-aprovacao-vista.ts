// View-model puro do pós-aprovação (Onda B Slice 7, autógrafo + sanção/veto) — traduz AutografoOut/
// TramitacaoExecutivaOut (wire, camelizado) pro que o pipeline de 5 etapas e o anel de prazo mostram.
// Porte de produto/design-system/o-plenario/telas/pos-aprovacao.html (pipeline-stepper + anel).
//
// VOCABULÁRIO REAL (confirmado em apps/backend/src/oplenario/legislativo/logic.clj/estados-resposta-
// executivo/estados-apreciacao-veto): `tramitacao_executiva.estado` é ENUM FECHADO em código (ao
// contrário do `estado` livre de proposição/tramitacao-vista.ts) — aguardando -> {sancionado|
// sancao_tacita|vetado} -> (só p/ vetado) {veto_mantido|veto_derrubado}. Por isso este mapa PODE ser
// fechado (sem fallback fail-closed pro rótulo cru precisar cobrir vocabulário de template por câmara).
//
// Passos 4 (Promulgação) e 5 (Publicação) vêm da NORMA (F3.8b, `PosAprovacaoOut.norma`): promulgada -> passo 4
// feito; publicada -> passo 5 feito. Sem norma, o passo 4 é o "atual" quando o desfecho do Executivo já permite
// promulgar (mesmo conjunto de legislativo.logic/estados-executivo-promulgaveis).

import { diaLocal } from "./calendario-vista";
import { formatarData, formatarDataSimples } from "./formatar-data";
import type { AutografoOut, NormaOut, TramitacaoExecutivaOut } from "./contrato-legislativo.gen";

export type SituacaoEtapa = "feita" | "atual" | "futura";

export type EtapaPipeline = {
  rotulo: string;
  detalhe: string;
  situacao: SituacaoEtapa;
};

const ROTULO_DESFECHO_POR_ESTADO: Record<string, string> = {
  sancionado: "Sancionado",
  sancao_tacita: "Sanção tácita",
  vetado: "Vetado",
  veto_mantido: "Veto mantido pela Câmara",
  veto_derrubado: "Veto derrubado pela Câmara",
};

const ESTADOS_RESOLVIDOS = new Set(Object.keys(ROTULO_DESFECHO_POR_ESTADO));

// Estado da tramitação, mas nil-ável na leitura composta: `PosAprovacaoOut.tramitacaoExecutiva` só é nulo
// no instante teórico entre gerar! e iniciar! (nunca aparece por fora — Repo compõe os dois numa única
// tx, spec §3.1) — mesmo assim tratamos como "aguardando" por segurança (nunca lança).
// Desfechos em que a matéria vira norma — espelho de legislativo.logic/estados-executivo-promulgaveis. O backend
// recusa (409) os outros; aqui só decide se o botão "Promulgar a lei" aparece.
const ESTADOS_PROMULGAVEIS = new Set(["sancionado", "sancao_tacita", "veto_derrubado"]);

export function promulgavel(estado: string | null | undefined): boolean {
  return estado != null && ESTADOS_PROMULGAVEIS.has(estado);
}

const NOME_DA_ESPECIE: Record<string, string> = {
  lei: "Lei",
  lei_complementar: "Lei Complementar",
  resolucao: "Resolução",
  decreto_legislativo: "Decreto Legislativo",
  emenda_lom: "Emenda à Lei Orgânica",
};

// "Lei nº 12/2026" — o número que a Casa e o cidadão usam para citar a norma.
export function formatarNumeroNorma(tipoNorma: string, numero: number, ano: number): string {
  return `${NOME_DA_ESPECIE[tipoNorma] ?? tipoNorma} nº ${numero}/${ano}`;
}

// "virou a Lei nº 5/2026, já publicada" — o Desfecho depois da promulgação. Só o decreto legislativo é masculino.
export function fraseDaNorma(norma: Pick<NormaOut, "tipoNorma" | "numero" | "ano" | "estado">): string {
  const masculino = norma.tipoNorma === "decreto_legislativo";
  const situacao =
    norma.estado === "publicada"
      ? `já publicad${masculino ? "o" : "a"}`
      : "que ainda falta publicar";
  return `virou ${masculino ? "o" : "a"} ${formatarNumeroNorma(norma.tipoNorma, norma.numero, norma.ano)}, ${situacao}`;
}

export function derivarPipeline(
  autografo: AutografoOut,
  tramitacaoExecutiva: TramitacaoExecutivaOut | null,
  norma: NormaOut | null = null,
): EtapaPipeline[] {
  const estado = tramitacaoExecutiva?.estado ?? "aguardando";
  const resolvida = ESTADOS_RESOLVIDOS.has(estado);
  const enviadoEm = formatarData(autografo.enviadoEm);

  const etapaAutografo: EtapaPipeline = {
    rotulo: "Autógrafo",
    detalhe: `${enviadoEm} · gerado`,
    situacao: "feita",
  };

  const etapaExecutivo: EtapaPipeline = {
    rotulo: "No Executivo",
    detalhe: resolvida ? `recebido em ${enviadoEm}` : `desde ${enviadoEm}`,
    situacao: resolvida ? "feita" : "atual",
  };

  const respondidoEm = tramitacaoExecutiva?.respondidoEm ? formatarData(tramitacaoExecutiva.respondidoEm) : null;
  const etapaSancao: EtapaPipeline = {
    rotulo: "Sanção ou veto",
    detalhe: resolvida
      ? `${ROTULO_DESFECHO_POR_ESTADO[estado]}${respondidoEm ? " · " + respondidoEm : ""}`
      : "aguarda",
    situacao: resolvida ? "feita" : "futura",
  };

  const etapaPromulgacao: EtapaPipeline = norma
    ? {
        rotulo: "Promulgação",
        detalhe: `${formatarNumeroNorma(norma.tipoNorma, norma.numero, norma.ano)} · ${formatarData(norma.promulgadoEm)}`,
        situacao: "feita",
      }
    : { rotulo: "Promulgação", detalhe: promulgavel(estado) ? "pode promulgar" : "—", situacao: promulgavel(estado) ? "atual" : "futura" };
  const publicada = norma?.estado === "publicada";
  const etapaPublicacao: EtapaPipeline = publicada
    ? { rotulo: "Publicação", detalhe: norma?.publicadoEm ? formatarData(norma.publicadoEm) : "publicada", situacao: "feita" }
    : { rotulo: "Publicação", detalhe: "vira lei", situacao: norma ? "atual" : "futura" };

  return [etapaAutografo, etapaExecutivo, etapaSancao, etapaPromulgacao, etapaPublicacao];
}

// Autógrafo nº 022/2026 (padding de 3 dígitos — mesmo espírito de formatarNumeroProtocolo em
// expediente-vista.ts, largura própria do mockup-fonte).
export function formatarNumeroAutografo(numero: number, ano: number): string {
  return `${String(numero).padStart(3, "0")}/${ano}`;
}

export type CategoriaPrazo = "urgente" | "atencao" | "tranquilo";

export type PrazoExecutivo =
  | { estado: "sem-prazo" }
  | { estado: "vencido"; diasVencidos: number }
  | { estado: "em-curso"; diasRestantes: number; diasTotal: number; categoria: CategoriaPrazo };

const DIA_MS = 24 * 60 * 60 * 1000;

// Anel de prazo calculado 100% client-side a partir de dado JÁ REAL (`autografo.enviadoEm` = início da
// contagem, mesma tx que abre a tramitação executiva; `autografo.prazoRespostaEm` = fim) — SEM tabela
// nova, SEM worker (spec §2, correção da pesquisa: a generalização polimórfica de `prazo_dominio_ativo`
// não é gatilho desta fatia). `agora` injetável só para teste determinístico.
export function derivarPrazoExecutivo(autografo: AutografoOut, agora: Date = new Date()): PrazoExecutivo {
  if (!autografo.prazoRespostaEm) return { estado: "sem-prazo" };

  const inicioMs = new Date(autografo.enviadoEm).getTime();
  const fimMs = new Date(autografo.prazoRespostaEm).getTime();
  const agoraMs = agora.getTime();

  if (agoraMs >= fimMs) {
    return { estado: "vencido", diasVencidos: Math.max(0, Math.round((agoraMs - fimMs) / DIA_MS)) };
  }

  const diasRestantes = Math.max(0, Math.ceil((fimMs - agoraMs) / DIA_MS));
  // diasTotal nunca menor que diasRestantes (guarda contra relógio/janela estranhos — arcoDashoffset já
  // clipa a fração em [0,1], mas um diasTotal < diasRestantes inverteria a leitura visual do anel).
  const diasTotal = Math.max(diasRestantes, Math.ceil((fimMs - inicioMs) / DIA_MS));
  const categoria: CategoriaPrazo = diasRestantes <= 2 ? "urgente" : diasRestantes <= 5 ? "atencao" : "tranquilo";
  return { estado: "em-curso", diasRestantes, diasTotal, categoria };
}

// ---------------------------------------------------------------------------
// Prazo de sanção/veto informado ao GERAR o autógrafo
// ---------------------------------------------------------------------------
// O backend (wire/in/pos_aprovacao.clj, GerarAutografo) aceita `prazo-resposta-em` OPCIONAL, como instante
// ISO-8601. O autógrafo é append-only (migration 0022: "o autografo nao muda"), então o prazo informado
// NÃO se corrige depois — por isso a tela mostra a data por extenso antes do envio.
// A secretaria escolhe um DIA (o último em que o Executivo pode responder); o instante enviado é o fim
// desse dia no fuso da Casa (America/Fortaleza, UTC−3, sem horário de verão desde 2019 — mesmo fuso do
// backend em kernel/tempo e de calendario-vista.FUSO_DA_CASA). Nenhum valor padrão: o prazo de sanção
// é o da Lei Orgânica de cada Município ([GAP] aberto do projeto), a tela nunca o presume.

const DIA_ISO = /^(\d{4})-(\d{2})-(\d{2})$/;

/** `AAAA-MM-DD` -> instante ISO do último segundo desse dia na Casa. Só chamar com dia já validado. */
export function instanteFimDoDia(dia: string): string {
  return `${dia}T23:59:59-03:00`;
}

function diaExiste(dia: string): boolean {
  const m = DIA_ISO.exec(dia);
  if (!m) return false;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return d.getUTCFullYear() === Number(m[1]) && d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3]);
}

/** Mensagem de erro para o dia do prazo (`AAAA-MM-DD`), ou null se serve. Vazio serve: o prazo é opcional.
 *  `hoje` = dia atual da Casa (`AAAA-MM-DD`). */
export function validarDiaDoPrazo(dia: string, hoje: string): string | null {
  if (dia === "") return null;
  if (!diaExiste(dia)) return "Informe uma data válida para o prazo.";
  if (dia < hoje) return "O prazo não pode ser uma data que já passou.";
  return null;
}

/** O dia atual no fuso da Casa (não o do navegador de quem consulta). */
export function hojeDaCasa(agora: Date = new Date()): string {
  return diaLocal(agora.toISOString()) ?? "";
}

/** Instante do prazo -> `dd/mm/aaaa` do dia da CASA (o servidor devolve em UTC: 23h59 de 20/10 em
 *  Fortaleza chega como 02h59 de 21/10 e não pode recuar nem avançar um dia na tela). */
export function dataDoPrazo(iso: string): string {
  const dia = diaLocal(iso);
  return dia ? formatarDataSimples(dia) : formatarData(iso);
}

export function fraseDoPrazoDoExecutivo(iso: string): string {
  return `O Executivo tem até ${dataDoPrazo(iso)} para sancionar ou vetar.`;
}

/** O backend responde `{erro: "..."}` com texto interno (400 genérico, 409 de domínio). A secretaria lê uma
 *  frase que diz o que fazer; o que não é conhecido segue como veio (nunca é escondido). */
export function fraseErroGerarAutografo(erro: string): string {
  if (erro === "requisicao invalida") {
    return "O pedido não foi aceito. Confira a data do prazo; se outra pessoa já gerou o autógrafo desta matéria, recarregue a página.";
  }
  if (erro.includes("nao foi aprovada em votacao")) {
    return "O autógrafo só pode ser gerado depois que a Câmara aprovar a matéria em votação.";
  }
  if (erro.includes("nao registrou qual texto foi deliberado")) {
    return "A votação que aprovou esta matéria não registrou qual texto foi deliberado, então o autógrafo ainda não pode ser gerado.";
  }
  if (erro === "autorizacao negada") {
    return "Você não tem permissão para gerar o autógrafo.";
  }
  return erro;
}
