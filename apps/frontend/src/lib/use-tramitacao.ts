"use client";

// Hook de leitura da TRAMITACAO de uma matéria (GAP docs/20 → agora com tela): GET
// /api/legislativo/proposicoes/:id/tramitacao. Diferente da ficha (GET .../ficha, que só traz o histórico
// como `tramitacao`), esta rota devolve o que o rito PERMITE AGORA — `gatilhosPossiveis` (os atos que o
// operador pode disparar) + `estadoAtual` + `nota` (só quando não há ato). É o que faltava para a tela
// dirigir o POST de tramitação (o endpoint existia, mas nenhuma página o chamava — botão "Distribuir a
// comissão" da ficha era inerte).
//
// TIPO-ESPELHO (não gerado): `TramitacaoOut` não está no manifesto do codegen (nenhum consumidor FE existia
// até agora). Espelho à mão de apps/backend/.../legislativo/wire/out/proposicao.clj (TramitacaoOut /
// GatilhoPossivelOut) — mesma disciplina de mesa-vista.ts. Adicionar ao manifesto Malli→TS é follow-up.
//
// Mirror do idioma de use-parecer-editor.ts: 3-estados + reset em render-time ao trocar `id` + guard `vivo`
// + `recarregar()` imperativo (depois de tramitar com sucesso, o estado/os atos mudam — o painel refaz o
// GET sem trocar `id`, com o mesmo guard `idAtualRef` contra respostas tardias de um `id` já abandonado).

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export interface GatilhoPossivel {
  gatilho: string;
  destinosPossiveis: string[];
  podeSerRecusado: boolean;
  exigeAutorizacao: boolean;
}

export interface TramitacaoOut {
  proposicaoId: string;
  estadoAtual: string;
  templateId: string | null;
  estadoTerminal: boolean | null;
  historicoTruncado: boolean;
  gatilhosPossiveis: GatilhoPossivel[];
  nota: string | null;
}

type Estado = "carregando" | "pronto" | "erro";

async function buscarTramitacao(token: string | null, id: string): Promise<TramitacaoOut | null> {
  const r = await apiFetch(`/api/legislativo/proposicoes/${encodeURIComponent(id)}/tramitacao`, {
    token: token ?? undefined,
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as TramitacaoOut;
}

export function useTramitacao(token: string | null, id: string | null) {
  const [dados, setDados] = useState<TramitacaoOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);
  const idAtualRef = useRef(id);
  useEffect(() => {
    idAtualRef.current = id;
  }, [id]);

  if (id !== idAnterior) {
    setIdAnterior(id);
    setEstado(id ? "carregando" : "pronto");
  }

  useEffect(() => {
    if (!id) return;
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarTramitacao(token, id);
        if (!vivo) return;
        if (resultado === null) {
          setEstado("erro");
          return;
        }
        setDados(resultado);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, id]);

  // Refaz o GET sem trocar `id` (depois de tramitar). Guarda contra resposta tardia de um `id` abandonado.
  const recarregar = useCallback(async () => {
    if (!id || semCredencial(token)) return;
    const idNoMomento = id;
    try {
      const resultado = await buscarTramitacao(token, id);
      if (idAtualRef.current !== idNoMomento) return;
      if (resultado === null) {
        setEstado("erro");
        return;
      }
      setDados(resultado);
      setEstado("pronto");
    } catch {
      if (idAtualRef.current === idNoMomento) setEstado("erro");
    }
  }, [token, id]);

  if (!id) return { dados: null, estado: "pronto" as Estado, recarregar };
  if (semCredencial(token)) return { dados: null, estado: "erro" as Estado, recarregar };
  return { dados, estado, recarregar };
}
