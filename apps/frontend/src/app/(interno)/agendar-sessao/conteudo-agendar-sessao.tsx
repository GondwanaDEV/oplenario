"use client";

// ConteudoAgendarSessao — o corpo da rota /agendar-sessao. Fino: TopoInterno + o formulário (que recebe o
// `token` por prop para ser testável sem <AuthProvider>, mesmo split de conteudo-moderacao.tsx).

import { useAuth } from "@/lib/auth";
import { TopoInterno } from "../topo";
import { FormAgendarSessao } from "./form-agendar-sessao";
import "./agendar-sessao.css";

export function ConteudoAgendarSessao() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Agendar sessão" />
      <FormAgendarSessao token={token} />
    </>
  );
}
