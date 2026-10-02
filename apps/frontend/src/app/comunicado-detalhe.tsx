"use client";

// UM comunicado (ADR-0020) — compartilhado pelo interno (/comunicados/:id) e pelo app do vereador (/notificacoes/:id).
// Arquétipo FICHA (cabeçalho + seções + ações) com o texto numa ILHA-PAPEL: o comunicado é documento — protocolado,
// imutável, clara nos dois temas (GUIDELINES §1, "documento/edital/ata = ilha-papel").
//
// O que a tela faz e por quê:
//   - abrir o comunicado É a leitura: o servidor grava "lido" ao entregar o detalhe ao destinatário (Eixo 4). A tela
//     só avisa o contador do topo (`avisarCaixaMudou`) quando o detalhe chega;
//   - "Estou ciente" aparece só quando o comunicado pede ciência, quem lê é destinatário e ainda não deu. Um toque, sem
//     diálogo de confirmação: a ADR quer "um toque", e a frase ao lado diz que a marca não se desfaz;
//   - o texto sai como TEXTO (quebras de linha preservadas por `white-space: pre-wrap`), nunca como HTML;
//   - o painel de leitura (quem recebeu, leu e deu ciência) só para quem pode (`podeVerLeitura`: remetente, secretaria,
//     administração) — e é dele também a ação "Corrigir (enviar substituto)": o comunicado não se edita (Eixo 5).

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  avisarCaixaMudou,
  baixarAnexoComToken,
  registrarCiencia,
  useComunicado,
  useLeituraDoComunicado,
} from "@/lib/use-comunicados";
import { ROTAS_COMUNICACAO, type AnexoOut, type ComunicadoOut } from "@/lib/contrato-comunicacao";
import {
  NOME_DO_TIPO_DE_OBJETO,
  estadoDaCiencia,
  hrefDoObjetoLigado,
  instante,
  linhaPara,
  marcaCurta,
  ordenarLinhasDeLeitura,
  resumoDaLeitura,
  rotuloDoObjetoLigado,
  tamanhoLegivel,
} from "@/lib/comunicacao-vista";
import "./comunicacao.css";

export function ComunicadoDetalhe({
  id,
  voltar,
  hrefDoComunicado,
}: {
  id: string | null;
  voltar: { href: string; rotulo: string };
  /** A tela de OUTRO comunicado nesta superfície (o substituto, o substituído). */
  hrefDoComunicado: (id: string) => string;
}) {
  const { token } = useAuth();
  const { estado, setEstado } = useComunicado(token, id);
  const avisado = useRef<string | null>(null);

  // o servidor gravou "lido" ao entregar o detalhe: o número do topo muda. Uma vez por comunicado aberto.
  useEffect(() => {
    if (estado.fase === "pronto" && avisado.current !== estado.dado.id) {
      avisado.current = estado.dado.id;
      avisarCaixaMudou();
    }
  }, [estado]);

  return (
    <div className="com">
      <Link className="com-voltar" href={comToken(voltar.href, token)} prefetch={false}>
        ← {voltar.rotulo}
      </Link>
      {estado.fase === "carregando" && <p role="status">Carregando o comunicado…</p>}
      {estado.fase === "erro" && (
        <p className="com-erro" role="alert">
          {estado.mensagem}
        </p>
      )}
      {estado.fase === "pronto" && (
        <Comunicado
          c={estado.dado}
          token={token}
          hrefDoComunicado={hrefDoComunicado}
          aoMudar={(c) => setEstado({ fase: "pronto", dado: c })}
        />
      )}
    </div>
  );
}

function Comunicado({
  c,
  token,
  hrefDoComunicado,
  aoMudar,
}: {
  c: ComunicadoOut;
  token: string | null;
  hrefDoComunicado: (id: string) => string;
  aoMudar: (c: ComunicadoOut) => void;
}) {
  const hrefObjeto = hrefDoObjetoLigado(c.objeto);
  return (
    <article className="com-ficha" aria-labelledby="com-titulo">
      <header className="com-cabeca">
        <p className="com-ref">
          <span className="com-protocolo">{c.protocolo}</span>
          {c.substituidoPor && <span className="chip chip-neutro">Substituído</span>}
          {c.exigeCiencia && <span className="chip chip-info">Pede ciência</span>}
        </p>
        <h1 id="com-titulo">{c.assunto}</h1>
        <p className="com-meta">
          De <b>{c.remetente.nome}</b> · enviado em <time dateTime={c.enviadoEm}>{instante(c.enviadoEm)}</time>
        </p>
        {c.destinos.length > 0 && <p className="com-meta">{linhaPara(c.destinos)}</p>}
      </header>

      {c.substituidoPor && (
        <p className="com-nota com-nota-forte" role="note">
          Este comunicado foi substituído pelo{" "}
          <Link href={comToken(hrefDoComunicado(c.substituidoPor.id), token)} prefetch={false}>
            {c.substituidoPor.protocolo}
          </Link>
          . Leia a versão corrigida; este fica guardado como registro.
        </p>
      )}
      {c.substitui && (
        <p className="com-nota" role="note">
          Este comunicado substitui o{" "}
          <Link href={comToken(hrefDoComunicado(c.substitui.id), token)} prefetch={false}>
            {c.substitui.protocolo}
          </Link>
          , que segue guardado com as marcas de leitura dele.
        </p>
      )}

      <Ciencia c={c} token={token} aoMudar={aoMudar} />

      <div className="ilha-papel com-corpo">{c.corpo}</div>

      {c.objeto && (
        <p className="com-objeto">
          <span className="com-objeto-tipo">{NOME_DO_TIPO_DE_OBJETO[c.objeto.tipo] ?? "Item"} vinculado</span>
          {hrefObjeto ? (
            <Link href={comToken(hrefObjeto, token)} prefetch={false}>
              {rotuloDoObjetoLigado(c.objeto)} →
            </Link>
          ) : (
            <span className="com-objeto-id">{c.objeto.id}</span>
          )}
        </p>
      )}

      {c.anexos.length > 0 && <Anexos comunicadoId={c.id} anexos={c.anexos} token={token} />}

      {c.podeVerLeitura && <PainelDeLeitura c={c} token={token} />}

      {c.podeVerLeitura && !c.substituidoPor && (
        <section className="com-corrigir" aria-labelledby="com-corrigir-t">
          <h2 id="com-corrigir-t">Corrigir</h2>
          <p className="com-dica">
            O comunicado enviado não muda. Para corrigir, envie um novo que o substitui: este passa a mostrar “substituído
            por”, e as marcas de leitura dos dois ficam guardadas.
          </p>
          <Link
            className="btn btn-contorno btn-mini"
            href={comToken(`/comunicados/novo?substitui=${encodeURIComponent(c.id)}`, token)}
            prefetch={false}
          >
            Corrigir (enviar substituto)
          </Link>
        </section>
      )}
    </article>
  );
}

function Ciencia({ c, token, aoMudar }: { c: ComunicadoOut; token: string | null; aoMudar: (c: ComunicadoOut) => void }) {
  const [agora] = useState(() => Date.now());
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const estado = estadoDaCiencia(c, agora);
  if (estado === "nao-exige") return null;

  if (estado === "nao-destinatario") {
    return (
      <p className="com-nota" role="note">
        Este comunicado pede ciência aos destinatários{c.cienciaAte ? ` até ${instante(c.cienciaAte)}` : ""}.
      </p>
    );
  }
  if (estado === "dada") {
    return (
      <p className="com-ciente" role="status">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden="true">
          <path d="M5 12l5 5L20 6" />
        </svg>
        Você registrou ciência em {instante(c.minhasMarcas?.cienteEm)}.
      </p>
    );
  }

  async function darCiencia() {
    setEnviando(true);
    setErro(null);
    const r = await registrarCiencia(token, c.id);
    setEnviando(false);
    if (!r.ok) {
      setErro(r.mensagem);
      return;
    }
    const marcas = c.minhasMarcas ?? { recebidoEm: null, lidoEm: null, cienteEm: null };
    aoMudar({ ...c, minhasMarcas: { ...marcas, cienteEm: r.dado.cienteEm } });
    avisarCaixaMudou();
  }

  return (
    <section className={estado === "vencida" ? "com-pede-ciencia com-pede-vencida" : "com-pede-ciencia"} aria-labelledby="com-ciencia-t">
      <h2 id="com-ciencia-t">Ciência pedida</h2>
      <p>
        Quem enviou pediu que você confirme que tomou conhecimento deste comunicado
        {c.cienciaAte && estado === "pendente" ? `, até ${instante(c.cienciaAte)}` : ""}.
      </p>
      {estado === "vencida" && c.cienciaAte && (
        <p className="com-vencida">O prazo para a ciência venceu em {instante(c.cienciaAte)}.</p>
      )}
      <div className="com-acoes">
        <button type="button" className="btn btn-primaria" onClick={() => void darCiencia()} disabled={enviando}>
          {enviando ? "Registrando…" : "Estou ciente"}
        </button>
        <span className="com-dica">Fica registrado com a data e a hora, e não se desfaz.</span>
      </div>
      {erro && (
        <p className="com-erro" role="alert">
          {erro}
        </p>
      )}
    </section>
  );
}

function Anexos({ comunicadoId, anexos, token }: { comunicadoId: string; anexos: AnexoOut[]; token: string | null }) {
  const [erro, setErro] = useState<string | null>(null);
  return (
    <section className="com-anexos" aria-labelledby="com-anexos-t">
      <h2 id="com-anexos-t">Anexos</h2>
      <ul>
        {anexos.map((a) => (
          <li key={a.id}>
            <span className="com-anexo-nome">{a.nome}</span>
            <span className="com-anexo-info">{[tamanhoLegivel(a.bytes), a.tipoMidia].filter(Boolean).join(" · ")}</span>
            {token ? (
              // modo dev: o token viaja só no header, então baixa pelos bytes (use-comunicados.ts)
              <button
                type="button"
                className="btn btn-contorno btn-mini"
                aria-label={`Baixar ${a.nome}`}
                onClick={async () => {
                  setErro(null);
                  const r = await baixarAnexoComToken(token, comunicadoId, a);
                  if (!r.ok) setErro(r.mensagem);
                }}
              >
                Baixar
              </button>
            ) : (
              <a className="btn btn-contorno btn-mini" href={ROTAS_COMUNICACAO.anexo(comunicadoId, a.id)} download={a.nome} aria-label={`Baixar ${a.nome}`}>
                Baixar
              </a>
            )}
          </li>
        ))}
      </ul>
      {erro && (
        <p className="com-erro" role="alert">
          {erro}
        </p>
      )}
    </section>
  );
}

function Marca({ iso }: { iso: string | null }) {
  const curta = marcaCurta(iso);
  if (curta && iso) return <time dateTime={iso}>{curta}</time>;
  return (
    <span className="com-falta">
      <span aria-hidden="true">—</span>
      <span className="sr-only">ainda não</span>
    </span>
  );
}

function PainelDeLeitura({ c, token }: { c: ComunicadoOut; token: string | null }) {
  const { estado, recarregar } = useLeituraDoComunicado(token, c.id);
  return (
    <section className="com-leitura" aria-labelledby="com-leitura-t">
      <div className="com-leitura-cab">
        <h2 id="com-leitura-t">Quem recebeu e leu</h2>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={recarregar}>
          Atualizar
        </button>
      </div>
      {estado.fase === "carregando" && <p role="status">Carregando a leitura…</p>}
      {estado.fase === "erro" && (
        <p className="com-erro" role="alert">
          {estado.mensagem}
        </p>
      )}
      {estado.fase === "pronto" && (
        <>
          <p className="com-leitura-resumo">{resumoDaLeitura(estado.dado.totais, c.exigeCiencia)}</p>
          <p className="com-dica">
            “Recebido” é quando o comunicado chegou à caixa da pessoa; “lido”, quando ela o abriu
            {c.exigeCiencia ? "; “ciente”, quando ela confirmou com o botão — a única marca que vale como prova de conhecimento" : ""}.
          </p>
          {estado.dado.linhas.length === 0 ? (
            <p className="com-dica">Ninguém na lista.</p>
          ) : (
            <div className="com-rolagem" role="region" aria-label="Leitura por pessoa" tabIndex={0}>
              <table className="com-tabela">
                <caption className="sr-only">Leitura do {c.protocolo}, uma linha por pessoa</caption>
                <thead>
                  <tr>
                    <th scope="col">Pessoa</th>
                    <th scope="col">Caminho</th>
                    <th scope="col">Recebido</th>
                    <th scope="col">Lido</th>
                    {c.exigeCiencia && <th scope="col">Ciente</th>}
                  </tr>
                </thead>
                <tbody>
                  {ordenarLinhasDeLeitura(estado.dado.linhas, c.exigeCiencia).map((l) => (
                    <tr key={l.identidadeId} className={l.vencido ? "com-linha-vencida" : undefined}>
                      <th scope="row">
                        <span className="com-pessoa">{l.nome}</span>
                        {l.vencido && <span className="chip chip-risco">Prazo vencido</span>}
                      </th>
                      <td>{l.via}</td>
                      <td>
                        <Marca iso={l.recebidoEm} />
                      </td>
                      <td>
                        <Marca iso={l.lidoEm} />
                      </td>
                      {c.exigeCiencia && (
                        <td>
                          <Marca iso={l.cienteEm} />
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
