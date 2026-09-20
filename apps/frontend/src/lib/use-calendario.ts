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
// TRUNCAMENTO — CARRY CUMPRIDO: `GET /compliance/painel` cortava `em-aberto` em 100 SEM sinalizar, e
// este hook fazia a única defesa que existia no sistema inteiro, deduzindo o corte por
// `resumo.pendente + resumo.vencida` (contado sem teto) contra o tamanho da lista. Agora
// `compliance/wire/out/painel.clj` emite `em-aberto-total` — o total AUTORITATIVO server-side (mesmo par
// lista+total de `transparencia/wire/out/parlamentar`) — e este hook lê ESSE campo, não mais deduz. Duas
// fontes que podem discordar são piores que uma: manter a heurística ao lado do campo novo mascararia um
// bug no cálculo do servidor. `resumo` não é mais lido aqui.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { useSessoes } from "./use-sessoes";
import type { ObrigacaoEmAberto } from "./calendario-vista";

export type EstadoCarga = "carregando" | "pronto" | "erro";

// Permissao de buscar os PRAZOS (GET /compliance/painel, `secretario`-only por design — ver cabecalho).
// A pagina resolve isto do papel do ator e passa ao hook, em vez de o hook bater na porta e tomar 403:
//   "buscar"     — pode ver (secretario): busca normalmente.
//   "bloqueado"  — papel sem acesso (ex.: vereador): degrada direto para "prazos nao vieram", SEM o
//                  request condenado que so' sujava o console com 403 (achado docs/20).
//   "aguardando" — ainda nao se sabe o papel (GET /eu em voo no modo real): segura em "carregando".
// Default "buscar" preserva o comportamento (e os testes) de quem chama sem informar a permissao.
export type PrazosPermissao = "buscar" | "bloqueado" | "aguardando";

/** Quando a lista de prazos é PÁGINA e não conjunto. `null` = não há divergência detectável. */
export interface TruncamentoPrazos {
  exibidos: number;
  total: number;
}

/** Espelha PainelOut (apps/backend .../compliance/wire/out/painel.clj) já camelizado. Só o que o
 *  calendário usa é declarado: o pipeline de remessas é a tela do comprador (§16.11), não a agenda.
 *  `emAbertoTotal` é o total AUTORITATIVO server-side (não o teto — o teto nunca é publicado ao
 *  cliente) — é o que permite a tela dizer "mostrando N de M" sem deduzir nada. */
interface PainelCompliance {
  emAberto?: ObrigacaoEmAberto[];
  emAbertoTotal?: number;
}

interface PrazosCarregados {
  obrigacoes: ObrigacaoEmAberto[];
  truncamento: TruncamentoPrazos | null;
}

/** `emAbertoTotal` ausente (payload antigo, ou fixture de teste incompleta) não permite AFIRMAR
 *  truncamento — a tela cala em vez de inventar um aviso. Só se afirma corte quando o total do servidor
 *  é estritamente maior que o exibido; total igual ou (incoerentemente) menor não é corte. */
function detectarTruncamento(total: number | undefined, exibidos: number): TruncamentoPrazos | null {
  return total !== undefined && total > exibidos ? { exibidos, total } : null;
}

async function buscarPrazos(token: string | null): Promise<PrazosCarregados | null> {
  const r = await apiFetch("/api/compliance/painel", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  const j = camelizarChaves(await r.json()) as PainelCompliance;
  // Chave ausente vira lista vazia AQUI, no boundary: a vista nunca precisa distinguir "veio []" de
  // "não veio a chave" — as duas coisas significam "a Casa não tem prazo em aberto".
  const obrigacoes = j.emAberto ?? [];
  return { obrigacoes, truncamento: detectarTruncamento(j.emAbertoTotal, obrigacoes.length) };
}

export function useCalendario(token: string | null, prazos: PrazosPermissao = "buscar") {
  const { sessoes, estado: estadoSessoes } = useSessoes(token);

  const [obrigacoes, setObrigacoes] = useState<ObrigacaoEmAberto[] | null>(null);
  const [truncamentoPrazos, setTruncamentoPrazos] = useState<TruncamentoPrazos | null>(null);
  const [estadoPrazos, setEstadoPrazos] = useState<EstadoCarga>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return; // o caso sem token é derivado no retorno (sem setState no effect)
    if (prazos === "aguardando") return; // papel ainda desconhecido: segura em "carregando", nao busca
    if (prazos === "bloqueado") {
      // Papel sem acesso ao painel (ex.: vereador): `/compliance/painel` daria 403 de qualquer forma.
      // Degrada direto para o MESMO estado visivel de "prazos nao vieram", sem o request condenado.
      setEstadoPrazos("erro");
      return;
    }
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
  }, [token, prazos]);

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
