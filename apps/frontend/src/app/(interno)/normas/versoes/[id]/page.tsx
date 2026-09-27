"use client";

// Rota /normas/versoes/[id] — a CONFERÊNCIA de uma versão importada (Faixa B / B.4, Eixo 7.3): a pessoa lê o texto
// como o sistema o separou, vê os pontos que o parser não entendeu e publica (vira a versão que vale) ou descarta.
// Também serve para ler uma versão já vigente ou antiga.

import { useParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { GuardSecretaria } from "../../../guard-secretaria";
import { TopoInterno } from "../../../topo";
import { ConferenciaVersao } from "./conferencia-versao";
import "../../normas.css";

export default function PaginaVersao() {
  return (
    <GuardSecretaria>
      <Conteudo />
    </GuardSecretaria>
  );
}

function Conteudo() {
  const { token } = useAuth();
  const { id } = useParams<{ id: string }>();
  return (
    <>
      <TopoInterno area="Normas" />
      <ConferenciaVersao token={token} id={id} />
    </>
  );
}
