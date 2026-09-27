"use client";

// /vereador/assistente — o assistente da Casa dentro do app do vereador (Faixa B / B.6). O mesmo painel da
// secretaria; o que muda é o que ele pode fazer: além de consultar, PROPÕE atos do próprio vereador (o
// requerimento), que ele lê e assina na tela da proposta (ADR-0012). A authz real é o backend.

import { useAuth } from "@/lib/auth";
import { CONFIANCA_PROPOE, PainelAssistente, SUGESTOES_VEREADOR } from "../../../(interno)/assistente/painel-assistente";
import "../../../(interno)/assistente/assistente.css";

export default function PaginaAssistenteVereador() {
  const { token } = useAuth();
  return <PainelAssistente token={token} publico="vereador" sugestoes={SUGESTOES_VEREADOR} confianca={CONFIANCA_PROPOE} />;
}
