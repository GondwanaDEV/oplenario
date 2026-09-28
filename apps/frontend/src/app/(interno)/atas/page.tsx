"use client";

// Rota /atas (interno) — o livro de atas da Casa (Onda E, `livro-atas`). Sem guard de papel: o servidor decide a
// visibilidade por linha (a ata de sessão secreta só aparece para a secretaria); vereador e secretaria leem o
// mesmo livro. A ata aberta vem da URL (`?sessao=` e `&versao=`), para o link poder ser compartilhado.

import { useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { LivroAtas } from "@/app/livro-atas";
import { TopoInterno } from "../topo";

export default function PaginaLivroAtas() {
  const { token } = useAuth();
  const params = useSearchParams();
  const versao = Number(params.get("versao"));
  return (
    <>
      <TopoInterno area="Atas" />
      <main id="conteudo" className="envelope">
        <LivroAtas
          fonte={{ tipo: "interno", token }}
          sessao={params.get("sessao")}
          versao={Number.isInteger(versao) && versao > 0 ? versao : null}
        />
      </main>
    </>
  );
}
