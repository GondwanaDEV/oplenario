"use client";

// Hook de GET /api/meu/identidade (fatia "demo-tres-consertos" #1) — o "quem sou eu" de exibição: nome +
// papéis do ator autenticado, pra `TopoInterno` parar de mostrar um ator FIXO ("Sérgio Lopes"/"Presidente
// da Mesa") pra QUALQUER persona logada. Mirror de use-meu-painel.ts: sempre busca via `apiFetch` nos DOIS
// modos (dev e real) — o token dev também é aceito pelo backend (idp-dev), e é o padrão dominante entre os
// hooks `use-meu-*` (a exceção é `useEu`/usePapeis, que evita o round-trip em dev por já ter os papéis
// síncronos no token — aqui o `nome` não está no token, então não há atalho equivalente).
//
// `semCredencial` (modo.ts) é o MESMO guard fail-closed de useMeuPainel: aborta só no modo dev sem token;
// no modo real (cookie httpOnly) nunca aborta — o 401 do backend é quem decide.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type MeuIdentidadeOut = { nome: string; papeis: string[] };

type Estado = "carregando" | "pronto" | "erro";

// Fail-closed em corpo malformado (mesmo racional de useEu, use-eu.ts): um `global.fetch` mockado por OUTRO
// teste de página (que não sabe que TopoInterno agora busca /api/meu/identidade por conta própria) pode
// devolver qualquer JSON — sem esta validação, um corpo sem `:nome` vazava como `dados.nome === undefined`
// até `nome.split(...)` em topo.tsx, derrubando a página inteira. Nunca assume o shape; corpo que não bate
// vira `null` (estado "erro"), nunca um ator meio-preenchido.
function comoMeuIdentidade(d: unknown): MeuIdentidadeOut | null {
  if (d === null || typeof d !== "object") return null;
  const { nome, papeis } = d as Record<string, unknown>;
  if (typeof nome !== "string") return null;
  if (!Array.isArray(papeis) || !papeis.every((p) => typeof p === "string")) return null;
  return { nome, papeis };
}

async function buscarMeuIdentidade(token: string | null): Promise<MeuIdentidadeOut | null> {
  const r = await apiFetch("/api/meu/identidade", { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return comoMeuIdentidade(camelizarChaves(await r.json()));
}

export function useMeuIdentidade(token: string | null): { dados: MeuIdentidadeOut | null; estado: Estado } {
  const [dados, setDados] = useState<MeuIdentidadeOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return; // caso de erro sem token é derivado no retorno, sem setState síncrono no effect
    let vivo = true;
    (async () => {
      try {
        const resultado = await buscarMeuIdentidade(token);
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

  if (semCredencial(token)) return { dados: null, estado: "erro" };
  return { dados, estado };
}
