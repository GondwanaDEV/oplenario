"use client";

// Hook de mutação — POST /api/legislativo/tramitacoes-executivas/:id/apreciacao (apreciação do veto pela
// Câmara: mantém ou derruba). Fecha o GAP docs/20 (bloco 3): o endpoint existe, nenhuma tela o dirigia — o
// /pos-aprovacao só mostrava um texto estático quando `estado === "vetado"`. Mirror EXATO de
// use-registrar-resposta.ts (id fixo no path — aqui a TRAMITAÇÃO-EXECUTIVA-id, NÃO o autógrafo — +
// `lock-version` obrigatório = CAS + corpoKebab filtrando undefined). A resposta 200 é o TramitacaoExecutivaOut
// atualizado (estado -> veto_mantido | veto_derrubado, apreciado-em preenchido).
//
// `veto-votacao-id` é OBRIGATÓRIO: referencia a votação do plenário que decidiu a apreciação (FK real em
// legislativo.votacoes — um UUID inexistente volta 400 `:validacao/votacao-inexistente`, nunca 500). O
// resultado é DECLARADO aqui; a contagem de votos vive na votação referenciada.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { TramitacaoExecutivaOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

export type ApreciarVetoIn = {
  lockVersion: number;
  resultado: string; // "veto_mantido" | "veto_derrubado"
  vetoVotacaoId: string;
};

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: ApreciarVetoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useApreciarVeto(token: string | null, tramitacaoExecutivaId: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    // RE-ARMA no mount (mesmo motivo de use-registrar-resposta.ts: StrictMode roda cleanup+setup no 1º mount,
    // e sem isto todo setEstado pós-resposta viraria no-op e o erro do servidor nunca chegaria à tela).
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function apreciar(corpo: ApreciarVetoIn): Promise<TramitacaoExecutivaOut> {
    if (semCredencial(token)) {
      throw new Error("sem token de autenticacao");
    }
    if (!tramitacaoExecutivaId) {
      throw new Error("tramitacao executiva ainda nao existe");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(
        `/api/legislativo/tramitacoes-executivas/${encodeURIComponent(tramitacaoExecutivaId)}/apreciacao`,
        {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(corpoKebab(corpo)),
        },
      );
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao apreciar o veto (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as TramitacaoExecutivaOut;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current && !tratado) {
        setEstado("erro");
        setErro("falha de rede — tente novamente");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }

  return { apreciar, estado, erro };
}
