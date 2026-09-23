"use client";

// Hook de detalhe do MODELO de documento (Onda B Slice 6, fatia de escrita — aba "Modelos") — GET
// /api/legislativo/documento-modelos/:id. Mirror EXATO de use-documento-detalhe.ts: `id` começa NULO (a
// lista de modelos não abre editor nenhum até o usuário clicar "editar" ou "novo" já ter resolvido o POST),
// `recarregar()` é usado depois de um PATCH bem-sucedido pra pegar o `lockVersion` novo.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { DocumentoModeloDetalheOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

async function buscarModelo(token: string | null, id: string): Promise<DocumentoModeloDetalheOut | null> {
  const r = await apiFetch(`/api/legislativo/documento-modelos/${encodeURIComponent(id)}`, {
    token: token ?? undefined,
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as DocumentoModeloDetalheOut;
}

export function useModeloDocumentoDetalhe(token: string | null, id: string | null) {
  const [dados, setDados] = useState<DocumentoModeloDetalheOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);
  const vivoRef = useRef(true);
  const idAtualRef = useRef(id);
  useEffect(() => {
    idAtualRef.current = id;
  }, [id]);

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
        const resultado = await buscarModelo(token, id);
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
      const resultado = await buscarModelo(token, id);
      if (!vivoRef.current) return;
      if (idAtualRef.current !== idDaChamada) return;
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
