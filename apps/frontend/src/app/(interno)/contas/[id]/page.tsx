"use client";

// Rota /contas/:id (interno) — a ficha de uma prestação de contas (ADR-0021 Parte B). Secretaria, vereador e jurídico
// leem; só a secretaria escreve (notificação, documentos, defesa, processo no TCE, levar à pauta). A ficha em si mora
// em ficha-prestacao.tsx; aqui só o guard, a carga e o "voltar". Cada escrita recarrega a prestação SEM piscar — o estado
// derivado, o prazo congelado e o `pautavel` são do servidor, nunca deduzidos aqui.

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { useAuth, usePapeis } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { usePrestacao } from "@/lib/use-contas";
import { GuardContas } from "../../guard-contas";
import { TopoInterno } from "../../topo";
import { FichaPrestacao } from "./ficha-prestacao";
import "../contas.css";

export default function PaginaPrestacao() {
  return (
    <GuardContas>
      <Conteudo />
    </GuardContas>
  );
}

function Conteudo() {
  const { id } = useParams<{ id: string }>();
  const registrada = useSearchParams()?.get("registrada") === "1";
  const { token } = useAuth();
  const { papeis } = usePapeis();
  const { estado, recarregar } = usePrestacao(token, id ?? null);
  return (
    <>
      <TopoInterno area="Contas" />
      <main className="envelope cts cts-ficha">
        <Link className="cts-voltar" href={comToken("/contas", token)}>
          ← Prestações de contas
        </Link>
        {estado.fase === "carregando" && <p role="status">Carregando a prestação…</p>}
        {estado.fase === "erro" && <p className="cts-erro" role="alert">{estado.mensagem}</p>}
        {estado.fase === "pronto" && (
          <FichaPrestacao p={estado.dado} token={token} ehSecretaria={papeis.includes("secretario")} registrada={registrada} onMudou={recarregar} />
        )}
      </main>
    </>
  );
}
