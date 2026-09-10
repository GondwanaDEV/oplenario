"use client";

// Hook do Calendário institucional (Onda E, fatia 2). Compõe as DUAS fontes que a Casa tem hoje:
//   - as SESSÕES, reusando `useSessoes` (GET /api/sessoes) — não se duplica busca que já existe;
//   - os PRAZOS, do bloco `em-aberto` de GET /api/compliance/painel.
//
// Os dois estados são SEPARADOS de propósito, e isso não é preciosismo: `/compliance/painel` está atrás
// do papel `secretario` (apps/backend .../compliance/diplomat/http/in.clj, `exige-papel "secretario"`),
// enquanto `/sessoes` não exige o mesmo papel. Um ator sem esse papel recebe 403 só no painel — com um
// estado único, isso apagaria o calendário inteiro (as sessões, que ele PODE ver) por causa de uma fonte
// que ele não pode ver. Com dois estados, a tela mostra a agenda e diz, em texto, que os prazos não
// vieram. Degradação parcial visível, nunca tela vazia que parece "não há nada agendado".
//
// TRUNCAMENTO — a razão de `resumo` ser lido aqui (e não descartado, como estava):
// `GET /compliance/painel` corta `em-aberto` em 100 SEM sinalizar. Medido na fonte:
// `compliance/components/repositorio.clj` (`painel`) tem `:or {limite-em-aberto 100}` e o controller
// (`compliance/controllers.clj`, `painel`) passa `{}` — ou seja, o default vale SEMPRE; e
// `compliance/db/obrigacao.clj` (`listar-em-aberto`) aplica `LIMIT` com `ORDER BY vence_em ASC`.
// O `PainelOut` é `{:closed true}` e não tem flag de truncamento. A ordenação é o amplificador: `vencida`
// também entra no filtro e tem `vence_em` no PASSADO, então ocupa os primeiros slots — a Casa ATRASADA é
// exatamente a que perde os prazos FUTUROS da grade, e a remessa do TCE some da tela sem aviso.
// O sinal para detectar isso vem no MESMO payload: `resumo.pendente + resumo.vencida` é contado por
// `resumo-por-estado`, SEM teto, sobre o mesmo par de estados que `listar-em-aberto` filtra. Comparar os
// dois é o único jeito de a tela saber que está vendo uma página, não o conjunto.
// CARRY p/ o backend (fora desta frente, que é FE-only): alinhar `listar-em-aberto` à disciplina de
// `GET /sessoes` — `:max-rows (inc teto)` + throw (fail-closed), ou aceitar filtro de período pela rota,
// já que o calendário quer uma JANELA, não "as 100 mais urgentes".

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { useSessoes } from "./use-sessoes";
import type { ObrigacaoEmAberto } from "./calendario-vista";

export type EstadoCarga = "carregando" | "pronto" | "erro";

/** Quando a lista de prazos é PÁGINA e não conjunto. `null` = não há divergência detectável. */
export interface TruncamentoPrazos {
  exibidos: number;
  total: number;
}

/** Espelha PainelOut (apps/backend .../compliance/wire/out/painel.clj) já camelizado. Só o que o
 *  calendário usa é declarado: o pipeline de remessas é a tela do comprador (§16.11), não a agenda.
 *  `resumo` NÃO é decoração aqui — é o contador sem teto que denuncia o corte de `emAberto`. */
interface PainelCompliance {
  emAberto?: ObrigacaoEmAberto[];
  resumo?: { pendente?: number; vencida?: number };
}

interface PrazosCarregados {
  obrigacoes: ObrigacaoEmAberto[];
  truncamento: TruncamentoPrazos | null;
}

/** `resumo` é 0-filado pelo backend com as 5 fases sempre presentes, mas o front não depende disso:
 *  chave ausente vira 0 e a comparação simplesmente não acusa nada. Só se AFIRMA truncamento quando o
 *  contador sem teto é maior que a lista — a direção oposta (lista maior que o placar) seria incoerência
 *  do payload, não corte, e a tela não tem o que dizer sobre ela. */
function detectarTruncamento(painel: PainelCompliance, exibidos: number): TruncamentoPrazos | null {
  const total = (painel.resumo?.pendente ?? 0) + (painel.resumo?.vencida ?? 0);
  return total > exibidos ? { exibidos, total } : null;
}

async function buscarPrazos(token: string | null): Promise<PrazosCarregados | null> {
  const r = await apiFetch("/api/compliance/painel", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  const j = camelizarChaves(await r.json()) as PainelCompliance;
  // Chave ausente vira lista vazia AQUI, no boundary: a vista nunca precisa distinguir "veio []" de
  // "não veio a chave" — as duas coisas significam "a Casa não tem prazo em aberto".
  const obrigacoes = j.emAberto ?? [];
  return { obrigacoes, truncamento: detectarTruncamento(j, obrigacoes.length) };
}

export function useCalendario(token: string | null) {
  const { sessoes, estado: estadoSessoes } = useSessoes(token);

  const [obrigacoes, setObrigacoes] = useState<ObrigacaoEmAberto[] | null>(null);
  const [truncamentoPrazos, setTruncamentoPrazos] = useState<TruncamentoPrazos | null>(null);
  const [estadoPrazos, setEstadoPrazos] = useState<EstadoCarga>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return; // o caso sem token é derivado no retorno (sem setState no effect)
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarPrazos(token);
        if (!vivo) return;
        if (resultado === null) {
          setEstadoPrazos("erro");
          return;
        }
        setObrigacoes(resultado.obrigacoes);
        setTruncamentoPrazos(resultado.truncamento);
        setEstadoPrazos("pronto");
      } catch {
        if (vivo) setEstadoPrazos("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  if (semCredencial(token)) {
    return {
      sessoes,
      estadoSessoes,
      obrigacoes: null,
      truncamentoPrazos: null,
      estadoPrazos: "erro" as EstadoCarga,
    };
  }

  return { sessoes, estadoSessoes, obrigacoes, truncamentoPrazos, estadoPrazos };
}
