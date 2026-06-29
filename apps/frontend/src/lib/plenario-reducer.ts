// Reducer PURO do painel ao vivo: dobra os 7 eventos do canal plenário no estado de view.
// É o núcleo lógico do HERO (onde mora bug) — testado em plenario-reducer.test.ts. Sem IO: o hook
// (use-plenario) faz o EventSource/fetch e delega a este reducer. O cronômetro NÃO vive aqui: o servidor
// emite só os MARCOS (iniciada/pausada/retomada/tempo_adicional); o display por segundo é recomputado na UI
// a partir de `iniciouEm` + marcos (decisão de §22.6 eixo G — ticks por segundo são descartados no fio).

import type { EventoPlenario, SessaoOut } from "./contrato";

/** Tipos de evento de presença que marcam PRESENTE (logic/tipos-presenca-positiva); "saida" remove. */
const PRESENCA_POSITIVA = new Set(["entrada", "retorno", "mudanca_modalidade"]);

export interface MarcoCronometro {
  tipo: string; // pausada | retomada | aparte_concedido | tempo_adicional_concedido
  ocorridoEm: string;
  segundosAdicionais?: number | null;
}

export interface OradorAtual {
  falaId: string;
  oradorId: string;
  tipoFala: string;
  fase: string;
  iniciouEm: string; // âncora do cronômetro client-side
}

export interface Inscrito {
  inscricaoId: string;
  vereadorId: string;
  ordem: number;
}

export interface EstadoPlenario {
  estado: string; // estado da sessão (agendada|aberta|suspensa|encerrada|nao_realizada|arquivada)
  presentes: string[]; // vereador-ids presentes (conjunto; ordem de inserção)
  oradorAtual: OradorAtual | null;
  marcosCronometro: MarcoCronometro[]; // marcos da fala EM CURSO (zerados a cada fala.iniciada)
  ultimaFalaEncerrada: { falaId: string; tempoSegundos: number } | null;
  inscritos: Inscrito[]; // fila ordenada por `ordem`
  ultimoSeq: number; // maior seq visto — vira o Last-Event-ID no resume
}

export function estadoInicial(sessao: SessaoOut): EstadoPlenario {
  return {
    estado: sessao.estado,
    presentes: [],
    oradorAtual: null,
    marcosCronometro: [],
    ultimaFalaEncerrada: null,
    inscritos: [],
    ultimoSeq: 0,
  };
}

export function aplicarEvento(estado: EstadoPlenario, evento: EventoPlenario): EstadoPlenario {
  const base = { ...estado, ultimoSeq: Math.max(estado.ultimoSeq, evento.seq) };

  switch (evento.tipo) {
    case "sessao.transicionou":
      return { ...base, estado: evento.dados.para };

    case "presenca.registrada": {
      const v = evento.dados["vereador-id"];
      if (PRESENCA_POSITIVA.has(evento.dados.tipo)) {
        return base.presentes.includes(v) ? base : { ...base, presentes: [...base.presentes, v] };
      }
      // saida (ou tipo não-positivo) remove
      return { ...base, presentes: base.presentes.filter((x) => x !== v) };
    }

    case "fala.iniciada":
      return {
        ...base,
        oradorAtual: {
          falaId: evento.dados["fala-id"],
          oradorId: evento.dados["orador-id"],
          tipoFala: evento.dados["tipo-fala"],
          fase: evento.dados.fase,
          iniciouEm: evento.dados["iniciou-em"],
        },
        marcosCronometro: [],
      };

    case "fala.cronometro":
      // só acumula marcos da fala em curso (um marco tardio de fala antiga é ignorado)
      if (!base.oradorAtual || base.oradorAtual.falaId !== evento.dados["fala-id"]) return base;
      return {
        ...base,
        marcosCronometro: [
          ...base.marcosCronometro,
          { tipo: evento.dados.tipo, ocorridoEm: evento.dados["ocorrido-em"], segundosAdicionais: evento.dados["segundos-adicionais"] },
        ],
      };

    case "fala.encerrada":
      return {
        ...base,
        oradorAtual: null,
        marcosCronometro: [],
        ultimaFalaEncerrada: { falaId: evento.dados["fala-id"], tempoSegundos: evento.dados["tempo-segundos"] },
      };

    case "inscricao.registrada": {
      const id = evento.dados["inscricao-id"];
      if (base.inscritos.some((i) => i.inscricaoId === id)) return base; // idempotente (at-least-once)
      const inscritos = [
        ...base.inscritos,
        { inscricaoId: id, vereadorId: evento.dados["vereador-id"], ordem: evento.dados.ordem },
      ].sort((a, b) => a.ordem - b.ordem);
      return { ...base, inscritos };
    }

    case "inscricao.desistida":
      return { ...base, inscritos: base.inscritos.filter((i) => i.inscricaoId !== evento.dados["inscricao-id"]) };

    default:
      return base;
  }
}
