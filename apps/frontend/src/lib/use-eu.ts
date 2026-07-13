"use client";

// Hook leve p/ GET /api/eu (Onda D Slice 2, Task 16) — resolve os papéis do ator autenticado no MODO REAL
// (cookie httpOnly `sessao`; token=null). No modo dev não há sessão por cookie — os papéis vêm do dev-token
// (papeisDoToken em auth.tsx); é usePapeis (auth.tsx) quem decide qual fonte usar, este hook aqui SÓ busca
// quando modoReal()===true. O rewrite /api/* do Next encaminha cookie+Authorization sozinho — nenhum
// route.ts dedicado é necessário para /api/eu.
//
// Regra de hooks: useState/useEffect são chamados SEMPRE (incondicional); o early-return de modo dev fica
// DENTRO do effect, nunca condiciona a chamada do próprio hook — senão viola as Rules of Hooks se o modo
// mudasse entre renders (não muda em runtime, mas o padrão protege o componente mesmo assim).
//
// Fail-closed (mesmo racional de papeisDoToken): erro de rede, !ok, ou corpo malformado -> papeis [], nunca
// assume acesso.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { modoReal } from "./modo";

type Estado = "carregando" | "pronto" | "erro";

export function useEu(token: string | null): { papeis: string[] | null; estado: Estado } {
  const [papeis, setPapeis] = useState<string[] | null>(null);
  const [estado, setEstado] = useState<Estado>(modoReal() ? "carregando" : "pronto");

  useEffect(() => {
    if (!modoReal()) return; // dev: usePapeis usa o token, não /eu
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/eu", { token: token ?? undefined, cache: "no-store" });
        if (!r.ok) throw new Error(`eu ${r.status}`);
        const d = (await r.json()) as { ator?: { papeis?: unknown } };
        const ps = Array.isArray(d?.ator?.papeis)
          ? (d.ator!.papeis as unknown[]).filter((p): p is string => typeof p === "string")
          : [];
        if (vivo) {
          setPapeis(ps);
          setEstado("pronto");
        }
      } catch {
        if (vivo) {
          setPapeis([]);
          setEstado("erro"); // fail-closed: sem papel nenhum
        }
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  return { papeis, estado };
}
