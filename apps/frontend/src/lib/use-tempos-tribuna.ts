"use client";

// Hook de IO da tela "Tempos da tribuna" (secretaria):
//   GET /api/tempos-regimentais → a tabela de tempos da Casa
//   PUT /api/tempos-regimentais → troca a tabela INTEIRA (o que não vai, sai) e devolve como ficou
// Papel `secretario` no backend. Tipos GERADOS (contrato-sessoes.gen.ts). O corpo vai em kebab
// (`tipo-fala`, `referencia-normativa`), como todo contrato de escrita da borda.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { TemposRegimentaisOut } from "./contrato-sessoes.gen";
import type { ItemTempo } from "./tempos-tribuna-vista";

type EstadoLeitura = "carregando" | "pronto" | "erro";

export type ResultadoSalvar = { ok: true } | { ok: false; erro: string };

export function useTemposTribuna(token: string | null) {
  const [itens, setItens] = useState<ItemTempo[]>([]);
  const [estado, setEstado] = useState<EstadoLeitura>("carregando");
  const vivoRef = useRef(true);
  const salvandoRef = useRef(false);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (semCredencial(token)) return; // sem credencial: o estado "erro" é derivado no render, abaixo
    let ativo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/tempos-regimentais", { token: token ?? undefined, cache: "no-store" });
        if (!ativo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const dados = camelizarChaves(await r.json()) as TemposRegimentaisOut;
        if (!ativo) return;
        setItens(dados.itens);
        setEstado("pronto");
      } catch {
        if (ativo) setEstado("erro");
      }
    })();
    return () => {
      ativo = false;
    };
  }, [token]);

  /** Grava a tabela inteira. No sucesso, `itens` passa a ser o que o servidor devolveu. */
  async function salvar(novos: ItemTempo[]): Promise<ResultadoSalvar> {
    if (semCredencial(token)) return { ok: false, erro: "Sessão sem credencial — entre de novo." };
    if (salvandoRef.current) return { ok: false, erro: "Já estamos salvando." };
    salvandoRef.current = true;
    try {
      const r = await apiFetch("/api/tempos-regimentais", {
        token: token ?? undefined,
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          itens: novos.map((i) => ({
            fase: i.fase,
            "tipo-fala": i.tipoFala,
            segundos: i.segundos,
            "referencia-normativa": i.referenciaNormativa,
          })),
        }),
      });
      if (!r.ok) return { ok: false, erro: `Não foi possível salvar os tempos (status ${r.status}).` };
      const dados = camelizarChaves(await r.json()) as TemposRegimentaisOut;
      if (vivoRef.current) setItens(dados.itens);
      return { ok: true };
    } catch {
      return { ok: false, erro: "Não foi possível salvar os tempos: falha de rede. Tente de novo." };
    } finally {
      salvandoRef.current = false;
    }
  }

  return {
    itens,
    estado: semCredencial(token) ? ("erro" as EstadoLeitura) : estado,
    salvar,
  };
}
