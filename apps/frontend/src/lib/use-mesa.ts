"use client";

// Hook do Dashboard da Mesa (FE Onda A1): busca GET /api/paineis/mesa (o request principal, 1 chamada) +
// as 3 chamadas "enriquecidas" (tramitação/pendências/sli-sessões, em paralelo) — decisão confirmada: 1
// request pro hero (MesaOut já vem com os 4 cards) + 3 chamadas de detalhe para itens reais (Fork 1-A da
// composição + decisão "B enriquecida" do brainstorm). Falha na chamada PRINCIPAL = página inteira em
// erro; falha isolada de UMA chamada de detalhe = aquele detalhe cai para null (a seção correspondente usa
// a versão "só contagem" já presente em `mesa`, nunca deriva pra erro de página).

import { useEffect, useState } from "react";
import type { CardIndisponivelOut, MesaOut, RelatorPendenteOut } from "./contrato-mesa.gen";

export interface ItemBoardOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
  transicionouEm: string;
}
export interface PendenciaOut {
  objetoTipo: string;
  objetoId: string;
  protocolo: string;
  venceEm: string;
  estado: string;
}
export interface SliSessaoOut {
  sessaoId: string;
  estadoAtual: string;
  situacao: string;
  agendadaPara?: string | null;
  abertaEm?: string | null;
  encerradaEm?: string | null;
  duracaoSegundos?: number | null;
}

type Estado = "carregando" | "pronto" | "erro";

// jsonista (backend, apps/backend/src/oplenario/http.clj:json-resposta) serializa keywords Clojure
// VERBATIM — :por-estado vira a chave JSON literal "por-estado", nunca camelCase. O contrato gerado
// (contrato-mesa.gen.ts) e todo o código downstream (mesa-vista.ts, componentes B4-B7) já são escritos
// contra nomes camelCase (porEstado, complianceTce, ...). Sem esta transformação, o acesso por
// propriedade camelCase resolve pra `undefined` contra um payload real do backend (bug B7b). A
// transformação é aplicada UMA VEZ aqui, no único ponto em que os 4 endpoints do dashboard são
// parseados — dependency-free, no mesmo espírito "zero-dep, hand-rolled" do codegen (Task A7).
function paraCamel(chave: string): string {
  return chave.replace(/-+([a-z0-9])/g, (_, c: string) => c.toUpperCase());
}

function camelizarChaves(valor: unknown): unknown {
  if (Array.isArray(valor)) return valor.map(camelizarChaves);
  if (valor !== null && typeof valor === "object") {
    return Object.fromEntries(
      Object.entries(valor as Record<string, unknown>).map(([k, v]) => [paraCamel(k), camelizarChaves(v)]),
    );
  }
  return valor;
}

async function buscarOuNull<T>(url: string, token: string): Promise<T | null> {
  try {
    const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as T;
  } catch {
    return null;
  }
}

// 3 dos cards de MesaOut (presencaResumo/esicCumprimento/relatoresPendentes) são tipados como unions com
// CardIndisponivelOut — o sentinel que o backend emite quando a leitura cross-módulo daquele card falha
// (§16.11). Precisamos distinguir o sentinel do card real antes de acessar campos que só existem no card
// real; um type guard estrutural (em vez de `as`) mantém isso seguro mesmo se o shape mudar. Exportado
// para reuso em mesa-vista.ts (mesmo card sentinel reaparece em presencaResumo/esicCumprimento lá).
export function ehCardIndisponivel(card: unknown): card is CardIndisponivelOut {
  return typeof card === "object" && card !== null && "indisponivel" in card;
}

export function useMesa(token: string | null) {
  const [mesa, setMesa] = useState<MesaOut | null>(null);
  const [tramitacaoItens, setTramitacaoItens] = useState<ItemBoardOut[] | null>(null);
  const [pendenciasItens, setPendenciasItens] = useState<PendenciaOut[] | null>(null);
  const [sliSessoes, setSliSessoes] = useState<SliSessaoOut[] | null>(null);
  const [relatoresPendentes, setRelatoresPendentes] = useState<RelatorPendenteOut[] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (!token) return; // caso de erro é derivado no retorno (sem setState síncrono no effect)
    let vivo = true;
    (async () => {
      const principal = await buscarOuNull<MesaOut>("/api/paineis/mesa", token);
      if (!vivo) return;
      if (!principal) {
        setEstado("erro");
        return;
      }
      setMesa(principal);
      setRelatoresPendentes(
        ehCardIndisponivel(principal.relatoresPendentes) ? null : principal.relatoresPendentes.itens,
      );

      const [tramitacao, pendencias, sli] = await Promise.all([
        buscarOuNull<{ itens: ItemBoardOut[] }>("/api/paineis/tramitacao", token),
        buscarOuNull<{ pendencias: PendenciaOut[] }>("/api/paineis/pendencias", token),
        buscarOuNull<{ sessoes: SliSessaoOut[] }>("/api/paineis/sli/sessoes", token),
      ]);
      if (!vivo) return;
      setTramitacaoItens(tramitacao ? tramitacao.itens : null);
      setPendenciasItens(pendencias ? pendencias.pendencias : null);
      setSliSessoes(sli ? sli.sessoes : null);
      setEstado("pronto");
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  // caso de erro sem token é derivado aqui (mantém o effect livre de setState síncrono)
  if (!token) {
    return {
      mesa: null,
      tramitacaoItens: null,
      pendenciasItens: null,
      sliSessoes: null,
      relatoresPendentes: null,
      estado: "erro" as Estado,
    };
  }
  return { mesa, tramitacaoItens, pendenciasItens, sliSessoes, relatoresPendentes, estado };
}
