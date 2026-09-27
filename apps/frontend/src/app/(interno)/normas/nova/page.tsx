"use client";

// Rota /normas/nova — importar o texto de uma norma da Casa (Faixa B / B.4). O texto não vale ao ser enviado: vai
// para a conferência, onde uma pessoa lê dispositivo por dispositivo e publica.

import { useAuth } from "@/lib/auth";
import { GuardSecretaria } from "../../guard-secretaria";
import { TopoInterno } from "../../topo";
import { FormImportar } from "./form-importar";
import "../normas.css";

export default function PaginaImportarNorma() {
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
      <FormImportar token={token} />
    </>
  );
}
