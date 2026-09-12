"use client";

// Onda B Slice 2 — criar uma proposicao (protocolar! imediato: uma acao primaria so', "Protocolar", spec
// §2 — nao existe estado de rascunho de backend). Apos sucesso, redireciona a /proposicoes preservando o
// ?token= dev (comToken, Task 14).

import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useCriarProposicao } from "@/lib/use-criar-proposicao";
import { comToken } from "@/lib/nav";
import { FormularioProposicao, type ValoresFormulario } from "./formulario-proposicao";
import { TopoInterno } from "../topo";

export default function PaginaCriarProposicao() {
  const { token } = useAuth();
  const { criar, estado, erro } = useCriarProposicao(token);
  const router = useRouter();

  async function aoSubmeter(valores: ValoresFormulario) {
    try {
      await criar(valores);
      router.push(comToken("/proposicoes", token));
    } catch {
      // erro ja' refletido em `erro`/`estado` pelo hook — nada mais a fazer aqui.
    }
  }

  return (
    <>
      <TopoInterno area="Proposições" />
      <main className="envelope">
        <div className="pagina-cab">
          <div>
            <span className="eyebrow">Editor de proposição</span>
            <h1>Nova proposição</h1>
          </div>
        </div>
        <FormularioProposicao
          aoSubmeter={aoSubmeter}
          enviando={estado === "enviando"}
          erro={erro}
          rotuloAcaoPrimaria="Protocolar"
        />
      </main>
    </>
  );
}
