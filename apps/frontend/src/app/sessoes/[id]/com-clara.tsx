"use client";

// A Clara nas telas da sessão que a secretaria opera (ADR-0024, fatia 5): o Comando da Mesa, a chamada, a revisão
// da ata e a transcrição. Elas ficam fora do grupo (interno), e cada página monta o próprio AuthProvider com o token
// da query; este envoltório é esse AuthProvider, agora com a moldura da Clara por dentro. Uma árvore só: a página e a
// Clara leem o MESMO provider, nunca dois tokens. O tema vem do layout raiz.
//
// `discreta`: o botão recolhido é só o glifo e, no computador, o conteúdo deixa livre a faixa dele (clara.css) — a Mesa
// opera estas telas ao vivo, e nenhum comando pode passar por baixo do botão.
//
// O telão (`/plenario`), a TV (`/tv`) e a folha (`/folha`) NÃO usam este envoltório, de propósito: os dois primeiros
// são projetados ao público e a folha é para imprimir. Lá não há Clara nem botão.

import { useSearchParams } from "next/navigation";
import { AuthProvider } from "@/lib/auth";
import { Faisca } from "@/app/(interno)/clara/clara";
import { MolduraDaClara, useAbrirClara } from "@/app/(interno)/clara/moldura-da-clara";

export function SessaoComClara({ children }: { children: React.ReactNode }) {
  const search = useSearchParams();
  return (
    <AuthProvider tokenQuery={search.get("token")}>
      <MolduraDaClara discreta>{children}</MolduraDaClara>
    </AuthProvider>
  );
}

/** A entrada da Clara no cabeçalho de uma tela da sessão, só no celular (no computador vale o botão flutuante): o
 *  Comando da Mesa é de largura inteira no telefone, e um botão flutuando sobre ele cobriria um comando ao rolar.
 *  Com ela na página, o botão flutuante some no celular (conduzir.css). Sem a Clara na tela, nada. */
export function BotaoClaraNoTopo() {
  const clara = useAbrirClara();
  if (!clara.disponivel) return null;
  return (
    <button
      className="tema-btn clara-no-topo"
      type="button"
      aria-controls={clara.painelId}
      aria-expanded={clara.aberta}
      aria-label="Pergunte à Clara"
      title="Pergunte à Clara (Ctrl + /)"
      onClick={(e) => clara.abrir("aberto", e.currentTarget)}
    >
      <Faisca tamanho={16} />
      <span className="tema-rotulo">Clara</span>
    </button>
  );
}
