"use client";

// Onda B Slice 3 — a Ficha da Matéria (rota /ficha-materia/:id). Fina: só resolve `params` (Next.js 15+
// promise-params) e delega pro conteúdo real (ver ../conteudo-ficha-materia.tsx — mesmo split documentado
// em sessoes/[id]/plenario/page.tsx/layout.tsx, e pelo mesmo motivo: `use()` isolado num wrapper fino
// mantém a lógica de fato testável sem depender do Router real do Next.js).

import { use } from "react";
import { ConteudoFichaMateria } from "../conteudo-ficha-materia";

export default function PaginaFichaMateria({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <ConteudoFichaMateria id={id} />;
}
