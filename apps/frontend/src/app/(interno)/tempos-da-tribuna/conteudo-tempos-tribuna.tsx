"use client";

// ConteudoTemposTribuna — o corpo da rota /tempos-da-tribuna. Fino: TopoInterno + o formulário (que recebe o
// `token` por prop para ser testável sem <AuthProvider>, mesmo split de conteudo-agendar-sessao.tsx).

import { useAuth } from "@/lib/auth";
import { TopoInterno } from "../topo";
import { FormTemposTribuna } from "./form-tempos-tribuna";
import "./tempos-tribuna.css";

export function ConteudoTemposTribuna() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Tempos da tribuna" />
      <FormTemposTribuna token={token} />
    </>
  );
}
