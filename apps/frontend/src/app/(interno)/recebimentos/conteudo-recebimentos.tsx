"use client";

// ConteudoRecebimentos — o corpo da rota /recebimentos. Fino: TopoInterno + a fila (que recebe o `token` por
// prop para ser testável sem <AuthProvider>, mesmo split de conteudo-moderacao.tsx).

import { useAuth } from "@/lib/auth";
import { TopoInterno } from "../topo";
import { FilaRecebimentos } from "./fila-recebimentos";
import "./recebimentos.css";

export function ConteudoRecebimentos() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Recebimentos" />
      <FilaRecebimentos token={token} />
    </>
  );
}
