"use client";

// O vereador-relator REDIGE o parecer da comissão (ADR-0019, fatia 2): Relatório e Análise, salvos como nova versão
// "rascunho" do texto (PATCH /api/meu/pareceres/:id — o mesmo fluxo do editor da secretaria; o backend confere que ele é
// o relator). No campo Análise entra o COPILOTO DO RELATOR: a IA rascunha a análise de constitucionalidade e
// juridicidade citando a matéria e as normas da Casa, e o texto só vai ao campo quando o relator pede — nada é salvo até
// "Salvar rascunho". Abaixo, o pedido de PARECER JURÍDICO da matéria. A assinatura (e o voto) seguem na tela de assinar.
//
// Rota /parecer/:id/redigir: o grupo (vereador) não entra na URL e /parecer/:id já é o editor da secretaria.

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useMeuParecer } from "@/lib/use-meu-parecer";
import { useSalvarRascunhoParecer } from "@/lib/use-salvar-rascunho-parecer";
import { pedirAnalise } from "@/lib/use-copiloto-analise";
import { parecerEhTerminal, rotularEstadoParecer } from "@/lib/parecer-vista";
import { rotularComissao } from "@/lib/comissao-vista";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { comToken } from "@/lib/nav";
import type { ParecerEditorOut } from "@/lib/contrato-legislativo.gen";
import { CopilotoAnalise } from "@/app/copiloto-analise";
import { PedidoJuridicoDoRelator } from "./pedido-juridico-relator";
import "../assinar/assinar.css";
import "./redigir.css";

export default function PaginaRedigirParecer() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { token } = useAuth();
  const { dados, estado } = useMeuParecer(token, id);

  if (estado === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }
  if (estado === "erro" || !dados) {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar este parecer</h1>
        <p>Ele pode não existir, ou você não é o relator designado.</p>
      </main>
    );
  }

  return (
    <div className="redigir-pagina">
      <button type="button" className="btn btn-fantasma btn-mini assinar-voltar" onClick={() => router.back()}>
        ← Voltar
      </button>
      <h1 className="assinar-titulo">Redigir parecer</h1>
      <p className="red-sub">
        {dados.objeto
          ? `${formatarNumeroProposicao(dados.objeto.tipo, dados.objeto.sequencial, dados.objeto.ano)} — ${dados.objeto.ementa}`
          : "Parecer de comissão"}
        <br />
        {rotularComissao(dados.comissaoNome ?? null)} · {rotularEstadoParecer(dados.estado)}
      </p>
      {/* `key`: outro parecer, outro editor (o estado dos campos nasce do que o servidor devolveu) */}
      <EditorDoRelator key={dados.id} dados={dados} token={token} id={id} />
      {dados.objetoTipo === "proposicao" && (
        <PedidoJuridicoDoRelator token={token} parecerId={id} proposicaoId={dados.objetoId} />
      )}
    </div>
  );
}

function EditorDoRelator({ dados, token, id }: { dados: ParecerEditorOut; token: string | null; id: string }) {
  const { salvar, estado, erro } = useSalvarRascunhoParecer(token, id, "meu");
  const [relatorio, setRelatorio] = useState(dados.relatorio ?? "");
  const [analise, setAnalise] = useState(dados.analise ?? "");
  const [salvo, setSalvo] = useState<string | null>(null);
  const bloqueado = parecerEhTerminal(dados.estado);

  async function aoSalvar() {
    setSalvo(null);
    try {
      const d = await salvar({ relatorio, analise });
      setSalvo(`Rascunho salvo (versão ${d.textoNumeroVersao ?? "nova"}). Assine quando o texto estiver pronto.`);
    } catch {
      // o erro já está em `erro` (o hook nunca engole a mensagem do servidor)
    }
  }

  if (bloqueado) {
    return (
      <p className="vazio" role="status">
        Este parecer já teve desfecho na comissão: o texto não muda mais.
      </p>
    );
  }

  return (
    <form className="red-form" onSubmit={(e) => e.preventDefault()}>
      <label htmlFor="red-relatorio">Relatório</label>
      <p className="red-aj">Resumo da matéria e do seu trâmite até aqui.</p>
      <textarea id="red-relatorio" value={relatorio} onChange={(e) => setRelatorio(e.target.value)} rows={6} />

      <label htmlFor="red-analise">Análise</label>
      <p className="red-aj">Constitucionalidade, juridicidade e técnica legislativa.</p>
      <textarea id="red-analise" value={analise} onChange={(e) => setAnalise(e.target.value)} rows={10} />
      {dados.objetoTipo === "proposicao" && (
        <CopilotoAnalise pedir={() => pedirAnalise(token, "meu", id)} analiseAtual={analise} aoUsar={setAnalise} />
      )}

      {erro && (
        <p role="alert" className="erro-inline">
          Não foi possível salvar: {erro}
        </p>
      )}
      {salvo && (
        <p role="status" className="red-ok">
          {salvo}
        </p>
      )}
      <div className="red-acoes">
        <button type="button" className="btn btn-primaria" onClick={aoSalvar} disabled={estado === "enviando"}>
          {estado === "enviando" ? "Salvando…" : "Salvar rascunho"}
        </button>
        <Link className="btn btn-contorno" href={comToken(`/parecer/${id}/assinar`, token)}>
          Ir para a assinatura
        </Link>
      </div>
    </form>
  );
}
