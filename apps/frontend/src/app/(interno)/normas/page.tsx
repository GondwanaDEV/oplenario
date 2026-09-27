"use client";

// Rota /normas (interno) — as normas de referência da Casa (Faixa B / B.4, ADR-0011): a LOM, o Regimento Interno e
// as leis que o assistente vai consultar, cada uma conferida por uma pessoa antes de valer. Gate de papel via
// <GuardSecretaria> (a authz real é o backend: exige-papel "secretario").

import { useAuth } from "@/lib/auth";
import { GuardSecretaria } from "../guard-secretaria";
import { TopoInterno } from "../topo";
import { ListaNormas } from "./lista-normas";
import "./normas.css";

export default function PaginaNormas() {
  return (
    <GuardSecretaria>
      <Conteudo />
    </GuardSecretaria>
  );
}

function Conteudo() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Normas" />
      <ListaNormas token={token} />
    </>
  );
}
