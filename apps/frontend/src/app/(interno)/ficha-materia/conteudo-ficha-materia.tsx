"use client";

// ConteudoFichaMateria — o corpo real da rota /ficha-materia/:id (Onda B Slice 3), separado do
// resolvedor de `params` (ver [id]/page.tsx) pelo MESMO motivo já documentado em
// sessoes/[id]/plenario/page.tsx (PaginaPlenario/ConteudoPlenario — layout.tsx comenta esse split): manter
// a lógica de fato num componente que recebe `id: string` puro, testável isoladamente sem depender de
// `use()` + Suspense (que exige o Router real do Next.js como fronteira — um `render()` direto de teste,
// sem esse Router, nunca resolve a Promise de `params`, mesmo já fulfilled — reproduzido isolado; carry
// documentado no relatório da fatia).
//
// Assembly de useFichaMateria (fetch autenticado, GET /api/legislativo/proposicoes/:id/ficha, via rewrite
// catch-all de next.config.ts — mesmo caminho de use-proposicao-detalhe.ts, sem route.ts próprio) +
// FichaCabecalho (header + azulejo herói) + FichaMateriaTabs (corpo, abas) + os 3 cards do rail.
// Loading/error mirror EXATO de editor-proposicao/[id]/page.tsx (mesmas classes .envelope/.tela-estado,
// mesmo `role="status"`).

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useFichaMateria } from "@/lib/use-ficha-materia";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { derivarDadosMateria } from "@/lib/ficha-materia-vista";
import { comToken } from "@/lib/nav";
import { TopoInterno } from "../topo";
import { FichaCabecalho } from "./ficha-cabecalho";
import { FichaMateriaTabs } from "./ficha-materia-tabs";
import { DadosMateriaCard } from "./dados-materia-card";
import { IdentidadeLexmlCard } from "./identidade-lexml-card";
import { AcoesCard } from "./acoes-card";
import "./ficha-materia.css";

export function ConteudoFichaMateria({ id }: { id: string }) {
  const { token } = useAuth();
  const { dados: ficha, estado } = useFichaMateria(token, id);

  if (estado === "carregando") {
    return (
      <>
        <TopoInterno area="Proposições" />
        <main className="envelope">
          <p role="status">Carregando…</p>
        </main>
      </>
    );
  }

  if (estado === "erro" || !ficha) {
    return (
      <>
        <TopoInterno area="Proposições" />
        <main className="tela-estado">
          <h1>Não foi possível carregar esta ficha</h1>
        </main>
      </>
    );
  }

  const numero = formatarNumeroProposicao(
    ficha.proposicao.tipo,
    ficha.proposicao.sequencial,
    ficha.proposicao.ano,
  );
  const dadosMateria = derivarDadosMateria(ficha);

  return (
    <>
      <TopoInterno area="Proposições" />
      <main className="envelope">
        <nav className="trilha" aria-label="Trilha de navegação">
          <Link href={comToken("/proposicoes", token)}>Proposições</Link>
          <span className="sep" aria-hidden="true">/</span>
          <b>{numero}</b>
        </nav>

        <FichaCabecalho proposicao={ficha.proposicao} />

        <div className="corpo">
          <FichaMateriaTabs ficha={ficha} token={token} />
          <aside className="rail" aria-labelledby="rail-titulo">
            <h2 id="rail-titulo" className="sr-only">
              Dados e ações da matéria
            </h2>
            <DadosMateriaCard dados={dadosMateria} />
            <IdentidadeLexmlCard urnLex={ficha.proposicao.urnLex} />
            <AcoesCard proposicao={ficha.proposicao} token={token} />
          </aside>
        </div>
      </main>
    </>
  );
}
