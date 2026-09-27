"use client";

// ConteudoGravacoes — o corpo da rota /gravacoes. Fino: TopoInterno + a fila (que recebe o `token` por prop para
// ser testável sem <AuthProvider>, mesmo split de conteudo-recebimentos.tsx).

import { useAuth } from "@/lib/auth";
import { TopoInterno } from "../topo";
import { FilaGravacoes } from "./fila-gravacoes";
import "./gravacoes.css";

export function ConteudoGravacoes() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Gravações" />
      <FilaGravacoes token={token} />
    </>
  );
}
