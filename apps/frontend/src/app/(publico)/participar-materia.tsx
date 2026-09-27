"use client";

// Participar de uma matéria no portal (formulários do cidadão, ADR-0015) — porte de ficha-materia-publica.html:
// o cartão "Quer acompanhar?" e a caixa "Deixe seu comentário". Três caras, pela sessão (useSessaoCidada):
//   - anônima: o convite "Entrar com gov.br e …", que volta a ESTA matéria depois do login;
//   - cidadã desta Casa: o botão/formulário de verdade;
//   - sessão de outra Casa: um aviso — o backend gravaria na Casa da sessão, não nesta.
// O comentário NÃO aparece na hora: entra pendente e só sai na lista depois da moderação da Câmara (é o que a
// tela diz). O design mostra nome do autor, "Útil" e "Responder" — o backend não tem nada disso, então não aparece.

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api-fetch";
import { hrefEntrarComGovbr } from "@/lib/participar-vista";
import { useEnvioCidadao } from "@/lib/use-envio-cidadao";
import type { EstadoSessaoCidada } from "@/lib/use-sessao-cidada";

type Sessao = { estado: EstadoSessaoCidada; token: string | null };

const MAX_COMENTARIO = 2000;

function voltaParaMateria(ente: string, proposicaoId: string) {
  return hrefEntrarComGovbr(ente, `/portal/casa/${ente}/materias/${proposicaoId}`);
}

function AvisoOutraCasa({ ente }: { ente: string }) {
  return (
    <p className="compor-aviso" role="note">
      Você entrou por outra Câmara. Para participar aqui,{" "}
      <a href={`/portal/casa/${ente}/participar`}>entre por esta Câmara</a>.
    </p>
  );
}

const IconeCadeado = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
    <path d="M12 2a5 5 0 0 0-5 5v3H6a2 2 0 0 0-2 2v8h16v-8a2 2 0 0 0-2-2h-1V7a5 5 0 0 0-5-5z" />
  </svg>
);

const IconeSino = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
    <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0" />
  </svg>
);

export function ComporComentario({ ente, proposicaoId, sessao }: { ente: string; proposicaoId: string; sessao: Sessao }) {
  const { enviar, estado, erro } = useEnvioCidadao(sessao.token);
  const [texto, setTexto] = useState("");
  const [enviado, setEnviado] = useState(false);

  if (sessao.estado === "carregando") return <div className="compor" aria-busy="true" />;
  if (sessao.estado === "outra-casa") return <AvisoOutraCasa ente={ente} />;

  if (sessao.estado !== "cidada") {
    return (
      <div className="compor">
        <div className="compor-rod">
          <span className="ident">
            <IconeCadeado />
            Para comentar, identifique-se com a conta gov.br.
          </span>
          <a className="btn btn-primaria btn-mini" href={voltaParaMateria(ente, proposicaoId)}>
            Entrar com gov.br e comentar
          </a>
        </div>
      </div>
    );
  }

  if (enviado) {
    return (
      <div className="compor compor-ok" role="status">
        <b>Recebemos seu comentário.</b> Ele aparece aqui depois da moderação da Câmara.{" "}
        <button type="button" className="link-simples" onClick={() => setEnviado(false)}>
          Escrever outro
        </button>
      </div>
    );
  }

  const limpo = texto.trim();
  return (
    <form
      className="compor"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!limpo) return;
        try {
          await enviar(`/portal/materias/${encodeURIComponent(proposicaoId)}/comentarios`, { corpo: limpo });
          setTexto("");
          setEnviado(true);
        } catch {
          // a mensagem já está em `erro`
        }
      }}
    >
      <label htmlFor="cmt">Deixe seu comentário</label>
      <textarea
        id="cmt"
        value={texto}
        maxLength={MAX_COMENTARIO}
        placeholder="Escreva de forma respeitosa o que pensa sobre este projeto…"
        onChange={(e) => setTexto(e.target.value)}
      />
      {erro && (
        <p className="form-erro" role="alert">
          {erro}
        </p>
      )}
      <div className="compor-rod">
        <span className="ident">
          <IconeCadeado />
          Identificada pelo gov.br · {texto.length}/{MAX_COMENTARIO}
        </span>
        <button className="btn btn-primaria btn-mini" type="submit" disabled={!limpo || estado === "enviando"}>
          {estado === "enviando" ? "Enviando…" : "Enviar comentário"}
        </button>
      </div>
    </form>
  );
}

export function AcompanharMateria({ ente, proposicaoId, sessao }: { ente: string; proposicaoId: string; sessao: Sessao }) {
  const { enviar, estado, erro } = useEnvioCidadao(sessao.token);
  const [seguindo, setSeguindo] = useState<boolean | null>(null);

  useEffect(() => {
    if (sessao.estado !== "cidada") return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/portal/acompanhamentos", { token: sessao.token ?? undefined, cache: "no-store" });
        const d = r.ok ? ((await r.json()) as { acompanhamentos?: { "proposicao-id"?: string }[] }) : null;
        if (vivo) setSeguindo(!!d?.acompanhamentos?.some((a) => a["proposicao-id"] === proposicaoId));
      } catch {
        if (vivo) setSeguindo(false);
      }
    })();
    return () => {
      vivo = false;
    };
  }, [sessao.estado, sessao.token, proposicaoId]);

  const texto = (
    <div className="t">
      <b>{seguindo ? "Você acompanha esta matéria" : "Quer acompanhar?"}</b>
      {seguindo
        ? "Ela aparece em “Minhas matérias acompanhadas”."
        : "Avisamos a cada novo passo desta matéria, sem custo."}
    </div>
  );

  if (sessao.estado === "carregando") return <div className="acomp" aria-busy="true" />;
  if (sessao.estado === "outra-casa") return <div className="acomp">{texto}<AvisoOutraCasa ente={ente} /></div>;
  if (sessao.estado !== "cidada") {
    return (
      <div className="acomp">
        {texto}
        <a className="btn btn-primaria" href={voltaParaMateria(ente, proposicaoId)}>
          <IconeSino />
          Entrar com gov.br e acompanhar
        </a>
      </div>
    );
  }

  const caminho = `/portal/materias/${encodeURIComponent(proposicaoId)}/acompanhar`;
  return (
    <div className="acomp">
      {texto}
      {erro && (
        <p className="form-erro" role="alert">
          {erro}
        </p>
      )}
      {seguindo !== null && (
        <button
          type="button"
          className={seguindo ? "btn btn-contorno" : "btn btn-primaria"}
          disabled={estado === "enviando"}
          onClick={async () => {
            try {
              await enviar(caminho, undefined, seguindo ? "DELETE" : "POST", {
                404: "Esta matéria ainda não pode ser acompanhada.",
              });
              setSeguindo(!seguindo);
            } catch {
              // a mensagem já está em `erro`
            }
          }}
        >
          {!seguindo && <IconeSino />}
          {seguindo ? "Deixar de acompanhar" : "Acompanhar esta matéria"}
        </button>
      )}
    </div>
  );
}
