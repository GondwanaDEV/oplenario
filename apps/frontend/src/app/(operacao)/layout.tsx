"use client";

// ADR-0016 — o shell do CONSOLE DO OPERADOR (supratenant). Outra esfera: a marca é "O Plenário · Operação", não
// uma Casa; a sessão é o cookie `sessao_operacao` (nunca o `sessao` de uma Casa); a navegação interna das Casas
// não aparece aqui. Mesma composição Suspense + AuthProvider + TemaProvider dos outros grupos (o `?token=` de dev
// segue pelo mesmo apiFetch).

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";
import { NavOperacao, TopoOperacao } from "./topo-operacao";
import "./operacao.css";

export default function LayoutOperacao({ children }: { children: React.ReactNode }) {
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
      <TemaProvider>
        <a className="pular" href="#conteudo">Pular para o conteúdo</a>
        <TopoOperacao />
        <main id="conteudo" className="envelope">
          <NavOperacao />
          {children}
        </main>
      </TemaProvider>
    </AuthProvider>
  );
}
