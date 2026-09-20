"use client";

// ConteudoModeracao — o corpo da rota /moderacao (GAP docs/20 → tela de servidor). Fino: TopoInterno + a
// fila (FilaModeracao, que recebe o `token` por prop para ser testável sem <AuthProvider> — mesmo split de
// conteudo-ficha-materia.tsx / AcoesTramitacao).

import { useAuth } from "@/lib/auth";
import { TopoInterno } from "../topo";
import { FilaModeracao } from "./fila-moderacao";
import "./moderacao.css";

export function ConteudoModeracao() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Moderação" />
      <FilaModeracao token={token} />
    </>
  );
}
