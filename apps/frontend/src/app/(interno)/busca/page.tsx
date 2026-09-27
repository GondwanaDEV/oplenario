"use client";

// Rota /busca (interno) — a busca intra-câmara (Faixa A / A.5 da Track IA): proposições e o que foi dito em plenário,
// por sentido e por palavra. Gate de papel via <GuardSecretaria> (a authz REAL é o backend: exige-papel "secretario"
// em GET /busca, a mesma trava da leitura da transcrição).

import { useAuth } from "@/lib/auth";
import { GuardSecretaria } from "../guard-secretaria";
import { TopoInterno } from "../topo";
import { PainelBusca } from "./painel-busca";
import "./busca.css";

export default function PaginaBusca() {
  return (
    <GuardSecretaria>
      <ConteudoBusca />
    </GuardSecretaria>
  );
}

function ConteudoBusca() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="Busca" />
      <PainelBusca token={token} />
    </>
  );
}
