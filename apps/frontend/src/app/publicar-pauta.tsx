"use client";

// "Publicar a pauta" (ADR-0019 fatia 3, Eixo 7) — o ato único sobre a pauta inteira de uma sessão: congela a versão
// que o portal do cidadão e a TV do plenário mostram como pauta OFICIAL. Compartilhado pela tela de montar a pauta
// (secretaria) e pela home do vereador (Presidente / 1º Secretário / Mesa, conforme a regra da Casa).
//
// Os avisos (matéria sem parecer da comissão, pedido jurídico pendente, antecedência mínima) são AVISOS: aparecem
// antes e na confirmação, e nunca impedem a publicação — os Regimentos deixam a matéria ir a plenário com o prazo da
// comissão vencido. Quem não pode publicar pela regra da Casa vê o botão desabilitado com o motivo que o servidor
// devolveu (a regra roda lá, com o cargo lido de cadastros).

import { useState } from "react";
import type { PautaPublicadaOut, PublicacaoPautaOut } from "@/lib/contrato-sessoes.gen";
import { publicarPauta, usePublicacaoPauta } from "@/lib/use-publicacao-pauta";
import {
  avisoDeAlteracao,
  descreverRegra,
  estadoDoBotao,
  seloDaPublicacao,
  textoDoAviso,
} from "@/lib/publicacao-pauta-vista";
import "./publicar-pauta.css";

interface Props {
  token: string | null;
  sessaoId: string;
  /** Muda quando a pauta viva muda (refaz a leitura dos avisos e do "alterada desde"). */
  chavePauta?: string;
  /** Sem a publicação, some (a home do vereador só mostra a quem pode publicar). */
  soQuemPode?: boolean;
  onPublicada?: (r: PautaPublicadaOut) => void;
}

export function PublicarPauta({ token, sessaoId, chavePauta = "", soQuemPode = false, onPublicada }: Props) {
  const { estado, recarregar } = usePublicacaoPauta(token, sessaoId, chavePauta);
  if (estado.fase === "carregando") return soQuemPode ? null : <p role="status">Carregando a publicação…</p>;
  // a leitura falhou: diz em linha (role=status) — o painel é auxiliar da tela, não um erro que compete com o dela
  if (estado.fase === "erro") return soQuemPode ? null : <p className="pp-erro" role="status">{estado.mensagem}</p>;
  if (soQuemPode && !estado.dado.podePublicar) return null;
  return (
    <PainelPublicacao
      token={token}
      sessaoId={sessaoId}
      pub={estado.dado}
      onPublicada={(r) => {
        recarregar();
        onPublicada?.(r);
      }}
    />
  );
}

function PainelPublicacao({
  token,
  sessaoId,
  pub,
  onPublicada,
}: {
  token: string | null;
  sessaoId: string;
  pub: PublicacaoPautaOut;
  onPublicada: (r: PautaPublicadaOut) => void;
}) {
  const [confirmando, setConfirmando] = useState(false);
  const [justificativa, setJustificativa] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [resultado, setResultado] = useState<{ tom: "ok" | "erro"; texto: string } | null>(null);
  const botao = estadoDoBotao(pub);
  const alterada = avisoDeAlteracao(pub);
  const exigeJustificativa = pub.republicacao;

  async function confirmar() {
    if (exigeJustificativa && !justificativa.trim()) {
      setResultado({ tom: "erro", texto: "Republicar exige dizer o que mudou." });
      return;
    }
    setEnviando(true);
    const r = await publicarPauta(token, sessaoId, exigeJustificativa ? justificativa : undefined);
    setEnviando(false);
    if (r.ok) {
      setConfirmando(false);
      setJustificativa("");
      const fora = r.dado.aviso === "antecedencia-nao-cumprida";
      setResultado({
        tom: "ok",
        texto: `Pauta publicada: versão ${r.dado.versao}.${fora ? " Atenção: publicada fora da antecedência mínima da Casa." : ""}`,
      });
      onPublicada(r.dado);
    } else {
      setResultado({ tom: "erro", texto: r.mensagem });
    }
  }

  return (
    <section className="card pp" aria-label="Publicar a pauta">
      <div className="pp-cab">
        <h2>Pauta oficial</h2>
        <span className={`pp-selo${pub.ultima ? " pp-selo-ok" : ""}`}>{seloDaPublicacao(pub.ultima)}</span>
      </div>
      {alterada && <p className="pp-alterada">{alterada}: o portal e a TV ainda mostram a versão publicada.</p>}
      <p className="pp-regra">{descreverRegra(pub.regra)}</p>

      {(pub.avisos.length > 0 || pub.avisosIndisponiveis) && (
        <div className="pp-avisos">
          <p className="pp-avisos-tit">Avisos (não impedem a publicação)</p>
          <ul>
            {pub.avisos.map((a, i) => (
              <li key={`${a.tipo}-${a.itemId ?? i}`}>{textoDoAviso(a)}</li>
            ))}
            {pub.avisosIndisponiveis && <li>Não foi possível conferir os pareceres das matérias agora.</li>}
          </ul>
        </div>
      )}

      {!confirmando ? (
        <div className="pp-acoes">
          <button
            type="button"
            className="btn btn-primaria"
            disabled={!botao.habilitado}
            aria-describedby={botao.motivo ? `pp-motivo-${sessaoId}` : undefined}
            onClick={() => {
              setResultado(null);
              setConfirmando(true);
            }}
          >
            {pub.republicacao ? "Republicar a pauta" : "Publicar a pauta"}
          </button>
          {botao.motivo && (
            <p className="pp-motivo" id={`pp-motivo-${sessaoId}`}>
              {botao.motivo}
            </p>
          )}
        </div>
      ) : (
        <div className="pp-confirmar" role="group" aria-label="Confirmar a publicação">
          <p>
            Publicar a pauta com {pub.itensNaPauta} {pub.itensNaPauta === 1 ? "item" : "itens"}
            {pub.avisos.length > 0 ? ` e ${pub.avisos.length} aviso(s)` : ""}? Ela passa a ser a pauta oficial no portal e
            na TV, e fica registrada em seu nome.
          </p>
          {exigeJustificativa && (
            <div className="campo">
              <label htmlFor={`pp-just-${sessaoId}`}>O que mudou desde a v{pub.ultima?.versao} (obrigatório)</label>
              <textarea
                id={`pp-just-${sessaoId}`}
                rows={2}
                maxLength={2000}
                value={justificativa}
                onChange={(e) => setJustificativa(e.target.value)}
              />
            </div>
          )}
          <div className="pp-acoes">
            <button type="button" className="btn btn-primaria btn-mini" disabled={enviando} onClick={() => void confirmar()}>
              {enviando ? "Publicando…" : "Confirmar publicação"}
            </button>
            <button type="button" className="btn btn-fantasma btn-mini" disabled={enviando} onClick={() => setConfirmando(false)}>
              Cancelar
            </button>
          </div>
        </div>
      )}

      <p className={`pp-resultado${resultado ? ` pp-${resultado.tom}` : ""}`} role={resultado?.tom === "erro" ? "alert" : "status"}>
        {resultado?.texto ?? ""}
      </p>

      {pub.versoes.length > 0 && (
        <details className="pp-historico">
          <summary>Histórico de publicações ({pub.versoes.length})</summary>
          <ol>
            {pub.versoes.map((v) => (
              <li key={v.versao}>
                {seloDaPublicacao(v)} · {v.itens} {v.itens === 1 ? "item" : "itens"}
                {v.publicadaPorNome ? ` · por ${v.publicadaPorNome}` : ""}
                {v.justificativa ? ` · “${v.justificativa}”` : ""}
              </li>
            ))}
          </ol>
        </details>
      )}
    </section>
  );
}
