"use client";

// Aba "Resumo cidadão" da ficha interna (Faixa A / A.8 da Track IA). A IA redige sozinha o resumo em linguagem simples
// a cada versão nova do texto; aqui a secretaria revisa o rascunho (cada número remete ao dispositivo que sustenta a
// frase, conferido pelo sistema), edita e publica em dois passos. Só o publicado vai ao portal. IA fora não trava
// nada: o resumo pode ser escrito à mão (R-IA-1).

import { useEffect, useState } from "react";
import { useResumo } from "@/lib/use-resumo";
import { paragrafosDoRascunho, rotuloDaCitacao } from "@/lib/rascunho-ata-vista";
import {
  TETO_TEXTO_RESUMO,
  avisoDoResumo,
  faltaParaPublicar,
  linhaDaVersao,
  situacaoDoResumo,
} from "@/lib/resumo-vista";
import type { RascunhoResumoOut } from "@/lib/contrato-legislativo.gen";
import "./resumo.css";

type Modo = "ler" | "revisar" | "editar" | "confirmar";

export function PainelResumo({ proposicaoId, token = null }: { proposicaoId: string; token?: string | null }) {
  const r = useResumo(proposicaoId, token);
  const [modo, setModo] = useState<Modo>("ler");
  const [texto, setTexto] = useState("");
  const [rascunhoId, setRascunhoId] = useState<string | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  if (r.estado === "carregando") return <p role="status">Carregando o resumo…</p>;
  if (r.estado === "negado")
    return <p role="status">O resumo para o portal é revisado e publicado pela secretaria.</p>;
  if (r.estado === "erro") return <p role="status">Não foi possível carregar o resumo.</p>;
  const { atual, rascunho, versoes } = r.resumo;
  const proxima = (atual?.versao.versao ?? 0) + 1;

  function editar(inicial: string, rid: string | null) {
    setTexto(inicial);
    setRascunhoId(rid);
    setErro(null);
    setAviso(null);
    setModo("editar");
  }

  async function confirmar() {
    setErro(null);
    try {
      const recibo = await r.publicar(texto.trim(), rascunhoId);
      setAviso(`Versão ${recibo.versao} publicada no portal.`);
      setModo("ler");
      setRascunhoId(null);
    } catch (e) {
      setErro(e instanceof Error ? e.message : "Não foi possível publicar agora.");
      setModo("editar");
    }
  }

  if (modo === "revisar" && rascunho?.rascunhoId)
    return (
      <RevisaoDoRascunho
        rascunhoId={rascunho.rascunhoId}
        lerRascunho={r.lerRascunho}
        voltar={() => setModo("ler")}
        usar={(c) => editar(c.textoLimpo, c.rascunhoId)}
      />
    );

  if (modo === "editar" || modo === "confirmar") {
    const falta = faltaParaPublicar(texto);
    return (
      <section className="resumo-editor" aria-label="Escrever o resumo">
        <h3>{rascunhoId ? "Revisar o rascunho e publicar" : atual ? `Nova versão do resumo (${proxima})` : "Escrever o resumo"}</h3>
        <p className="resumo-dica">
          Linguagem simples, para quem lê o portal: o que a proposição propõe, a quem se aplica e o que muda. Sem opinião
          sobre o mérito.
        </p>
        <div className="campo">
          <label htmlFor="resumo-texto">Resumo em linguagem simples</label>
          <textarea id="resumo-texto" value={texto} rows={8} maxLength={TETO_TEXTO_RESUMO + 200}
            readOnly={modo === "confirmar"} onChange={(e) => setTexto(e.target.value)} />
          <p className="resumo-contagem">{texto.trim().length} de {TETO_TEXTO_RESUMO} caracteres</p>
        </div>
        {erro && <p className="resumo-erro" role="alert">{erro}</p>}
        {modo === "editar" ? (
          <div className="resumo-acoes">
            {falta && <p className="resumo-falta">{falta}</p>}
            <button type="button" className="btn btn-primaria" disabled={falta !== null} onClick={() => { setErro(null); setModo("confirmar"); }}>
              Revisar para publicar
            </button>
            <button type="button" className="btn btn-fantasma" onClick={() => { setModo("ler"); setErro(null); setRascunhoId(null); }}>
              Cancelar
            </button>
          </div>
        ) : (
          <div className="resumo-confirmacao" role="group" aria-label="Confirmar a publicação">
            <p>
              <b>Publicar a versão {proxima} no portal?</b> O cidadão passa a ver este resumo na página da matéria, com o
              selo de que foi escrito com ajuda de IA e revisado pela Casa.
            </p>
            <div className="resumo-acoes">
              <button type="button" className="btn btn-primaria" disabled={r.enviando} onClick={confirmar}>
                {r.enviando ? "Publicando…" : "Publicar o resumo"}
              </button>
              <button type="button" className="btn btn-fantasma" disabled={r.enviando} onClick={() => setModo("editar")}>
                Voltar a editar
              </button>
            </div>
          </div>
        )}
      </section>
    );
  }

  const s = situacaoDoResumo(rascunho);
  return (
    <div className="resumo">
      {aviso && <p className="resumo-ok" role="status">{aviso}</p>}
      {atual ? (
        <section className="resumo-publicado" aria-label="Resumo publicado">
          <p className="resumo-linha">{linhaDaVersao(atual.versao)}</p>
          {atual.versao.desatualizado && (
            <p className="resumo-aviso" role="note">O texto da proposição mudou depois deste resumo: confira se ele ainda vale.</p>
          )}
          <article className="papel resumo-texto">{atual.texto}</article>
          <div className="resumo-acoes">
            <button type="button" className="btn btn-contorno" onClick={() => editar(atual.texto, null)}>Editar e publicar nova versão</button>
          </div>
        </section>
      ) : (
        <section className="resumo-publicado">
          <p className="resumo-vazio">Esta matéria ainda não tem resumo publicado no portal.</p>
          <div className="resumo-acoes">
            <button type="button" className="btn btn-contorno" onClick={() => editar("", null)}>Escrever à mão</button>
          </div>
        </section>
      )}
      <section className="resumo-ia" aria-label="Rascunho pela IA">
        <h3>{s.titulo}</h3>
        <p>{s.detalhe}</p>
        {s.revisar && (
          <div className="resumo-acoes">
            <button type="button" className="btn btn-primaria" onClick={() => { setAviso(null); setModo("revisar"); }}>
              Revisar o rascunho
            </button>
          </div>
        )}
      </section>
      {versoes.length > 1 && (
        <section className="resumo-historico" aria-label="Versões publicadas">
          <h3>Versões publicadas</h3>
          <ol>
            {versoes.map((v) => (
              <li key={v.versao}>{linhaDaVersao(v)}</li>
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}

function RevisaoDoRascunho({ rascunhoId, lerRascunho, voltar, usar }: {
  rascunhoId: string;
  lerRascunho: (id: string) => Promise<RascunhoResumoOut>;
  voltar: () => void;
  usar: (c: RascunhoResumoOut) => void;
}) {
  const [conteudo, setConteudo] = useState<RascunhoResumoOut | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  useEffect(() => {
    let vivo = true;
    lerRascunho(rascunhoId).then(
      (c) => vivo && setConteudo(c),
      (e) => vivo && setErro(e instanceof Error ? e.message : "Não foi possível abrir o rascunho agora."),
    );
    return () => {
      vivo = false;
    };
  }, [rascunhoId, lerRascunho]);

  if (erro)
    return (
      <section className="resumo-revisao" aria-label="Revisão do rascunho">
        <p className="resumo-erro" role="alert">{erro}</p>
        <div className="resumo-acoes">
          <button type="button" className="btn btn-contorno" onClick={voltar}>Voltar</button>
        </div>
      </section>
    );
  if (!conteudo) return <p role="status">Abrindo o rascunho…</p>;

  const aviso = avisoDoResumo(conteudo.incerteza.nivel, conteudo.incerteza.motivos);
  const paragrafos = paragrafosDoRascunho(conteudo.texto, conteudo.citacoes, conteudo.paragrafosSemFonte);
  return (
    <section className="resumo-revisao" aria-label="Revisão do rascunho">
      <h3>Rascunho da IA</h3>
      <p className="resumo-dica">
        Cada número remete ao trecho da proposição que sustenta a frase. Nada vai ao portal sem a sua revisão.
      </p>
      {conteudo.desatualizado && (
        <p className="resumo-aviso" role="note">Este rascunho é de uma versão anterior do texto da proposição.</p>
      )}
      {aviso && <p className="resumo-aviso" role="note">{aviso}</p>}
      <article className="papel resumo-texto resumo-rascunho">
        {paragrafos.map((p, i) => (
          <p key={i} className={p.semFonte ? "resumo-sem-fonte" : undefined}>
            {p.semFonte && <span className="resumo-selo">sem fonte — confira</span>}
            {p.partes.map((x, j) =>
              x.tipo === "citacao" ? (
                <sup key={j} className={x.citacao?.status === "conferida" ? "resumo-cita" : "resumo-cita resumo-cita-falha"}
                  title={rotuloDaCitacao(x.citacao, "o texto")}>
                  {x.n}
                </sup>
              ) : (
                <span key={j}>{x.texto}</span>
              ),
            )}
          </p>
        ))}
      </article>
      {conteudo.citacoes.length > 0 && (
        <details className="resumo-fontes">
          <summary>Trechos citados ({conteudo.citacoes.length})</summary>
          <ol>
            {conteudo.citacoes.map((c, i) => (
              <li key={i} className={c.status === "conferida" ? undefined : "resumo-cita-falha"}>
                <b>{rotuloDaCitacao(c, "o texto")}</b>
                {c.trecho && <span> — “{c.trecho}”</span>}
              </li>
            ))}
          </ol>
        </details>
      )}
      <div className="resumo-acoes">
        <button type="button" className="btn btn-primaria" onClick={() => usar(conteudo)}>Usar este rascunho</button>
        <button type="button" className="btn btn-fantasma" onClick={voltar}>Voltar</button>
      </div>
    </section>
  );
}
