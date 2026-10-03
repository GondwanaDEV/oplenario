"use client";

// Rota /sessoes/[id]/audiencia (interno) — a Mesa da audiência pública (ADR-0021 A2/A3). Gate de papel via
// <GuardSecretaria> (a authz REAL é o backend: `secretario` nas escritas; o guard só evita mostrar o erro cru a quem
// não tem o papel). AuthProvider + TemaProvider vêm do layout do grupo (interno). As outras telas da sessão
// (/conduzir, /plenario, /chamada) moram fora do grupo e trazem o próprio AuthProvider; esta é a primeira sob
// (interno), porque é uma tela de secretaria com o topo de sempre.

import { useParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { GuardSecretaria } from "../../../guard-secretaria";
import { TopoInterno } from "../../../topo";
import { MesaAudiencia } from "./mesa-audiencia";

export default function PaginaMesaAudiencia() {
  return (
    <GuardSecretaria>
      <Conteudo />
    </GuardSecretaria>
  );
}

function Conteudo() {
  const { id } = useParams<{ id: string }>();
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Audiência pública" />
      <MesaAudiencia token={token} sessaoId={id} />
    </>
  );
}
