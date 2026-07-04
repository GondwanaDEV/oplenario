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

async function buscarOuNull<T>(url: string, token: string): Promise<T | null> {
  try {
    const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, cache: "no-store" });
    if (!r.ok) return null;
    return (await r.json()) as T;
  } catch {
    return null;
  }
}

// 3 dos cards de MesaOut (presencaResumo/esicCumprimento/relatoresPendentes) são tipados como unions com
// CardIndisponivelOut — o sentinel que o backend emite quando a leitura cross-módulo daquele card falha
// (§16.11). Precisamos distinguir o sentinel do card real antes de acessar campos que só existem no card
// real; um type guard estrutural (em vez de `as`) mantém isso seguro mesmo se o shape mudar.
function ehCardIndisponivel(card: unknown): card is CardIndisponivelOut {
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
