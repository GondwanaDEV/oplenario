// View-model PURO do painel "Atos disponíveis" da tramitação (sem React, testável isolado). Traduz o
// TramitacaoOut do backend no que a tela mostra: ou a lista de atos que o rito declara a partir do estado
// atual, ou uma única `nota` honesta quando não há ato (sem rito / estado fora do rito / terminal / beco
// sem saída — o backend já diz qual no campo `nota`).
//
// `gatilho` é o verbo que vai no corpo do POST (imutável). `rotulo` é só apresentação — humaniza a chave
// (distribuir_comissao → "Distribuir comissao") sem NUNCA ser o que se envia. `condicional`
// (`podeSerRecusado`) e `exigeAutorizacao` viram dicas de que o ato PODE ser recusado no disparo (409/403)
// — a leitura não avalia guards, então a tela não promete sucesso (ver GatilhoPossivelOut no backend).

import type { TramitacaoOut } from "./use-tramitacao";
import { vistaCarga, type VistaCarga } from "./recebimento-vista";

export interface AtoTramitacao {
  gatilho: string;
  rotulo: string;
  condicional: boolean;
  exigeAutorizacao: boolean;
  destinos: string[];
}

export type VistaAcoesTramitacao =
  | { tipo: "com-atos"; estadoAtual: string; atos: AtoTramitacao[] }
  | { tipo: "sem-atos"; estadoAtual: string; nota: string }
  // fatia 2b: a matéria chegou a um estado que exige RECEBIMENTO e ninguém recebeu. Os atos ficam de fora de
  // propósito: o backend recusaria todos (409 `recebimento-pendente`) — o único ato possível é receber.
  | { tipo: "carga"; estadoAtual: string; carga: VistaCarga };

const NOTA_PADRAO = "Nenhum ato disponível a partir do estado atual.";

export function humanizarGatilho(gatilho: string): string {
  const semSep = gatilho.replace(/[_-]+/g, " ").trim();
  if (!semSep) return gatilho;
  return semSep.charAt(0).toUpperCase() + semSep.slice(1);
}

export function derivarAcoesTramitacao(t: TramitacaoOut, agora: Date = new Date()): VistaAcoesTramitacao {
  if (t.recebimentoPendente) {
    return { tipo: "carga", estadoAtual: t.estadoAtual, carga: vistaCarga(t.recebimentoPendente, agora) };
  }
  const gatilhos = t.gatilhosPossiveis ?? [];
  if (gatilhos.length === 0) {
    return { tipo: "sem-atos", estadoAtual: t.estadoAtual, nota: t.nota ?? NOTA_PADRAO };
  }
  return {
    tipo: "com-atos",
    estadoAtual: t.estadoAtual,
    atos: gatilhos.map((g) => ({
      gatilho: g.gatilho,
      rotulo: humanizarGatilho(g.gatilho),
      condicional: g.podeSerRecusado,
      exigeAutorizacao: g.exigeAutorizacao,
      destinos: g.destinosPossiveis ?? [],
    })),
  };
}
