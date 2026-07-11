"use client";

// AbasExpediente — a nav das 4 abas do Expediente (Onda B Slice 6). Porte de expediente.html:314-332
// (`.abas`/`.abas-grade`/`.aba`). Só "Gerar documento" tem backend fiado nesta fatia; Protocolo geral/
// Modelos/Recebidos são rotas próprias com <EmBreve> (a gestão do LIVRO/CRUD de modelos/entradas
// recebidas ainda não tem tela dedicada — o Livro do Protocolo Geral do dia JÁ aparece embutido na aba
// "Gerar documento", ver tabela-protocolo.tsx). Sem contadores (`.cont` do mockup): mostrar um contador
// real em só 2 das 4 abas (Modelos/Protocolo têm dado; Recebidos não tem contrato nenhum ainda) seria mais
// confuso que none — omitido por inteiro (Global Constraint "sem dado falso" aplicada por omissão, não só
// ao "Recebidos"; mesma disciplina do chip-prazo em parecer).

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import "./expediente.css";

export type AbaExpediente = "gerar" | "protocolo" | "modelos" | "recebidos";

const ABAS: { id: AbaExpediente; rotulo: string; href: string }[] = [
  { id: "gerar", rotulo: "Gerar documento", href: "/expediente" },
  { id: "protocolo", rotulo: "Protocolo geral", href: "/expediente/protocolo" },
  { id: "modelos", rotulo: "Modelos", href: "/expediente/modelos" },
  { id: "recebidos", rotulo: "Recebidos", href: "/expediente/recebidos" },
];

export function AbasExpediente({ atual }: { atual: AbaExpediente }) {
  const { token } = useAuth();
  return (
    <nav className="abas" aria-label="Seções do Expediente">
      <div className="envelope">
        <ul className="abas-grade">
          {ABAS.map((aba) => (
            <li key={aba.id} className={`aba${aba.id === atual ? " atual" : ""}`}>
              <Link href={comToken(aba.href, token)} aria-current={aba.id === atual ? "page" : undefined}>
                {aba.rotulo}
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </nav>
  );
}
