"use client";

// Rota /conferencias (interno) — a conferência das proposições (Faixa B / B.8, ADR-0013). A cada proposição
// protocolada, o agente institucional da Casa (sem pessoa por trás, ligado pelo administrador) lê o texto e os
// dispositivos da LOM e do Regimento e deixa aqui uma nota técnica em RASCUNHO, com citações. A secretaria lê,
// aproveita ou descarta — a IA nunca decide. Gate de papel via <GuardSecretaria> (a authz real é o backend).

import { useAuth } from "@/lib/auth";
import { GuardSecretaria } from "../guard-secretaria";
import { TopoInterno } from "../topo";
import { FilaConferencias } from "./fila-conferencias";
import "./conferencias.css";

export default function PaginaConferencias() {
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
      <TopoInterno area="Conferências" />
      <FilaConferencias token={token} />
    </>
  );
}
