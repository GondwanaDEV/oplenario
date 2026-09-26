"use client";

// "Novo requerimento" do vereador (fatia 2a do pedido do stakeholder: "a construção do requerimento seria no
// login de vereador … o texto formatado, e já dar a opção dele assinar"). Dois passos, no mesmo ritual da
// assinatura do parecer (assinatura-2-toques.html):
//   1. Escrever — escolhe um MODELO da Casa, dá a ementa e preenche os campos que o modelo pede.
//   2. Revisar e assinar — o SERVIDOR monta o texto (autor do login, data de hoje) e a tela o mostra na
//      ilha-papel; "Revisar e assinar" abre a folha de confirmação; confirmar assina e protocola.
// Fatia 2c: com COAUTORES escolhidos no passo 1, o passo 2 não assina — "Enviar para subscrição" grava o texto e
// convida os colegas; o autor assina e protocola depois, na página da proposta (/requerimento/proposta/:id).
// A IA de redação (copiloto, feature 3.11) entra depois NESTE formulário — hoje o texto vem do modelo.
//
// A folha de confirmação e a ilha-papel REUSAM o CSS da assinatura do parecer (mesmo ritual, mesma
// aparência — um só lugar para evoluir quando a assinatura real, ICP/gov.br, entrar). O que é só desta
// tela mora em requerimento.css.

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useNovoRequerimento } from "@/lib/use-novo-requerimento";
import { useColegas } from "@/lib/use-subscricao";
import { Coautores } from "../coautores";
import { campoLongo, faltando, podeVerPrevia, rotuloCampo, seloDaAssinatura } from "@/lib/requerimento-vista";
import type { RequerimentoProtocoladoOut } from "@/lib/contrato-legislativo.gen";
import { comToken } from "@/lib/nav";
import "../../parecer/[id]/assinar/assinar.css";
import "./requerimento.css";
import "../subscricao.css";

export default function PaginaNovoRequerimento() {
  const router = useRouter();
  const { token } = useAuth();
  const { modelos, estadoModelos, estado, erro, previa, protocolar, enviarParaSubscricao } = useNovoRequerimento(token);
  const { colegas } = useColegas(token);
  const [coautores, setCoautores] = useState<string[]>([]);
  const coletivo = coautores.length > 0;

  const [modeloId, setModeloId] = useState<string | null>(null);
  const [ementa, setEmenta] = useState("");
  const [valores, setValores] = useState<Record<string, string>>({});
  const [texto, setTexto] = useState<string | null>(null);
  const [sheetAberta, setSheetAberta] = useState(false);
  const [recibo, setRecibo] = useState<RequerimentoProtocoladoOut | null>(null);

  const modelo = modelos.find((m) => m.id === modeloId) ?? null;
  const campos = modelo?.campos ?? [];
  // Só os campos do modelo ESCOLHIDO vão ao servidor — o que foi digitado para outro modelo não vaza.
  const camposDoModelo = Object.fromEntries(campos.map((c) => [c, (valores[c] ?? "").trim()]));
  const pronto = podeVerPrevia({ modeloId, ementa, campos, valores });
  const enviando = estado === "enviando";

  async function verTexto() {
    if (!modeloId || !pronto) return;
    try {
      setTexto(await previa({ modeloId, campos: camposDoModelo }));
    } catch {
      // a mensagem do servidor já está em `erro`
    }
  }

  async function confirmar() {
    if (!modeloId) return;
    try {
      const r = await protocolar({ modeloId, campos: camposDoModelo, ementa: ementa.trim() });
      setSheetAberta(false);
      setRecibo(r);
    } catch {
      // a folha mostra `erro`
    }
  }

  async function enviarSubscricao() {
    if (!modeloId) return;
    try {
      const p = await enviarParaSubscricao({ modeloId, campos: camposDoModelo, ementa: ementa.trim(), coautores });
      router.push(comToken(`/requerimento/proposta/${p.id}`, token));
    } catch {
      // a mensagem do servidor já está em `erro`
    }
  }

  if (recibo) {
    const selo = seloDaAssinatura(recibo.assinaturaAlgoritmo);
    return (
      <div className="assinar-pagina req-feito" role="status">
        <h1 className="assinar-titulo">
          Requerimento nº {recibo.sequencial}/{recibo.ano} protocolado
        </h1>
        <p className="req-feito-sub">Ele já aparece em “Suas proposições” e segue a tramitação da Casa.</p>
        <p className={`req-selo${selo.provisorio ? " provisorio" : ""}`}>{selo.texto}</p>
        <Link className="btn btn-primaria req-voltar-inicio" href={comToken("/vereador", token)}>
          Voltar ao início
        </Link>
      </div>
    );
  }

  if (estadoModelos === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }
  if (estadoModelos === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível abrir o formulário</h1>
        <p>Confira se o seu cadastro de vereador está vinculado a este login.</p>
      </main>
    );
  }

  return (
    <div className="assinar-pagina">
      <button type="button" className="btn btn-fantasma btn-mini assinar-voltar" onClick={() => router.back()}>
        ← Voltar
      </button>
      <h1 className="assinar-titulo">Novo requerimento</h1>

      <div className="toques" aria-hidden="true">
        <span className={`toque ${texto === null ? "on" : ""}`}>
          <span className="n">1</span>Escrever
        </span>
        <span className="liga" />
        <span className={`toque ${texto !== null ? "on" : ""}`}>
          <span className="n">2</span>Revisar e assinar
        </span>
      </div>

      {texto === null ? (
        modelos.length === 0 ? (
          <p className="vazio">
            A Casa ainda não cadastrou modelos de requerimento. A secretaria cadastra na aba “Modelos” do Expediente.
          </p>
        ) : (
          <form
            className="req-form"
            onSubmit={(e) => {
              e.preventDefault();
              void verTexto();
            }}
          >
            <fieldset className="req-modelos">
              <legend>Tipo de requerimento</legend>
              {modelos.map((m) => (
                <div key={m.id} className="req-modelo">
                  <input
                    type="radio"
                    id={`modelo-${m.id}`}
                    name="modelo"
                    value={m.id}
                    checked={modeloId === m.id}
                    onChange={() => setModeloId(m.id)}
                  />
                  <label htmlFor={`modelo-${m.id}`}>{m.nome}</label>
                </div>
              ))}
            </fieldset>

            {modelo && (
              <>
                <div className="req-campo">
                  <label htmlFor="req-ementa">Ementa</label>
                  <input
                    id="req-ementa"
                    value={ementa}
                    maxLength={2000}
                    onChange={(e) => setEmenta(e.target.value)}
                    aria-describedby="req-ementa-ajuda"
                  />
                  <span id="req-ementa-ajuda" className="req-ajuda">
                    Uma linha que resume o pedido — é o que aparece nas listas e no portal.
                  </span>
                </div>
                {campos.map((c) => (
                  <div key={c} className="req-campo">
                    <label htmlFor={`req-${c}`}>{rotuloCampo(c)}</label>
                    {campoLongo(c) ? (
                      <textarea
                        id={`req-${c}`}
                        rows={5}
                        maxLength={2000}
                        value={valores[c] ?? ""}
                        onChange={(e) => setValores((v) => ({ ...v, [c]: e.target.value }))}
                      />
                    ) : (
                      <input
                        id={`req-${c}`}
                        maxLength={2000}
                        value={valores[c] ?? ""}
                        onChange={(e) => setValores((v) => ({ ...v, [c]: e.target.value }))}
                      />
                    )}
                  </div>
                ))}
                <p className="req-ajuda">Seu nome e a data de hoje entram no texto automaticamente.</p>
                <Coautores colegas={colegas} selecionados={coautores} onMudar={setCoautores} />
                {erro && (
                  <p role="alert" className="erro-inline">
                    {erro}
                  </p>
                )}
              </>
            )}

            <div className="assinar-bar">
              <div className="assinar-bar-in">
                <button className="btn btn-primaria" type="submit" disabled={!pronto || enviando}>
                  {enviando ? "Montando o texto…" : "Ver o texto formatado"}
                </button>
                {modelo && !pronto && faltando(campos, valores).length > 0 && (
                  <p className="req-falta">Falta preencher: {faltando(campos, valores).map(rotuloCampo).join(", ")}.</p>
                )}
              </div>
            </div>
          </form>
        )
      ) : (
        <>
          <section className="papel" aria-label="Documento a assinar">
            <div className="cab">
              <h2>{modelo?.nome ?? "Requerimento"}</h2>
            </div>
            <div className="corpo">
              <p className="req-texto">{texto}</p>
            </div>
          </section>

          {coletivo ? (
            <div className="sumario">
              <h3>Antes do protocolo, as subscrições</h3>
              <p>
                {coautores.length === 1 ? "O colega convidado recebe" : `Os ${coautores.length} colegas convidados recebem`}{" "}
                o pedido e confirmam com a própria assinatura, sobre <b>este texto</b>.
              </p>
              <p>
                Você acompanha as respostas e <b>protocola quando quiser</b>. Quem não tiver confirmado até lá não consta.
              </p>
            </div>
          ) : (
            <div className="sumario">
              <h3>O que você está assinando</h3>
              <p>
                Ao confirmar, o requerimento é <b>protocolado</b> com número oficial e entra na tramitação da Casa.
              </p>
              <p>
                A assinatura fica <b>registrada</b> com o seu nome, data e hora.
              </p>
            </div>
          )}
          <button type="button" className="btn btn-fantasma req-editar" onClick={() => setTexto(null)}>
            Editar
          </button>

          <div className="assinar-bar">
            <div className="assinar-bar-in">
              {coletivo ? (
                <>
                  {erro && (
                    <p role="alert" className="erro-inline">
                      {erro}
                    </p>
                  )}
                  <button className="btn btn-primaria" type="button" onClick={enviarSubscricao} disabled={enviando}>
                    {enviando ? "Enviando…" : "Enviar para subscrição"}
                  </button>
                </>
              ) : (
                <button className="btn btn-primaria" type="button" onClick={() => setSheetAberta(true)}>
                  Revisar e assinar
                </button>
              )}
            </div>
          </div>
        </>
      )}

      {sheetAberta && (
        <div className="scrim" role="dialog" aria-modal="true" aria-labelledby="sh-tit">
          <div className="sheet">
            <h3 id="sh-tit">Confirmar assinatura</h3>
            <p className="sub">Confira o texto uma última vez. Depois de protocolado, ele não pode ser alterado.</p>
            {estado === "erro" && erro && (
              <p role="status" className="erro-inline">
                Não foi possível protocolar: {erro}
              </p>
            )}
            <div className="acoes">
              <button className="btn btn-primaria" type="button" onClick={confirmar} disabled={enviando}>
                {enviando ? "Protocolando…" : "Confirmar e protocolar"}
              </button>
              <button className="btn btn-fantasma" type="button" onClick={() => setSheetAberta(false)}>
                Cancelar
              </button>
            </div>
            {/* Mesmo [GAP] da assinatura do parecer: o servidor grava hoje o selo provisório 'STUB-ICP-v0'
                (sem ICP-Brasil/gov.br real — decisão pendente da §22.5). A copy não promete validade ICP; o
                recibo mostra o selo como provisório (requerimento-vista.ts/seloDaAssinatura). */}
            <p className="legal">A assinatura é registrada no sistema junto com o texto, e não pode ser desfeita.</p>
          </div>
        </div>
      )}
    </div>
  );
}
