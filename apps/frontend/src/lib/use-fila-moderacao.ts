"use client";

// Hook de leitura da FILA DE MODERAÇÃO de comentários (GAP docs/20 → tela): GET /api/moderacao/comentarios
// (rota INTERNA de servidor, exige-papel "secretario"). Devolve os comentários pendentes, denunciados
// primeiro (o backend já ordena — ver fila-moderacao-handler). Antes desta fatia o endpoint existia sem
// nenhuma tela de servidor.
//
// TIPO-ESPELHO (não gerado): ItemFilaModeracao espelha à mão apps/backend/.../participacao/wire/out/
// moderacao_comentario.clj (ItemFilaOut) — mesma disciplina de mesa-vista.ts. Adicionar ao manifesto
// Malli→TS de participação é follow-up.
//
// Mirror do idioma 3-estados + recarregar() de use-parecer-editor.ts (guard idAtualRef desnecessário aqui:
// a fila não é por-id; um único recarregar imperativo após moderar refaz o GET).

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export interface ItemFilaModeracao {
  id: string;
  proposicaoId: string;
  autorIdentidadeId: string;
  corpo: string;
  denunciado: boolean;
  criadoEm: string;
}

type Estado = "carregando" | "pronto" | "erro";

async function buscarFila(token: string | null): Promise<ItemFilaModeracao[] | null> {
  const r = await apiFetch("/api/moderacao/comentarios", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as ItemFilaModeracao[];
}

export function useFilaModeracao(token: string | null) {
  const [dados, setDados] = useState<ItemFilaModeracao[] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const vivoRef = useRef(true);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarFila(token);
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
  }, [token]);

  const recarregar = useCallback(async () => {
    if (semCredencial(token)) return;
    try {
      const resultado = await buscarFila(token);
      if (!vivoRef.current) return;
      if (resultado === null) {
        setEstado("erro");
        return;
      }
      setDados(resultado);
      setEstado("pronto");
    } catch {
      if (vivoRef.current) setEstado("erro");
    }
  }, [token]);

  if (semCredencial(token)) return { dados: null, estado: "erro" as Estado, recarregar };
  return { dados, estado, recarregar };
}
