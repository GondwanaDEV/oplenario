// Reducer PURO do painel ao vivo: dobra os 7 eventos do canal plenário no estado de view.
// É o núcleo lógico do HERO (onde mora bug) — testado em plenario-reducer.test.ts. Sem IO: o hook
// (use-plenario) faz o EventSource/fetch e delega a este reducer. O cronômetro NÃO vive aqui: o servidor
// emite só os MARCOS (iniciada/pausada/retomada/tempo_adicional); o display por segundo é recomputado na UI
// a partir de `iniciouEm` + marcos (decisão de §22.6 eixo G — ticks por segundo são descartados no fio).

import type { EventoPlenario, SessaoOut } from "./contrato";
import type { QuorumSessaoOut } from "./contrato-sessoes.gen";

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

export type VotoNominal = "sim" | "nao" | "abstencao";

/** Placar da votação corrente (uma por vez no plenário). §22.6 SIGILO: na SECRETA só existe o CONTADOR
 * (votosSecretos) — JAMAIS voto por vereador; o agregado do encerramento é público mesmo na secreta. */
export interface PlacarVotacao {
  votacaoId: string;
  modalidade: string; // "nominal" | "secreta" (vazio se só vimos o encerramento, sem modalidade no payload)
  objetoTipo: string | null;
  encerrada: boolean;
  votosNominais: Record<string, VotoNominal>; // só NOMINAL: vereadorId -> voto (mostra quem votou o quê)
  votosSecretos: number; // só SECRETA: contagem de votos registrados (anônimo)
  resultado: string | null; // "aprovada" | "rejeitada" (do encerramento)
  totais: { sim: number | null; nao: number | null; abstencao: number | null } | null; // do encerramento
  baseMembros: number | null; // do encerramento (denominador do quórum)
}

export interface EstadoPlenario {
  estado: string; // estado da sessão (agendada|aberta|suspensa|encerrada|nao_realizada|arquivada)
  presentes: string[]; // vereador-ids vistos AO VIVO (conjunto; ordem de inserção) — o DELTA sobre `presencaBase`
  presencaOcorridoEm: Record<string, string>; // vereadorId -> "ocorrido-em" da entrada/retorno que o pôs em `presentes`;
  // existe só para a reconciliação de `hidratarQuorum` (corte por instante) — nunca lido fora deste módulo.
  membrosDaCasa: number | null; // denominador do quórum (Etapa 4b: hidratação de GET /sessoes/:id/quorum); null = borda não respondeu ainda / falhou
  presencaBase: number | null; // numerador "caixa-preta" vindo do snapshot de quórum (sem ids individuais — a rota é MAGRA, §22.6 eixo C)
  hidratadoEm: string | null; // `instante` do snapshot de quórum — fronteira da reconciliação com os eventos ao vivo
  oradorAtual: OradorAtual | null;
  marcosCronometro: MarcoCronometro[]; // marcos da fala EM CURSO (zerados a cada fala.iniciada)
  ultimaFalaEncerrada: { falaId: string; tempoSegundos: number } | null;
  inscritos: Inscrito[]; // fila ordenada por `ordem`
  placar: PlacarVotacao | null; // votação corrente/última (null = nenhuma votação vista)
  ultimoSeq: number; // maior seq visto — vira o Last-Event-ID no resume
}

export function estadoInicial(sessao: SessaoOut): EstadoPlenario {
  return {
    estado: sessao.estado,
    presentes: [],
    presencaOcorridoEm: {},
    membrosDaCasa: null,
    presencaBase: null,
    hidratadoEm: null,
    oradorAtual: null,
    marcosCronometro: [],
    ultimaFalaEncerrada: null,
    inscritos: [],
    placar: null,
    ultimoSeq: 0,
  };
}

/** O numerador do quórum a EXIBIR: a base opaca vinda da hidratação (0 se ainda não hidratado) + os
 * vereadores vistos ao vivo depois dela (ou todos os vistos, se a hidratação nunca chegou — mesmo
 * comportamento de antes da Etapa 4b). PURA — page.tsx só chama, nunca refaz esta conta. */
export function totalPresentes(estado: EstadoPlenario): number {
  return (estado.presencaBase ?? 0) + estado.presentes.length;
}

/** Hidrata o quórum a partir do snapshot de `GET /sessoes/:id/quorum` (Etapa 4b) — corrige os dois defeitos
 * do HERO: dá DENOMINADOR (membrosDaCasa) e semeia o NUMERADOR sem esperar por eventos SSE (recarregar às
 * 15h uma sessão aberta às 14h mostra a Casa cheia, não zero — o replay do canal tem retenção de 5min e não
 * cobre isso). PURA — o hook (usePlenario) só despacha.
 *
 * RECONCILIAÇÃO com o SSE: a corrida é NORMAL nesta tela — o snapshot (fetch) e os eventos (stream) chegam
 * em qualquer ordem relativa. A rota é MAGRA (só números, §22.6 eixo C) — não há ids no snapshot, só o
 * `instante` em que ele foi avaliado no servidor (a mesma semântica de `logic/instante-de-avaliacao`: o
 * numerador já inclui todo evento com `ocorrido-em` <= esse instante). Regra: eventos de presença com
 * `ocorrido-em` <= `instante` já estão embutidos na base — contá-los de novo duplicaria; eventos
 * POSTERIORES não estão, e têm de sobreviver por cima. Isso vale nos dois sentidos de corrida:
 *   - evento chega DEPOIS de hidratar: barrado na hora, em `aplicarEvento` (mesmo corte por instante).
 *   - evento chega ANTES de hidratar (já está em `estado.presentes`): podado AQUI, por `presencaOcorridoEm`
 *     (o único motivo desse mapa existir) — mais velho que o snapshot sai, mais novo fica.
 * Idempotente (Q7): hidratar de novo com o MESMO snapshot substitui a base pelo mesmo valor — não duplica. */
export function hidratarQuorum(estado: EstadoPlenario, quorum: QuorumSessaoOut): EstadoPlenario {
  const instante = quorum.instante;
  const presentes = estado.presentes.filter((id) => {
    const ocorridoEm = estado.presencaOcorridoEm[id];
    return ocorridoEm !== undefined && ocorridoEm > instante;
  });
  const presencaOcorridoEm: Record<string, string> = {};
  for (const id of presentes) presencaOcorridoEm[id] = estado.presencaOcorridoEm[id];
  return {
    ...estado,
    presentes,
    presencaOcorridoEm,
    membrosDaCasa: quorum.quorum.membrosDaCasa,
    presencaBase: quorum.quorum.presentesPlenario + quorum.quorum.presentesRemoto,
    hidratadoEm: instante,
  };
}

export function aplicarEvento(estado: EstadoPlenario, evento: EventoPlenario): EstadoPlenario {
  const base = { ...estado, ultimoSeq: Math.max(estado.ultimoSeq, evento.seq) };

  switch (evento.tipo) {
    case "sessao.transicionou":
      return { ...base, estado: evento.dados.para };

    case "presenca.registrada": {
      const v = evento.dados["vereador-id"];
      const ocorridoEm = evento.dados["ocorrido-em"];
      // reconciliação com a hidratação (Etapa 4b): um evento <= ao instante do snapshot já está embutido no
      // numerador que veio da borda — descartado aqui para não contar 2x. Sem hidratação ainda (hidratadoEm
      // nulo) o corte não se aplica: o evento é aplicado normal e reconciliado depois, em `hidratarQuorum`.
      if (base.hidratadoEm !== null && ocorridoEm <= base.hidratadoEm) return base;

      if (PRESENCA_POSITIVA.has(evento.dados.tipo)) {
        if (base.presentes.includes(v)) return base;
        return {
          ...base,
          presentes: [...base.presentes, v],
          presencaOcorridoEm: { ...base.presencaOcorridoEm, [v]: ocorridoEm },
        };
      }
      // saida (ou tipo não-positivo)
      if (base.presentes.includes(v)) {
        const restante = { ...base.presencaOcorridoEm };
        delete restante[v];
        return { ...base, presentes: base.presentes.filter((x) => x !== v), presencaOcorridoEm: restante };
      }
      // não estava no delta ao vivo: se já hidratado, presume-se da base opaca do snapshot — decrementa
      // direto (nunca < 0). Sem hidratação, não há o que decrementar (mesmo comportamento de antes: no-op).
      if (base.presencaBase !== null) {
        return { ...base, presencaBase: Math.max(0, base.presencaBase - 1) };
      }
      return base;
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

    case "votacao.aberta": {
      // uma votação por vez no plenário: a abertura SUBSTITUI o placar anterior (zera as contagens).
      const d = evento.dados;
      return {
        ...base,
        placar: {
          votacaoId: d["votacao-id"],
          modalidade: d.modalidade,
          objetoTipo: d["objeto-tipo"],
          encerrada: false,
          votosNominais: {},
          votosSecretos: 0,
          resultado: null,
          totais: null,
          baseMembros: null,
        },
      };
    }

    case "voto.registrado": {
      const d = evento.dados;
      // só conta p/ a votação CORRENTE (votos de outra votação / fora de ordem são ignorados).
      if (!base.placar || base.placar.votacaoId !== d["votacao-id"]) return base;
      // §22.6 SIGILO (fail-closed): a modalidade que vale é a da ABERTURA (base.placar.modalidade), não a do
      // evento individual — que poderia chegar adulterado. Só uma votação aberta NOMINAL grava voto por
      // vereador; qualquer outra (secreta, ou desconhecida na reconexão) é tick anônimo, e a identidade que
      // por acaso tenha vindo no fio é DESCARTADA (nunca entra em votosNominais).
      if (base.placar.modalidade !== "nominal") {
        return { ...base, placar: { ...base.placar, votosSecretos: base.placar.votosSecretos + 1 } };
      }
      if (!("vereador-id" in d)) return base; // nominal sem identidade: nada a registrar (descarta)
      // nominal: voto por vereador (idempotente por chave; re-voto sobrescreve).
      return {
        ...base,
        placar: { ...base.placar, votosNominais: { ...base.placar.votosNominais, [d["vereador-id"]]: d.voto as VotoNominal } },
      };
    }

    case "votacao.encerrada": {
      const d = evento.dados;
      const mesma = base.placar !== null && base.placar.votacaoId === d["votacao-id"];
      // mesma votação: preserva o que se acumulou; senão (reconexão sem ter visto a abertura) constrói do agregado.
      const anterior = mesma ? base.placar! : null;
      return {
        ...base,
        placar: {
          votacaoId: d["votacao-id"],
          modalidade: anterior?.modalidade ?? d.modalidade ?? "",
          objetoTipo: anterior?.objetoTipo ?? null,
          encerrada: true,
          votosNominais: anterior?.votosNominais ?? {},
          votosSecretos: anterior?.votosSecretos ?? 0,
          resultado: d.resultado,
          totais: { sim: d["total-sim"] ?? null, nao: d["total-nao"] ?? null, abstencao: d["total-abstencao"] ?? null },
          baseMembros: d["base-membros"] ?? null,
        },
      };
    }

    default:
      return base;
  }
}
