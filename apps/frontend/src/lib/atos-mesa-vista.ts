// View-model PURO dos ATOS DA MESA no cockpit (docs/23 Fatia 2): decisões sobre questão de ordem e incidentes
// processuais. Sem IO — o hook (use-atos-mesa.ts) busca; aqui só se deriva o que a tela mostra.
//
// A regra que motiva a fatia: quem DECIDE a questão de ordem é quem preside (o Presidente da Mesa, ou o vice em
// exercício); quem REGISTRA é o operador da Casa. O formulário pré-seleciona o Presidente da Mesa da composição
// da sessão, e o backend recusa (409) quem não compõe a Casa naquela data.

import type { AtosMesaOut, ComposicaoMembroOut, DecisaoMesaOut, IncidenteOut } from "./contrato-sessoes.gen";
import { nomeDoMembro } from "./tribuna-mesa-vista";

export type TipoIncidente = IncidenteOut["tipo"];
export type ResultadoIncidente = IncidenteOut["resultado"];

/** Espelha `logic/tipos-incidente` (questão de ordem vive em decisão da Mesa; retirada de pauta, na pauta). */
export const TIPOS_INCIDENTE: { valor: TipoIncidente; rotulo: string }[] = [
  { valor: "pedido_vista", rotulo: "Pedido de vista" },
  { valor: "verificacao_votacao", rotulo: "Verificação de votação" },
  { valor: "urgencia", rotulo: "Urgência" },
  { valor: "votacao_em_bloco", rotulo: "Votação em bloco" },
];

/** Espelha `logic/resultados-incidente`. */
export const RESULTADOS_INCIDENTE: { valor: ResultadoIncidente; rotulo: string }[] = [
  { valor: "deferido", rotulo: "Deferido" },
  { valor: "indeferido", rotulo: "Indeferido" },
  { valor: "prejudicado", rotulo: "Prejudicado" },
  { valor: "retirado", rotulo: "Retirado" },
];

const ROTULO_TIPO = new Map(TIPOS_INCIDENTE.map((t) => [t.valor, t.rotulo]));
const ROTULO_RESULTADO = new Map(RESULTADOS_INCIDENTE.map((r) => [r.valor, r.rotulo]));

/** O cargo de Mesa chega como texto de cadastro ("presidente", "Presidente", "vice-presidente"…): normaliza para
 * comparar sem depender de caixa, acento de hífen ou espaço. */
function normalizarCargo(cargo: string | null): string {
  return (cargo ?? "").trim().toLowerCase().replace(/[\s-]+/g, "_");
}

/** Quem o formulário pré-seleciona como "quem presidiu": o Presidente da Mesa da composição da sessão. Sem
 * Presidente cadastrado → null (o operador escolhe; nunca se adivinha). */
export function presidentePadrao(membros: ComposicaoMembroOut[]): string | null {
  return membros.find((m) => normalizarCargo(m.cargoMesa) === "presidente")?.vereadorId ?? null;
}

export interface OpcaoMembro {
  vereadorId: string;
  rotulo: string;
}

/** As opções de "quem presidiu": primeiro os membros da Mesa (Presidente à frente), depois os demais, cada grupo
 * em ordem alfabética. Todo membro da composição é opção legítima (a presidência pode passar a qualquer um na
 * falta da Mesa) — o backend só exige que componha a Casa. */
export function opcoesDePresidencia(membros: ComposicaoMembroOut[]): OpcaoMembro[] {
  const peso = (m: ComposicaoMembroOut) => {
    const c = normalizarCargo(m.cargoMesa);
    if (c === "presidente") return 0;
    return c ? 1 : 2;
  };
  return membros
    .slice()
    .sort((a, b) => peso(a) - peso(b) || nomeDoMembro(a.vereadorId, membros).localeCompare(nomeDoMembro(b.vereadorId, membros), "pt-BR"))
    .map((m) => ({
      vereadorId: m.vereadorId,
      rotulo: m.cargoMesa ? `${nomeDoMembro(m.vereadorId, membros)} · ${m.cargoMesa}` : nomeDoMembro(m.vereadorId, membros),
    }));
}

export interface AtoNaLinhaDoTempo {
  id: string;
  natureza: "decisao" | "incidente";
  quando: string;
  titulo: string;
  /** O que foi suscitado (questão, ou a descrição do incidente). */
  texto: string;
  /** A disposição: a decisão da Mesa, ou o resultado do incidente. */
  desfecho: string;
  /** Deliberação/fundamentação, quando houver. */
  nota: string | null;
  /** "Decidiu: Fulana" (decisão) / "Requereu: Fulano" (incidente com requerente). */
  pessoa: string | null;
  resultado: ResultadoIncidente | null;
}

function daDecisao(d: DecisaoMesaOut, membros: ComposicaoMembroOut[]): AtoNaLinhaDoTempo {
  return {
    id: d.id,
    natureza: "decisao",
    quando: d.decididoEm,
    titulo: "Questão de ordem",
    texto: d.questao,
    desfecho: d.decisao,
    nota: d.fundamentacao ?? null,
    pessoa: `Decidiu: ${nomeDoMembro(d.presidenteId, membros)}`,
    resultado: null,
  };
}

function doIncidente(i: IncidenteOut, membros: ComposicaoMembroOut[]): AtoNaLinhaDoTempo {
  return {
    id: i.id,
    natureza: "incidente",
    quando: i.ocorridoEm,
    titulo: ROTULO_TIPO.get(i.tipo) ?? i.tipo,
    texto: i.descricao,
    desfecho: ROTULO_RESULTADO.get(i.resultado) ?? i.resultado,
    nota: i.deliberacao ?? null,
    pessoa: i.requerenteId ? `Requereu: ${nomeDoMembro(i.requerenteId, membros)}` : null,
    resultado: i.resultado,
  };
}

/** As duas listas numa linha do tempo só, do mais recente para o mais antigo (o operador olha o que acabou de
 * registrar). Empate de instante: decisões antes de incidentes, depois por id — ordem estável. */
export function linhaDoTempo(atos: AtosMesaOut | null, membros: ComposicaoMembroOut[]): AtoNaLinhaDoTempo[] {
  if (!atos) return [];
  const todos = [...atos.decisoes.map((d) => daDecisao(d, membros)), ...atos.incidentes.map((i) => doIncidente(i, membros))];
  return todos.sort((a, b) => {
    const t = Date.parse(b.quando) - Date.parse(a.quando);
    if (t !== 0) return t;
    if (a.natureza !== b.natureza) return a.natureza === "decisao" ? -1 : 1;
    return a.id.localeCompare(b.id);
  });
}
