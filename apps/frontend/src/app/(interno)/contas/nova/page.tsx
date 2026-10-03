"use client";

// Rota /contas/nova (interno) — registrar uma prestação de contas (ADR-0021 B1). Só a secretaria (GuardSecretaria; o
// backend exige o papel). Registrada, a tela leva à ficha — que diz, para as contas de governo, que o PDL foi
// protocolado (`?registrada=1`).

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { GuardSecretaria } from "../../guard-secretaria";
import { TopoInterno } from "../../topo";
import { FormNovaPrestacao } from "./form-nova-prestacao";
import "../contas.css";

export default function PaginaNovaPrestacao() {
  return (
    <GuardSecretaria>
      <Conteudo />
    </GuardSecretaria>
  );
}

function Conteudo() {
  const { token } = useAuth();
  const router = useRouter();
  return (
    <>
      <TopoInterno area="Contas" />
      <main className="envelope cts">
        <Link className="cts-voltar" href={comToken("/contas", token)}>
          ← Prestações de contas
        </Link>
        <header className="cts-cabeca">
          <h1>Registrar prestação de contas</h1>
          <p className="cts-sub">
            Registre a prestação quando o parecer prévio do Tribunal de Contas chegar à Câmara. Os prazos de defesa e de
            julgamento contam a partir daqui, pelas regras da Casa.
          </p>
        </header>
        <section className="cts-painel" aria-label="Dados da prestação">
          <FormNovaPrestacao token={token} onRegistrada={(p) => router.push(comToken(`/contas/${encodeURIComponent(p.id)}?registrada=1`, token))} />
        </section>
      </main>
    </>
  );
}
