"use client";

// Hook de detalhe do documento do Expediente (Onda B Slice 6) — GET /api/legislativo/documentos/:id.
// Mirror EXATO de use-parecer-editor.ts, com uma diferença de domínio: `id` começa NULO (a aba "Gerar
// documento" não edita um registro pré-existente — o documento só passa a existir depois que
// useGerarDocumento resolve o POST; a página guarda o `id` retornado em estado local e passa pra cá).
// `recarregar()` é usada depois de "Protocolar e numerar": o backend pode ter mudado `estado`/
// `protocolo-numero`/`protocolo-ano`, o componente refaz o GET pra ter certeza em vez de assumir.

import { useCallback, useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { DocumentoOut } from "./contrato-legislativo.gen";

type Estado = "carregando" | "pronto" | "erro";

async function buscarDocumento(token: string, id: string): Promise<DocumentoOut | null> {
  const r = await fetch(`/api/legislativo/documentos/${encodeURIComponent(id)}`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as DocumentoOut;
}

export function useDocumentoDetalhe(token: string | null, id: string | null) {
  const [dados, setDados] = useState<DocumentoOut | null>(null);
  const [estado, setEstado] = useState<Estado>(id ? "carregando" : "pronto");
  const [idAnterior, setIdAnterior] = useState(id);
  const vivoRef = useRef(true);
  // Sempre o `id` do render mais recente (ver use-parecer-editor.ts p/ o racional completo de por que
  // `recarregar` precisa deste ref em vez de fechar só sobre o `id` de quando foi chamada).
  const idAtualRef = useRef(id);
  useEffect(() => {
    idAtualRef.current = id;
  }, [id]);

  // Reset DURANTE O RENDER (não dentro do useEffect) — mesmo padrão de use-parecer-editor.ts.
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
    (async () => {
      try {
        const resultado = await buscarDocumento(token, id);
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
    if (!id || !token) return;
    const idDaChamada = id;
    try {
      const resultado = await buscarDocumento(token, id);
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
  if (!token) return { dados: null, estado: "erro" as Estado, recarregar };
  return { dados, estado, recarregar };
}
