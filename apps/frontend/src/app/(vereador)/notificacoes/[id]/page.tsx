"use client";

// Rota /notificacoes/:id (app do vereador) — o comunicado aberto DENTRO do app (ADR-0020): o vereador lê, dá ciência e
// volta à aba "Avisos" sem sair do topo e da barra de abas do celular. A tela é a mesma do interno
// (src/app/comunicado-detalhe.tsx); o shell do vereador (GuardVereador) já está no layout.

import { useParams } from "next/navigation";
import { ComunicadoDetalhe } from "../../../comunicado-detalhe";

const hrefDoComunicado = (id: string) => `/notificacoes/${encodeURIComponent(id)}`;

export default function PaginaComunicadoDoVereador() {
  const { id } = useParams<{ id: string }>();
  return <ComunicadoDetalhe id={id ?? null} voltar={{ href: "/notificacoes", rotulo: "Avisos" }} hrefDoComunicado={hrefDoComunicado} />;
}
