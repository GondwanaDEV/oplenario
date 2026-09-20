"use client";

// Guard de papel REUTILIZAVEL para as telas (interno) restritas a 'secretario'. Extraido do guard inline
// de pauta-convocacao (que era "a primeira rota (interno) restrita a um papel"): agora que ha' varias
// (expediente/protocolo/recebidos, cadastro de vereadores), o padrao vive aqui uma vez so'.
//
// Por que WRAPPER e nao check inline na pagina: a pagina tem seus proprios hooks (useAuth/useState/...).
// Um early-return no meio deles viola a regra de hooks. Aqui o guard so' chama usePapeis; o CONTEUDO
// (com todos os outros hooks) so' e' montado quando autorizado — mesma estrutura Pagina/Conteudo da pauta.
//
// UX-only: a authz REAL e' o backend (`exige-papel "secretario"` -> 403). Este guard evita que um papel
// sem acesso veja o erro generico "Nao foi possivel carregar" (o fetch 403 falhando) em vez de uma
// explicacao limpa. Achado docs/20 (jornadas T1/T2): so' a pauta gateava; as irmas mostravam erro cru.

import { usePapeis } from "@/lib/auth";
import "./guard-secretaria.css";

export function AcessoRestritoSecretaria() {
  return (
    <main className="acesso-restrito">
      <h1>Acesso restrito</h1>
      <p>Esta área é exclusiva da secretaria legislativa.</p>
    </main>
  );
}

export function GuardSecretaria({ children }: { children: React.ReactNode }) {
  const { papeis, estado } = usePapeis();
  // Segura enquanto /eu nao respondeu (evita piscar "Acesso restrito" antes da resposta — mesmo racional
  // do guard da pauta e de GuardVereador).
  if (estado === "carregando") return null;
  if (!papeis.includes("secretario")) return <AcessoRestritoSecretaria />;
  return <>{children}</>;
}
