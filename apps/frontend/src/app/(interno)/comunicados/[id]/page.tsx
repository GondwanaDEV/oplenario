"use client";

// Rota /comunicados/:id (interno) — um comunicado (ADR-0020). A tela é compartilhada com o app do vereador
// (src/app/comunicado-detalhe.tsx). Sem guard de papel: quem pode ver é o backend (destinatário, remetente, secretaria,
// administração); a recusa vira frase. O "voltar" respeita de onde se veio: a caixa, ou os enviados (`?de=enviados`).

import { useParams, useSearchParams } from "next/navigation";
import { ComunicadoDetalhe } from "../../../comunicado-detalhe";
import { TopoInterno } from "../../topo";

const hrefDoComunicado = (id: string) => `/comunicados/${encodeURIComponent(id)}`;

export default function PaginaComunicado() {
  const { id } = useParams<{ id: string }>();
  const de = useSearchParams().get("de");
  const voltar = de === "enviados" ? { href: "/comunicados/enviados", rotulo: "Enviados" } : { href: "/caixa", rotulo: "Caixa" };
  return (
    <>
      <TopoInterno area="Caixa" />
      <main className="envelope com-pagina">
        <ComunicadoDetalhe id={id ?? null} voltar={voltar} hrefDoComunicado={hrefDoComunicado} />
      </main>
    </>
  );
}
