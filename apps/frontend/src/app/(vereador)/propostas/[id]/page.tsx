"use client";

// Uma PROPOSTA DE ATO do assistente (Faixa B / B.6, ADR-0012, docs/25 Eixo 4.2 B). O assistente não protocola nem
// assina: ele prepara, e a pessoa decide AQUI, na plataforma — nunca no chat. A ilha-papel mostra o texto montado
// agora (a data é a de hoje: é este que será assinado); se a proposta nasceu depois de o assistente ler conteúdo de
// fora da Casa, a tela avisa (Eixo 4.5). Confirmar segue o mesmo ritual da tela de origem (assinatura em 2 toques).
// Reusa o CSS do ritual de assinatura (assinar.css), como o "Novo requerimento".

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useProposta } from "@/lib/use-propostas";
import {
  avisoDeOrigem,
  reciboDaProposta,
  rotuloDoEstado,
  textoAtualizado,
  validadeDaProposta,
} from "@/lib/propostas-vista";
import { comToken } from "@/lib/nav";
import "../../parecer/[id]/assinar/assinar.css";
import "../../requerimento/novo/requerimento.css";
import "../propostas.css";

export default function PaginaProposta() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { token } = useAuth();
  const { estado, enviando, erro, confirmar, recusar } = useProposta(token, id);
  const [folha, setFolha] = useState(false);

  if (estado.fase === "carregando") {
    return (
      <div className="tela-estado">
        <h1>Carregando…</h1>
      </div>
    );
  }
  if (estado.fase === "erro") {
    return (
      <div className="tela-estado">
        <h1>Proposta não encontrada</h1>
        <p>{estado.mensagem}</p>
      </div>
    );
  }

  const p = estado.dado;
  const aguardando = p.estado === "aguardando";
  const texto = p.apresentacaoAtual?.texto ?? p.texto;
  const origem = avisoDeOrigem(p);
  const atualizado = textoAtualizado(p);
  const recibo = reciboDaProposta(p);
  const assinatura = p.ritual === "assinatura";

  return (
    <div className="assinar-pagina">
      <button type="button" className="btn btn-fantasma btn-mini assinar-voltar" onClick={() => router.back()}>
        ← Voltar
      </button>
      <p className="prop-origem-agente">Preparado pelo assistente da Casa</p>
      <h1 className="assinar-titulo">{p.titulo}</h1>
      <p className="prop-estado">
        <span className={`prop-selo prop-selo-${p.estado}`}>{rotuloDoEstado(p.estado)}</span>
        {aguardando && <span className="prop-validade">{validadeDaProposta(p)}</span>}
      </p>

      {origem && (
        <p className="prop-aviso-origem" role="note">
          {origem}
        </p>
      )}

      {recibo && (
        <p className="prop-recibo" role="status">
          {recibo}
        </p>
      )}

      <section className="papel" aria-label={aguardando ? "Documento a confirmar" : "Documento proposto"}>
        <div className="cab">
          <h2>
            {p.estado === "confirmada"
              ? assinatura
                ? "Texto assinado"
                : "O que foi feito"
              : !aguardando
                ? "O que foi proposto"
                : assinatura
                  ? "Texto a assinar"
                  : "O que será feito"}
          </h2>
        </div>
        <div className="corpo">
          <p className="req-texto">{texto}</p>
        </div>
      </section>
      {aguardando && atualizado && <p className="req-ajuda">{atualizado}</p>}

      {aguardando && (
        <div className="sumario">
          <h3>Nada foi feito ainda</h3>
          <p>
            O assistente só <b>preparou</b>. Se você confirmar, {assinatura ? "o texto é assinado e protocolado" : "a ação é feita"}{" "}
            <b>em seu nome</b>, como se você tivesse feito pela tela.
          </p>
          <p>Se não era isso, recuse — e peça de novo, se quiser.</p>
        </div>
      )}

      {aguardando ? (
        <div className="assinar-bar">
          <div className="assinar-bar-in prop-acoes">
            {erro && !folha && (
              <p role="alert" className="erro-inline">
                {erro}
              </p>
            )}
            <button className="btn btn-primaria" type="button" onClick={() => setFolha(true)} disabled={enviando}>
              {assinatura ? "Revisar e assinar" : "Revisar e confirmar"}
            </button>
            <button className="btn btn-fantasma" type="button" onClick={recusar} disabled={enviando}>
              Recusar
            </button>
          </div>
        </div>
      ) : (
        <Link className="btn btn-primaria prop-voltar" href={comToken("/propostas", token)}>
          Ver as outras propostas
        </Link>
      )}

      {folha && aguardando && (
        <div className="scrim" role="dialog" aria-modal="true" aria-labelledby="sh-tit">
          <div className="sheet">
            <h3 id="sh-tit">{assinatura ? "Confirmar assinatura" : "Confirmar"}</h3>
            <p className="sub">
              {assinatura
                ? "Confira o texto uma última vez. Depois de protocolado, ele não pode ser alterado."
                : "Confira uma última vez. A ação é feita em seu nome."}
            </p>
            {erro && (
              <p role="status" className="erro-inline">
                {erro}
              </p>
            )}
            <div className="acoes">
              <button
                className="btn btn-primaria"
                type="button"
                disabled={enviando}
                onClick={async () => {
                  if (await confirmar()) setFolha(false);
                }}
              >
                {enviando ? "Confirmando…" : assinatura ? "Confirmar e protocolar" : "Confirmar"}
              </button>
              <button className="btn btn-fantasma" type="button" onClick={() => setFolha(false)}>
                Cancelar
              </button>
            </div>
            {assinatura && (
              <p className="legal">A assinatura é registrada no sistema junto com o texto, e não pode ser desfeita.</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
