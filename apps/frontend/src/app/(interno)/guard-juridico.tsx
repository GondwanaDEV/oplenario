"use client";

// Guard da fila do parecer jurídico (ADR-0019) — mesmo padrão de GuardSecretaria/GuardAdminEnte: WRAPPER (o conteúdo, com
// os hooks dele, só monta quando autorizado) e UX-only (a authz real é o backend: leem `juridico` e `secretario`; só o
// `juridico` escreve e assina). Segura enquanto /eu não respondeu, para não piscar "Acesso restrito".

import { usePapeis } from "@/lib/auth";
import "./guard-secretaria.css";

export function AcessoRestritoJuridico() {
  return (
    <main className="acesso-restrito">
      <h1>Acesso restrito</h1>
      <p>Esta área é do jurídico e da secretaria da Casa.</p>
    </main>
  );
}

export function GuardJuridico({ children }: { children: React.ReactNode }) {
  const { papeis, estado } = usePapeis();
  if (estado === "carregando") return null;
  if (!papeis.includes("juridico") && !papeis.includes("secretario")) return <AcessoRestritoJuridico />;
  return <>{children}</>;
}
