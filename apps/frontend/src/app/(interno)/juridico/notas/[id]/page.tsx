"use client";

// Rota /juridico/notas/:id (interno) — uma nota técnica da IA lida pelo JURÍDICO (ADR-0019 fatia 2a, Eixo 5). O advogado lê
// o rascunho (cada número remete ao dispositivo que sustenta a frase, conferido pelo sistema) e pode "Usar como rascunho":
// abre o pedido de parecer da matéria (ou reaproveita o pendente) com o texto da nota, sem as marcas de citação e SEM
// conclusão, e leva ao editor. O texto da IA nunca é chamado de parecer: o advogado o revisa, decide a conclusão e assina.

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth, usePapeis } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { avisoDaNota, linhaDaNota, numeroDaNota } from "@/lib/conferencias-vista";
import { AVISO_TEXTO_DE_IA } from "@/lib/juridico-vista";
import { useNotaTecnica } from "@/lib/use-conferencias";
import { usarNotaComoRascunho } from "@/lib/use-juridico";
import type { NotaTecnicaOut } from "@/lib/contrato-legislativo.gen";
import { GuardJuridico } from "../../../guard-juridico";
import { LeituraDaNota } from "../../../conferencias/leitura-nota";
import { TopoInterno } from "../../../topo";
import "../../../conferencias/conferencias.css";
import "../../juridico.css";

export default function PaginaNotaDoJuridico() {
  return (
    <GuardJuridico>
      <Conteudo />
    </GuardJuridico>
  );
}

function Conteudo() {
  const { token } = useAuth();
  const { papeis } = usePapeis();
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { estado } = useNotaTecnica(token, id ?? null);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const ehJuridico = papeis.includes("juridico");

  async function usar() {
    if (!id) return;
    setEnviando(true);
    setErro(null);
    const r = await usarNotaComoRascunho(token, id);
    if (r.ok) router.push(comToken(`/juridico/${r.dado.id}`, token));
    else {
      setEnviando(false);
      setErro(r.mensagem);
    }
  }

  return (
    <>
      <TopoInterno area="Jurídico" />
      <main className="envelope conf conf-nota">
        <Link className="conf-voltar" href={comToken("/juridico", token)}>← Parecer jurídico</Link>
        {estado.fase === "carregando" && <p role="status">Carregando a nota…</p>}
        {estado.fase === "erro" && <p className="conf-erro" role="alert">{estado.mensagem}</p>}
        {estado.fase === "pronto" && (
          <Nota n={estado.dado} token={token} ehJuridico={ehJuridico} enviando={enviando} erro={erro} usar={usar} />
        )}
      </main>
    </>
  );
}

function Nota({ n, token, ehJuridico, enviando, erro, usar }: {
  n: NotaTecnicaOut;
  token: string | null;
  ehJuridico: boolean;
  enviando: boolean;
  erro: string | null;
  usar: () => void;
}) {
  const aviso = avisoDaNota(n.incerteza, n.motivosIncerteza);
  return (
    <>
      <header className="conf-cabeca">
        <p className="conf-numero">{numeroDaNota(n)}</p>
        <h1 className="titulo-longo">{n.ementa}</h1>
        <p className="conf-sub">
          {linhaDaNota(n)} ·{" "}
          <Link href={comToken(`/ficha-materia/${n.proposicaoId}`, token)}>Abrir a ficha da matéria</Link>
        </p>
      </header>

      {n.estado === "pendente" ? (
        <>
          <p className="conf-selo-ia" role="note">
            <b>Rascunho produzido por IA.</b> Não é parecer nem decisão: confira cada ponto. {AVISO_TEXTO_DE_IA}
          </p>
          {aviso && <p className="conf-aviso" role="note">{aviso}</p>}
          <LeituraDaNota n={n} token={token} />
          {erro && <p className="conf-erro" role="alert">{erro}</p>}
          {ehJuridico ? (
            <section className="conf-confirma" aria-label="Usar como rascunho">
              <p>
                <b>Usar como rascunho</b> abre o pedido de parecer desta matéria com o texto da nota, sem as marcas de
                citação e sem conclusão. Você revisa, escolhe a conclusão e assina; nada é assinado por você antes disso.
                A nota sai da fila da secretaria e da sua.
              </p>
              <div className="conf-acoes">
                <button type="button" className="btn btn-primaria" disabled={enviando} onClick={usar}>
                  {enviando ? "Abrindo o rascunho…" : "Usar como rascunho"}
                </button>
              </div>
            </section>
          ) : (
            <p className="conf-vazio">Só o jurídico da Casa usa a nota como rascunho de parecer.</p>
          )}
        </>
      ) : (
        <p className="conf-vazio">
          {n.estado === "aproveitada"
            ? "Esta nota já foi aproveitada, como rascunho de parecer ou pela secretaria. Ela não está mais na fila."
            : "Esta nota foi descartada pela secretaria. Ela fica guardada só como registro."}
        </p>
      )}
    </>
  );
}
