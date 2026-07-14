"use client";

// Hook de detalhe do editor de parecer (Onda B Slice 5) — GET /api/legislativo/pareceres/:id. Mirror do
// idioma 3-estados de use-proposicao-detalhe.ts (reset em render-time ao trocar `id`, guard `vivo` contra
// unmount, fetch INLINE dentro do useEffect — `react-hooks/set-state-in-effect` recusa uma função
// referenciada via deps que chama setState por dentro, então o efeito de carga inicial mantém o corpo
// async local, como o mirror) + `recarregar()` extra: depois de "Emitir parecer" com sucesso o backend
// pode ou não ter transicionado o estado — o componente refaz o GET pra saber com certeza, sem trocar
// `id` (spec §"Depois de Emitir"). `buscarParecer` (módulo, sem setState) é a ÚNICA fonte do fetch, usada
// pelos dois caminhos (efeito de carga + `recarregar` imperativo) — não duplica a chamada de rede.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

// null = 404/erro HTTP (não distingue do "não encontrado" — mesmo contrato dos GETs irmãos).
async function buscarParecer(token: string | null, id: string): Promise<ParecerEditorOut | null> {
  const r = await apiFetch(`/api/legislativo/pareceres/${encodeURIComponent(id)}`, {
    token: token ?? undefined,
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as ParecerEditorOut;
}

export function useParecerEditor(token: string | null, id: string | null) {
  const [dados, setDados] = useState<ParecerEditorOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);
  const vivoRef = useRef(true);
  // Sempre o `id` do render mais recente — lido de dentro de `recarregar` (que roda depois de um await, por
  // fora do ciclo de render) pra detectar se o `id` mudou enquanto o fetch estava em voo. `recarregar` é
  // `useCallback`-fechado sobre o `id` de QUANDO FOI CHAMADO; sem este ref ela não tem como saber que ficou
  // stale (ex.: emitir() dispara recarregar() pro parecer P1, o usuário navega pra P2 antes dela resolver —
  // sem o guard, a resposta tardia de P1 sobrescreveria os dados de P2 já carregados). Atualizado em efeito
  // (não durante o render — `react-hooks/refs` recusa escrever `ref.current` no corpo do componente).
  const idAtualRef = useRef(id);
  useEffect(() => {
    idAtualRef.current = id;
  }, [id]);

  // Reset DURANTE O RENDER (não dentro do useEffect) — mesmo padrão de use-proposicao-detalhe.ts.
  if (id !== idAnterior) {
    setIdAnterior(id);
    setEstado(id ? "carregando" : "pronto");
  }

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!id) return;
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarParecer(token, id);
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
    const idDaChamada = id;
    try {
      const resultado = await buscarParecer(token, id);
      if (!vivoRef.current) return;
      if (idAtualRef.current !== idDaChamada) return; // `id` mudou enquanto o fetch estava em voo — descarta.
      if (resultado === null) {
        setEstado("erro");
        return;
      }
      setDados(resultado);
      setEstado("pronto");
    } catch {
      if (vivoRef.current && idAtualRef.current === idDaChamada) setEstado("erro");
    }
  }, [token, id]);

  if (!id) return { dados: null, estado: "pronto" as Estado, recarregar };
  if (semCredencial(token)) return { dados: null, estado: "erro" as Estado, recarregar };
  return { dados, estado, recarregar };
}
