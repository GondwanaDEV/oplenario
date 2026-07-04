"use client";

// apps/frontend/src/app/(interno)/layout.tsx
// Layout raiz do App Shell interno (FE Onda A1) — qualquer página sob app/(interno)/ ganha AuthProvider +
// TemaProvider automaticamente. O grupo de rotas (interno) não aparece na URL (convenção Next.js App
// Router); /sessoes/[id]/plenario fica FORA deste grupo por enquanto (migrá-la é carry — spec §7).
//
// DESVIO do draft do brief: o draft recebia `searchParams` como prop do layout. Testado de verdade
// (npm run build) e contra a doc oficial do Next.js (App Router): "Layouts, which are Server Components,
// do not receive the searchParams prop" — o build COMPILA (não há erro de tipo, pois nenhuma page.tsx
// ainda existia sob (interno) para expor a discrepância), mas em runtime `searchParams` seria sempre
// `undefined`, quebrando silenciosamente o token via querystring. Fallback aplicado: o layout vira Client
// Component e lê `useSearchParams()` — o MESMO hook usado em sessoes/[id]/plenario/page.tsx — em vez de
// depender de uma prop que o Router nunca preenche para layouts. Isso também é o que permite a Task B9
// (`page.tsx` da Mesa) chamar `useAuth()` diretamente sem seu próprio <AuthProvider> local: o Provider já
// é ancestral, injetado aqui.
//
// `useSearchParams()` precisa de um limite <Suspense> — confirmado com `npm run build` real contra uma
// rota de smoke-test estática sob este grupo, que quebrava com "useSearchParams() should be wrapped in a
// suspense boundary" (doc oficial). Por isso a leitura mora num componente FILHO (`LeitorToken`), nunca no
// mesmo componente que declara o boundary — o fallback (`null`) só aparece durante o passe estático; em
// runtime (esta área é toda autenticada, nunca cacheada) o conteúdo real chega no primeiro render.

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

export default function LayoutInterno({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={null}>
      <LeitorToken>{children}</LeitorToken>
    </Suspense>
  );
}

function LeitorToken({ children }: { children: React.ReactNode }) {
  const searchParams = useSearchParams();
  return (
    <AuthProvider tokenQuery={searchParams.get("token")}>
      <TemaProvider>{children}</TemaProvider>
    </AuthProvider>
  );
}
