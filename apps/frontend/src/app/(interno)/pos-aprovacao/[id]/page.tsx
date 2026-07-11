"use client";

// Onda B Slice 7 — a página de Pós-aprovação (rota /pos-aprovacao/:id). Fina: só resolve `params` (Next.js
// 15+ promise-params) e delega pro conteúdo real (ver ../conteudo-pos-aprovacao.tsx — mesmo split
// documentado em ficha-materia/[id]/page.tsx/conteudo-ficha-materia.tsx, e pelo mesmo motivo: `use()`
// isolado num wrapper fino mantém a lógica de fato testável sem depender do Router real do Next.js).

import { use } from "react";
import { ConteudoPosAprovacao } from "../conteudo-pos-aprovacao";

export default function PaginaPosAprovacao({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <ConteudoPosAprovacao id={id} />;
}
