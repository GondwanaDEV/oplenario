"use client";

// AuthContext: extrai o padrão de token dev (?token=/NEXT_PUBLIC_DEV_TOKEN) que antes vivia inline em
// sessoes/[id]/plenario/page.tsx — App Shell (FE Onda A1) precisa do MESMO guard em qualquer página
// interna nova, não só no plenário. Em produção o token via querystring É PROIBIDO (authn real = sessão
// Keycloak, carry F1.4); o guard lança antes de montar os children.
//
// CONTRATO DE COMPOSIÇÃO: nunca chame useAuth() no MESMO componente que renderiza seu próprio
// <AuthProvider> — o Provider ainda não é ancestral do próprio corpo da função que o retorna. Sempre
// aninhe useAuth() num componente filho (ex.: page.tsx separa PaginaPlenario, que só resolve os params e
// renderiza <AuthProvider>, de ConteudoPlenario, que chama useAuth() por dentro).
//
// `papeis` (Onda C1): o dev-token é JSON de claims cru (idp-dev, backend) — {"sub":...,"identidade-id":...,
// "ente-id":...}. `papeis` é um campo OPCIONAL do MESMO JSON, só para o FE guardar renderização client-side
// (ex.: o shell (vereador) bloqueia sem "vereador" em `papeis`); NÃO é autoritativo — a authz real é
// server-side (exige-papel na rota, resolvido do vínculo real no banco, backend 403 se divergir). Ausente
// ou token malformado -> [] (fail-closed: sem papel nenhum, nunca assume acesso).

import { createContext, useContext, type ReactNode } from "react";

function papeisDoToken(token: string | null): string[] {
  if (!token) return [];
  try {
    const claims: unknown = JSON.parse(token);
    const papeis = (claims as { papeis?: unknown })?.papeis;
    return Array.isArray(papeis) ? papeis.filter((p): p is string => typeof p === "string") : [];
  } catch {
    return [];
  }
}

const AuthCtx = createContext<{ token: string | null; papeis: string[] } | null>(null);

export function AuthProvider({
  children,
  tokenQuery,
}: {
  children: ReactNode;
  tokenQuery: string | null;
}) {
  const tokenInProd = process.env.NODE_ENV === "production" && !!tokenQuery;
  const token = tokenInProd
    ? null
    : tokenQuery ?? (process.env.NODE_ENV !== "production" ? process.env.NEXT_PUBLIC_DEV_TOKEN ?? null : null);

  if (tokenInProd) {
    throw new Error("token via querystring desabilitado em produção (authn = sessão Keycloak, carry F1.4).");
  }
  return <AuthCtx.Provider value={{ token, papeis: papeisDoToken(token) }}>{children}</AuthCtx.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error("useAuth fora de AuthProvider");
  return ctx;
}
