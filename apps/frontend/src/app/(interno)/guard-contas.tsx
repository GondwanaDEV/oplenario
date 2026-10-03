"use client";

// Guard das CONTAS (ADR-0021 Parte B) — mesmo padrão de GuardJuridico: WRAPPER (o conteúdo, com os hooks dele, só monta
// quando autorizado) e UX-only (a authz real é o backend: leem `secretario`, `vereador` e `juridico`; só a secretaria
// escreve). Segura enquanto /eu não respondeu, para não piscar "Acesso restrito".

import { usePapeis } from "@/lib/auth";
import "./guard-secretaria.css";

export const PAPEIS_DAS_CONTAS = ["secretario", "vereador", "juridico"] as const;

export function AcessoRestritoContas() {
  return (
    <main className="acesso-restrito">
      <h1>Acesso restrito</h1>
      <p>As contas são acompanhadas pela secretaria, pelos vereadores e pelo jurídico da Casa.</p>
    </main>
  );
}

export function GuardContas({ children }: { children: React.ReactNode }) {
  const { papeis, estado } = usePapeis();
  if (estado === "carregando") return null;
  if (!PAPEIS_DAS_CONTAS.some((p) => papeis.includes(p))) return <AcessoRestritoContas />;
  return <>{children}</>;
}
