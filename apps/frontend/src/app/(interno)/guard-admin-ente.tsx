"use client";

// Guard da área do administrador da Casa (ADR-0005) — mesmo padrão de GuardSecretaria: WRAPPER (o conteúdo, com
// os hooks dele, só monta quando autorizado) e UX-only (a authz real é o backend: `exige-papel "admin_ente"` nas
// escritas de conceder acesso). Segura enquanto /eu não respondeu, para não piscar "Acesso restrito".

import { usePapeis } from "@/lib/auth";
import "./guard-secretaria.css";

export function AcessoRestritoAdminEnte() {
  return (
    <main className="acesso-restrito">
      <h1>Acesso restrito</h1>
      <p>Esta área é do administrador da Casa.</p>
    </main>
  );
}

export function GuardAdminEnte({ children }: { children: React.ReactNode }) {
  const { papeis, estado } = usePapeis();
  if (estado === "carregando") return null;
  if (!papeis.includes("admin_ente")) return <AcessoRestritoAdminEnte />;
  return <>{children}</>;
}
