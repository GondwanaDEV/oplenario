"use client";

// Hook de ficha da matéria (Onda B Slice 3) — GET /api/legislativo/proposicoes/:id/ficha. Mirror EXATO de
// use-proposicao-detalhe.ts (mesmo idioma 3-estados + reset em render-time ao trocar `id`, exigido por
// eslint-plugin-react-hooks v7 `set-state-in-effect` em vez de setState síncrono dentro do useEffect).
//
// `recarregar()` (mesmo padrão de use-parecer-editor.ts): depois de uma tramitação bem-sucedida no painel
// de atos (ver acoes-tramitacao.tsx), a página refaz o GET SEM trocar `id` para o cabeçalho/histórico
// refletirem o novo estado. `buscarFichaMateria` (sem setState) é a fonte única do fetch — usada pelo
// efeito de carga e pelo `recarregar` imperativo, com guard `idAtualRef` contra resposta tardia de um `id`
// já abandonado.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { FichaMateriaOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

async function buscarFichaMateria(token: string | null, id: string): Promise<FichaMateriaOut | null> {
  const r = await apiFetch(`/api/legislativo/proposicoes/${encodeURIComponent(id)}/ficha`, {
    token: token ?? undefined,
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as FichaMateriaOut;
}

export function useFichaMateria(token: string | null, id: string | null) {
  const [dados, setDados] = useState<FichaMateriaOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);
  const idAtualRef = useRef(id);
  useEffect(() => {
    idAtualRef.current = id;
  }, [id]);

  // Reset DURANTE O RENDER (não dentro do useEffect) — mesmo padrão de use-proposicao-detalhe.ts: sem
  // isto, os dados/estado "pronto" do id anterior ficariam visíveis até o fetch do novo id resolver.
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
        const resultado = await buscarFichaMateria(token, id);
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

  const recarregar = useCallback(async () => {
    if (!id || semCredencial(token)) return;
    const idNoMomento = id;
    try {
      const resultado = await buscarFichaMateria(token, id);
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
