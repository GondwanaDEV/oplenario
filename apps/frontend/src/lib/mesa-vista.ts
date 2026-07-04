// View-model PURO do Dashboard da Mesa (FE Onda A1) — traduz o retorno de useMesa em "o que cada seção da
// página mostra", incluindo os 3 estados por seção (disponivel/indisponivel/em-breve). Nenhum componente
// React sabe interpretar MesaOut diretamente; eles só leem daqui (mesmo padrão de placar-vista.ts).

import type { ItemBoardOut, PendenciaOut, SliSessaoOut } from "./use-mesa";
import { ehCardIndisponivel } from "./use-mesa";
import type { MesaOut, RelatorPendenteOut } from "./contrato-mesa.gen";

// complianceTce é opaco no contrato gerado (Record<string, unknown> — a real materialização de
// domínio §22.7 não é modelada estaticamente ali); esta é a forma esperada quando NÃO é o sentinel de
// degradação {indisponivel:true}. Leitura local só, nunca exposta como tipo público.
// emAberto espelha ObrigacaoEmAbertoOut (apps/backend .../compliance/wire/out/painel.clj) — id/vence-em
// já eram usados aqui; template-chave/objeto-tipo/objeto-id/estado entram agora para que "O que vence"
// (Task B6) tenha essas chaves tipadas em vez de precisar de type-cast solto no consumidor.
interface ComplianceCard {
  resumo: Record<string, number>;
  emAberto: {
    id: string;
    templateChave: string;
    objetoTipo: string;
    objetoId: string;
    venceEm: string;
    estado: string;
  }[];
  remessasRecentes: unknown[];
}

// mesa.tramitacao.porEstado é Record<string, unknown>[] no contrato gerado (opaco, mesmo motivo do
// ComplianceCard acima) — mas o backend sempre emite { estado, n } (confirmado no fixture de
// mesa-vista.test.ts e no mock de paineis-mesa.html). PipelineLegislativo (Task B7) precisa desses 2
// campos tipados pra alimentar BarraSegmentada/TabuleiroEstagios; widening local, não touca o gerado.
interface EstagioResumo {
  estado: string;
  n: number;
}

export interface MesaVistaInput {
  mesa: MesaOut | null;
  tramitacaoItens: ItemBoardOut[] | null;
  pendenciasItens: PendenciaOut[] | null;
  sliSessoes: SliSessaoOut[] | null;
  relatoresPendentes: RelatorPendenteOut[] | null;
}

export function derivarMesaVista(input: MesaVistaInput) {
  const { mesa, tramitacaoItens, pendenciasItens, sliSessoes, relatoresPendentes } = input;

  if (!mesa) {
    return {
      saude: { estado: "indisponivel" as const, resumo: undefined, emAberto: undefined },
      oQueVence: { estado: "indisponivel" as const, itens: [] as { venceEm: string; origem: "compliance" | "pendencia" }[] },
      pipeline: {
        estado: "indisponivel" as const,
        comItens: false,
        porEstado: [] as EstagioResumo[],
        itens: [] as ItemBoardOut[],
      },
      despachos: {
        relator: { estado: "indisponivel" as const, itens: [] as RelatorPendenteOut[] },
        distribuicao: { estado: "em-breve" as const },
        autografo: { estado: "em-breve" as const },
        ata: { estado: "em-breve" as const },
      },
      orgulho: {
        estado: "indisponivel" as const,
        presencaMedia: null as number | null,
        esicPercentual: null as number | null,
        totalTramitacao: null as number | null,
        transmissaoAoVivo: { estado: "em-breve" as const },
      },
      proximaSessaoCiencia: { estado: "em-breve" as const },
      lenteJuridico: { estado: "em-breve" as const },
      _sliSessoes: sliSessoes,
    };
  }

  // complianceTce é opaco (Record<string, unknown>) no contrato gerado, então o compilador não sabe que
  // "sem o sentinel" implica a forma de ComplianceCard — o guard já eliminou o sentinel em runtime; o
  // double-cast (via unknown) é o jeito seguro de expressar isso sem `any`.
  const complianceOk = !ehCardIndisponivel(mesa.complianceTce);
  const compliance = complianceOk ? (mesa.complianceTce as unknown as ComplianceCard) : null;

  return {
    saude: complianceOk
      ? { estado: "disponivel" as const, resumo: compliance!.resumo, emAberto: compliance!.emAberto as unknown[] }
      : { estado: "indisponivel" as const, resumo: undefined, emAberto: undefined },

    oQueVence: complianceOk
      ? {
          estado: "disponivel" as const,
          itens: [
            ...compliance!.emAberto.map((i) => ({ ...i, origem: "compliance" as const })),
            ...(pendenciasItens ?? []).map((i) => ({ ...i, origem: "pendencia" as const })),
          ].sort((a, b) => a.venceEm.localeCompare(b.venceEm)),
        }
      : { estado: "indisponivel" as const, itens: [] as { venceEm: string; origem: "compliance" | "pendencia" }[] },

    pipeline:
      tramitacaoItens !== null
        ? {
            estado: "disponivel" as const,
            comItens: true,
            porEstado: mesa.tramitacao.porEstado as unknown as EstagioResumo[],
            itens: tramitacaoItens,
          }
        : {
            estado: "disponivel" as const,
            comItens: false,
            porEstado: mesa.tramitacao.porEstado as unknown as EstagioResumo[],
            itens: [] as ItemBoardOut[],
          },

    despachos: {
      relator:
        relatoresPendentes !== null
          ? { estado: "disponivel" as const, itens: relatoresPendentes }
          : { estado: "indisponivel" as const, itens: [] as RelatorPendenteOut[] },
      distribuicao: { estado: "em-breve" as const },
      autografo: { estado: "em-breve" as const },
      ata: { estado: "em-breve" as const },
    },

    orgulho: {
      estado: "disponivel" as const,
      // guard inline (não pré-computado em variável) para que o narrowing do TS se aplique diretamente a
      // mesa.presencaResumo/mesa.esicCumprimento no branch positivo.
      presencaMedia: ehCardIndisponivel(mesa.presencaResumo) ? null : mesa.presencaResumo.mediaPercentual,
      esicPercentual: ehCardIndisponivel(mesa.esicCumprimento) ? null : mesa.esicCumprimento.percentual,
      totalTramitacao: mesa.tramitacao.total,
      transmissaoAoVivo: { estado: "em-breve" as const },
    },

    proximaSessaoCiencia: { estado: "em-breve" as const },
    lenteJuridico: { estado: "em-breve" as const },

    _sliSessoes: sliSessoes,
  };
}

export type MesaVista = ReturnType<typeof derivarMesaVista>;
