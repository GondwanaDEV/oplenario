"use client";

// Rota /assistente (interno) — o assistente da Casa (Faixa B / B.3 da Track IA): pergunta em palavras, o agente
// consulta o sistema COMO a pessoa (credencial delegada, ADR-0010) e responde citando o que consultou. Gate de papel
// via <GuardSecretaria> (a authz REAL é o backend: POST /agente/perguntas exige secretario ou vereador; a tela do
// vereador vem com o copiloto do requerimento, B.7).

import { useAuth } from "@/lib/auth";
import { GuardSecretaria } from "../guard-secretaria";
import { TopoInterno } from "../topo";
import { PainelAssistente } from "./painel-assistente";
import "./assistente.css";

export default function PaginaAssistente() {
  return (
    <GuardSecretaria>
      <ConteudoAssistente />
    </GuardSecretaria>
  );
}

function ConteudoAssistente() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Clara" />
      <PainelAssistente token={token} />
    </>
  );
}
