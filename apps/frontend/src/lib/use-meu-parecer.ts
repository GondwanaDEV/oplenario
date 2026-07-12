"use client";

// Hook de detalhe do parecer p/ o VEREADOR-RELATOR (Onda C4, feature 7.3) — GET /api/meu/pareceres/:id.
// Mirror EXATO de use-parecer-editor.ts (mesmo idioma 3-estados, mesmo contrato de `recarregar()`); a
// UNICA diferenca e' a URL (borda /meu, gate 'vereador' + posse — legislativo/diplomat/http/in.clj).

import { useCallback, useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

async function buscarMeuParecer(token: string, id: string): Promise<ParecerEditorOut | null> {
  const r = await fetch(`/api/meu/pareceres/${encodeURIComponent(id)}`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as ParecerEditorOut;
}

export function useMeuParecer(token: string | null, id: string | null) {
  const [dados, setDados] = useState<ParecerEditorOut | null>(null);
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
    if (!token) return;
    let vivo = true;
    setEstado("carregando");
    buscarMeuParecer(token, id).then((d) => {
      if (!vivo || !vivoRef.current) return;
      if (idAtualRef.current !== id) return;
      setDados(d);
      setEstado(d ? "pronto" : "erro");
    });
    return () => {
      vivo = false;
    };
  }, [token, id]);

  const recarregar = useCallback(async () => {
    if (!token || !id) return;
    const d = await buscarMeuParecer(token, id);
    if (!vivoRef.current || idAtualRef.current !== id) return;
    setDados(d);
    setEstado(d ? "pronto" : "erro");
  }, [token, id]);

  return { dados, estado, recarregar };
}
