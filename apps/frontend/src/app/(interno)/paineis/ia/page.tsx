"use client";

// Rota /paineis/ia (interno) — a IA DA CASA (Faixa B / B.9, ADR-0014, docs/25 Eixo 8.4): o consumo do mês × o
// orçamento do plano, o estado da cota, o que cada capacidade custou e o que as pessoas fizeram com o resultado. Do
// administrador da Casa (a authz real é o backend: exige-papel "admin_ente").

import { useAuth, usePapeis } from "@/lib/auth";
import { TopoInterno } from "../../topo";
import { PainelIA } from "./painel-ia";
import "../../guard-secretaria.css";
import "./ia.css";

export default function PaginaIaDaCasa() {
  const { papeis, estado } = usePapeis();
  if (estado === "carregando") return null;
  if (!papeis.includes("admin_ente"))
    return (
      <main className="acesso-restrito">
        <h1>Acesso restrito</h1>
        <p>O painel da IA é do administrador da Casa.</p>
      </main>
    );
  return <Conteudo />;
}

function Conteudo() {
  const { token } = useAuth();
  return (
    <>
      <TopoInterno area="IA da Casa" />
      <PainelIA token={token} />
    </>
  );
}
