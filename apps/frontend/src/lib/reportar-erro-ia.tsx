"use client";

// "Reportar erro" numa resposta de IA (feature 8.4). Discreto: um link-botão sob a resposta; ao clicar, a pessoa diz O
// QUE está errado escolhendo uma categoria — o registro da Camada de Confiança é SEM conteúdo (B4), então não há campo
// de texto livre: o vocabulário é o do satélite (`CategoriaReporte`). Vai a POST /api/ia/execucoes/:id/reportes; o core
// põe a Casa e a pessoa da sessão e repassa ao satélite, que não conta duas vezes o reporte da mesma pessoa. É isso que
// o painel da IA da Casa mostra em "erros reportados". IA fora não quebra nada: a tela diz para tentar de novo.

import { useId, useState } from "react";
import { apiFetch } from "./api-fetch";
import "./reportar-erro-ia.css";

export const CATEGORIAS_REPORTE = [
  { valor: "fato_errado", rotulo: "Informação errada" },
  { valor: "citacao_errada", rotulo: "A fonte não diz isso" },
  { valor: "omissao", rotulo: "Faltou algo importante" },
  { valor: "linguagem", rotulo: "Linguagem confusa ou inadequada" },
  { valor: "outro", rotulo: "Outro problema" },
] as const;

type Categoria = (typeof CATEGORIAS_REPORTE)[number]["valor"];

export const OBRIGADO_REPORTE = "Obrigado — isso entra na revisão da IA.";

function mensagemDeErro(status: number): string {
  if (status === 404) return "Esta resposta não pode mais ser reportada.";
  return "Não foi possível registrar agora. Tente de novo em instantes.";
}

export function ReportarErroIa({ execucaoId, token = null }: { execucaoId: string; token?: string | null }) {
  const [fase, setFase] = useState<"fechado" | "aberto" | "enviando" | "feito">("fechado");
  const [categoria, setCategoria] = useState<Categoria | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const nome = useId();

  if (fase === "feito") {
    return (
      <p className="reportar-ia-ok" role="status">
        {OBRIGADO_REPORTE}
      </p>
    );
  }

  if (fase === "fechado") {
    return (
      <button type="button" className="reportar-ia-botao" onClick={() => setFase("aberto")}>
        Reportar erro
      </button>
    );
  }

  async function enviar() {
    if (!categoria || fase === "enviando") return;
    setFase("enviando");
    setErro(null);
    try {
      const r = await apiFetch(`/api/ia/execucoes/${encodeURIComponent(execucaoId)}/reportes`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ categoria }),
        cache: "no-store",
      });
      if (r.ok) return setFase("feito");
      setErro(mensagemDeErro(r.status));
    } catch {
      setErro(mensagemDeErro(0));
    }
    setFase("aberto");
  }

  return (
    <form
      className="reportar-ia"
      onSubmit={(e) => {
        e.preventDefault();
        void enviar();
      }}
    >
      <fieldset>
        <legend>O que está errado?</legend>
        {CATEGORIAS_REPORTE.map((c) => (
          <label key={c.valor} className="reportar-ia-opcao">
            <input
              type="radio"
              name={nome}
              value={c.valor}
              checked={categoria === c.valor}
              onChange={() => setCategoria(c.valor)}
            />
            {c.rotulo}
          </label>
        ))}
      </fieldset>
      {erro && (
        <p className="reportar-ia-erro" role="alert">
          {erro}
        </p>
      )}
      <div className="reportar-ia-acoes">
        <button type="button" className="reportar-ia-botao" onClick={() => setFase("fechado")}>
          Cancelar
        </button>
        <button type="submit" className="btn btn-contorno btn-mini" disabled={!categoria || fase === "enviando"}>
          {fase === "enviando" ? "Enviando…" : "Enviar"}
        </button>
      </div>
    </form>
  );
}
