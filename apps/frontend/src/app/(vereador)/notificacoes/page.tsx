"use client";

// A aba "Avisos" do app do vereador (/notificacoes) — desde a ADR-0020 (Eixo 7) é a MESMA caixa do interno
// (src/app/caixa-da-casa.tsx): os comunicados da Casa e os avisos do sistema juntos, com os filtros Tudo / Não lidos /
// Para ciência / Do sistema. O comunicado abre DENTRO do app (/notificacoes/:id), para o vereador não perder o topo e a
// barra de abas do celular. A antiga inbox (abas derivadas da categoria, nota de dedup) foi absorvida: o filtro
// "Do sistema" é a lente dos avisos, e o comunicado que pede ciência é, ele mesmo, o acionável da caixa.

import { CaixaDaCasa } from "../../caixa-da-casa";

const hrefComunicado = (id: string) => `/notificacoes/${encodeURIComponent(id)}`;

export default function PaginaNotificacoes() {
  return <CaixaDaCasa superficie="vereador" hrefComunicado={hrefComunicado} />;
}
