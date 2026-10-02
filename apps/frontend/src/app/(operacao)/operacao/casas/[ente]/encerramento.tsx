"use client";

// ADR-0018 (fatia 2) — o ENCERRAMENTO da câmara no console, como etapas (Eixo 4): exportação completa → confirmação de
// recebimento → guarda de 90 dias → destino do acervo público → apagamento (dois operadores) → encerrada. A Operação vê
// METADADO da exportação (estado, tamanho, código, manifesto resumido) e nunca o conteúdo: somos operadores dos dados
// (LGPD), a câmara é a controladora — quem baixa o arquivo é o administrador dela. O servidor confere tudo de novo
// (salvaguardas, two-person rule, a janela); esta tela mostra em que ponto a câmara está e pede o próximo passo.

import { useState } from "react";
import {
  type Casa,
  type Encerramento,
  type Exportacao,
  type Pedido,
  conferirDestino,
  conferirJustificativa,
  conferirOficio,
  definirDestinoAcervo,
  gerarExportacao,
  pedirApagamento,
  registrarOficio,
  retomarApagamento,
} from "@/lib/use-operacao";
import {
  type Etapa,
  codigoCurto,
  codigoEmGrupos,
  etapasDoEncerramento,
  exportacaoGerando,
  exportacaoParaConfirmar,
  inteiro,
  pedidoDeApagamento,
  rotuloEstadoEtapa,
  rotuloExportacao,
  tabelasDoResumo,
  tamanho,
} from "@/lib/encerramento-vista";
import { dataHora, dataLonga } from "../../quando";
import { PedidoAberto } from "./acesso-da-camara";

type Aviso = { tipo: "ok" | "erro"; texto: string } | null;

export function EncerramentoDaCamara({ casa, encerramento, pedidoAberto, token, aoMudar, agora }: {
  casa: Casa;
  encerramento: Encerramento | null | undefined;
  pedidoAberto: Pedido | null;
  token: string | null;
  aoMudar: () => void;
  agora?: Date;
}) {
  const [aviso, setAviso] = useState<Aviso>(null);
  if (!encerramento) return null;
  const enc = encerramento;
  const encerrada = casa.estado === "encerrado";
  // fora do encerramento (a câmara exportou por conta própria), a ficha só mostra o histórico das exportações
  const emSequencia = enc.emCurso || encerrada;
  const etapas = etapasDoEncerramento(casa, enc, agora);
  const ok = (texto: string) => {
    setAviso({ tipo: "ok", texto });
    aoMudar();
  };
  const erro = (texto: string) => setAviso({ tipo: "erro", texto });

  return (
    <section className="op-secao" aria-labelledby="titulo-encerramento">
      <h2 id="titulo-encerramento">{emSequencia ? "Encerramento" : "Exportações da câmara"}</h2>
      <p className="aj">
        {emSequencia
          ? "O fim do contrato segue uma ordem que não volta: a câmara recebe todos os dados, confirma o recebimento, " +
            "guardamos por 90 dias e só então apagamos. Você vê o tamanho, o código e o resumo do arquivo — nunca o " +
            "conteúdo: os dados são da câmara, e só o administrador dela baixa a exportação."
          : "A câmara gerou a exportação completa dos dados dela (portabilidade). Você vê só o tamanho, o código e o " +
            "resumo — o conteúdo é da câmara, e só o administrador dela baixa o arquivo."}
      </p>
      {aviso && (
        <p className={`op-aviso${aviso.tipo === "erro" ? " erro" : ""}`} role={aviso.tipo === "erro" ? "alert" : "status"}>
          {aviso.texto}
        </p>
      )}

      {emSequencia ? (
        <ol className="op-etapas">
          {etapas.map((e, i) => (
            <li key={e.chave} className={`op-etapa op-etapa-${e.estado}`} aria-current={e.estado === "atual" ? "step" : undefined}>
              <span className="n" aria-hidden="true">{e.estado === "feita" ? "✓" : i + 1}</span>
              <div className="corpo">
                <div className="cab">
                  <h3>{e.titulo}</h3>
                  <span className="st">{rotuloEstadoEtapa(e.estado)}</span>
                </div>
                <ConteudoDaEtapa etapa={e} casa={casa} enc={enc} pedidoAberto={pedidoAberto} token={token} ok={ok} erro={erro} />
              </div>
            </li>
          ))}
        </ol>
      ) : (
        <ListaDeExportacoes exportacoes={enc.exportacoes} />
      )}
    </section>
  );
}

function ConteudoDaEtapa({ etapa, casa, enc, pedidoAberto, token, ok, erro }: {
  etapa: Etapa; casa: Casa; enc: Encerramento; pedidoAberto: Pedido | null; token: string | null;
  ok: (t: string) => void; erro: (t: string) => void;
}) {
  const encerrada = casa.estado === "encerrado";
  switch (etapa.chave) {
    case "exportacao":
      return <EtapaExportacao casa={casa} enc={enc} token={token} ok={ok} erro={erro} />;
    case "confirmacao":
      return <EtapaConfirmacao enc={enc} encerrada={encerrada} token={token} ok={ok} erro={erro} />;
    case "guarda":
      return (
        <p className="aj">
          {enc.apagamentoPossivelEm
            ? etapa.estado === "feita"
              ? `Cumprida. O apagamento ficou possível em ${dataLonga(enc.apagamentoPossivelEm)}.`
              : `Apagamento possível a partir de ${dataLonga(enc.apagamentoPossivelEm)}. Até lá, a câmara só lê e pode exportar de novo.`
            : "Começa quando a câmara confirmar o recebimento da exportação."}
        </p>
      );
    case "destino":
      return <EtapaDestino casa={casa} enc={enc} token={token} ok={ok} erro={erro} />;
    case "apagamento":
      return <EtapaApagamento casa={casa} enc={enc} pedidoAberto={pedidoAberto} token={token} ok={ok} erro={erro} />;
    case "encerrada":
      return encerrada ? <ResumoDoApagamento enc={enc} /> : (
        <p className="aj">Depois do apagamento, o portal da câmara passa a dizer que ela não usa mais O Plenário e aponta para o destino do acervo.</p>
      );
  }
}

function DadosDaExportacao({ e }: { e: Exportacao }) {
  return (
    <dl className="op-dl">
      <dt>Situação</dt><dd>{rotuloExportacao(e)}{e.estado === "falhou" && e.erro ? ` — ${e.erro}` : ""}</dd>
      <dt>Pedida</dt>
      <dd>{e.solicitadaEm ? dataHora(e.solicitadaEm) : "—"} · {e.solicitadaPor === "operador" ? "pela Operação" : "pela câmara"}</dd>
      {e.estado === "pronta" && (
        <>
          <dt>Tamanho</dt><dd>{tamanho(e.bytes)}</dd>
          <dt>Código (SHA-256)</dt><dd><code className="op-codigo" title={e.sha256 ?? undefined}>{e.sha256 ? codigoEmGrupos(e.sha256) : "—"}</code></dd>
          {e.manifesto && Object.keys(e.manifesto).length > 0 && (
            <>
              <dt>Manifesto</dt>
              <dd>
                {Object.entries(e.manifesto).map(([k, v]) => (
                  <span key={k} className="op-manifesto">
                    {k}: {typeof v === "object" && v !== null && "itens" in v ? `${inteiro((v as { itens: number }).itens)} itens` : String(v)}
                  </span>
                ))}
              </dd>
            </>
          )}
        </>
      )}
    </dl>
  );
}

function EtapaExportacao({ casa, enc, token, ok, erro }: {
  casa: Casa; enc: Encerramento; token: string | null; ok: (t: string) => void; erro: (t: string) => void;
}) {
  const [ocupado, setOcupado] = useState(false);
  const ultima = enc.exportacoes[0] ?? null;
  const gerando = exportacaoGerando(enc.exportacoes);

  async function gerar() {
    if (ocupado) return;
    setOcupado(true);
    const r = await gerarExportacao(casa.enteId, token);
    setOcupado(false);
    if (!r.ok) return erro(r.mensagem);
    ok(r.dados.estado === "pronta" ? "Exportação pronta." : "Exportação pedida. O arquivo está sendo gerado.");
  }

  return (
    <>
      {ultima ? <DadosDaExportacao e={ultima} /> : <p className="aj">Nenhuma exportação gerada ainda.</p>}
      {enc.emCurso && (
        enc.exportacaoDisponivel ? (
          <div className="op-acoes">
            <button className="btn btn-contorno" type="button" onClick={gerar} disabled={ocupado || !!gerando} aria-busy={ocupado}>
              {ocupado ? "Pedindo…" : gerando ? "Gerando…" : ultima ? "Gerar de novo" : "Gerar exportação"}
            </button>
            <span className="aj">A câmara também pode gerar pela área do administrador.</span>
          </div>
        ) : (
          <p className="aj">A exportação completa ainda não está disponível nesta instalação.</p>
        )
      )}
    </>
  );
}

function EtapaConfirmacao({ enc, encerrada, token, ok, erro }: {
  enc: Encerramento; encerrada: boolean; token: string | null; ok: (t: string) => void; erro: (t: string) => void;
}) {
  const [texto, setTexto] = useState("");
  const [erroCampo, setErroCampo] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const c = enc.confirmacao;
  const pendente = exportacaoParaConfirmar(enc.exportacoes);

  if (c) {
    return (
      <p className="aj">
        {c.confirmadaPor === "oficio"
          ? <>Registrada por ofício em {c.confirmadaEm ? dataLonga(c.confirmadaEm) : "—"}: “{c.oficio}”.</>
          : <>Confirmada pelo administrador da câmara em {c.confirmadaEm ? dataLonga(c.confirmadaEm) : "—"}.</>}
        {" "}Arquivo com o código <code className="op-codigo">{codigoCurto(c.sha256)}</code>. A confirmação não se desfaz.
      </p>
    );
  }
  if (encerrada || !enc.emCurso) return null;

  async function registrar(e: React.FormEvent) {
    e.preventDefault();
    if (!pendente) return;
    const ec = conferirOficio(texto);
    setErroCampo(ec);
    if (ec || ocupado) return;
    setOcupado(true);
    const r = await registrarOficio(pendente.id, texto, token);
    setOcupado(false);
    if (!r.ok) return erro(r.mensagem);
    setTexto("");
    ok("Confirmação por ofício registrada. A guarda de 90 dias começou.");
  }

  return (
    <>
      <p className="aj">
        O administrador da câmara confirma na área dele, vendo o código do arquivo. Se a câmara confirmou por ofício,
        registre aqui — o texto fica na atuação selada.
      </p>
      {pendente ? (
        <form className="op-form op-form-curto" onSubmit={registrar} noValidate>
          <div className="op-campo">
            <label htmlFor="texto-oficio">Ofício de recebimento (exportação {codigoCurto(pendente.sha256)})</label>
            <textarea id="texto-oficio" rows={2} value={texto} onChange={(e) => setTexto(e.target.value)}
              aria-invalid={!!erroCampo} aria-describedby="texto-oficio-dica" />
            <span className="dica" id="texto-oficio-dica">Número, data e quem assina. Ex.: “Ofício 12/2026 da Mesa Diretora, de 03/10/2026”.</span>
            {erroCampo && <span className="erro">{erroCampo}</span>}
          </div>
          <div className="op-acoes">
            <button className="btn btn-contorno" type="submit" disabled={ocupado} aria-busy={ocupado}>
              {ocupado ? "Registrando…" : "Registrar ofício"}
            </button>
          </div>
        </form>
      ) : (
        <p className="aj">Assim que uma exportação ficar pronta, ela pode ser confirmada.</p>
      )}
    </>
  );
}

function EtapaDestino({ casa, enc, token, ok, erro }: {
  casa: Casa; enc: Encerramento; token: string | null; ok: (t: string) => void; erro: (t: string) => void;
}) {
  const [url, setUrl] = useState(enc.destinoAcervoUrl ?? "");
  const [erroCampo, setErroCampo] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);

  async function salvar(e: React.FormEvent) {
    e.preventDefault();
    const ec = conferirDestino(url);
    setErroCampo(ec);
    if (ec || ocupado) return;
    setOcupado(true);
    const r = await definirDestinoAcervo(casa.enteId, url, token);
    setOcupado(false);
    if (!r.ok) return erro(r.mensagem);
    ok(url.trim() ? "Destino do acervo salvo. O portal da câmara vai apontar para ele." : "Destino do acervo retirado.");
  }

  return (
    <>
      <p className="aj">
        Opcional. Para onde a câmara levou os documentos públicos (leis, atas, votações). Depois do encerramento, o portal
        mostra este endereço a quem chegar pelo link antigo.
      </p>
      <form className="op-form op-form-curto" onSubmit={salvar} noValidate>
        <div className="op-campo">
          <label htmlFor="destino-acervo">Endereço do acervo público</label>
          <input id="destino-acervo" type="url" inputMode="url" placeholder="https://" value={url}
            onChange={(e) => setUrl(e.target.value)} aria-invalid={!!erroCampo} />
          {erroCampo && <span className="erro">{erroCampo}</span>}
        </div>
        <div className="op-acoes">
          <button className="btn btn-contorno" type="submit" disabled={ocupado} aria-busy={ocupado}>
            {ocupado ? "Salvando…" : "Salvar destino"}
          </button>
        </div>
      </form>
    </>
  );
}

function EtapaApagamento({ casa, enc, pedidoAberto, token, ok, erro }: {
  casa: Casa; enc: Encerramento; pedidoAberto: Pedido | null; token: string | null;
  ok: (t: string) => void; erro: (t: string) => void;
}) {
  const [justificativa, setJustificativa] = useState("");
  const [erroCampo, setErroCampo] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const pedido = pedidoDeApagamento(pedidoAberto);

  if (casa.estado === "encerrado") return <p className="aj">Feito. Os dados da câmara foram apagados.</p>;

  if (enc.apagamentoPendente) {
    const retomar = async () => {
      if (ocupado) return;
      setOcupado(true);
      const r = await retomarApagamento(casa.enteId, token);
      setOcupado(false);
      if (!r.ok) return erro(r.mensagem);
      if (r.dados.efeito === "encerrada") return ok("Apagamento concluído. A câmara está encerrada.");
      erro(`O apagamento parou de novo${r.dados.erro ? ` (${r.dados.erro})` : ""}. Ele continua de onde parou: tente outra vez.`);
    };
    return (
      <div className="op-aviso erro" role="group" aria-label="Apagamento interrompido">
        <p className="op-just">
          <b>O apagamento foi aprovado e parou no meio.</b> Não há volta: a câmara não pode ser reativada. Retomar continua de
          onde parou.
        </p>
        <button className="btn op-btn-perigo" type="button" onClick={retomar} disabled={ocupado} aria-busy={ocupado}>
          {ocupado ? "Retomando…" : "Retomar o apagamento"}
        </button>
      </div>
    );
  }

  if (pedido) {
    return <PedidoAberto pedido={pedido} token={token} aoConcluir={ok} aoFalhar={erro} />;
  }

  if (!enc.apagamentoDisponivel) return <p className="aj">O apagamento ainda não está disponível nesta instalação.</p>;

  if (!enc.podePedirApagamento) {
    return (
      <p className="aj">
        {enc.apagamentoPossivelEm
          ? `Pode ser pedido a partir de ${dataLonga(enc.apagamentoPossivelEm)}, depois da guarda.`
          : "Só depois que a câmara confirmar o recebimento e a guarda de 90 dias passar."}
        {" "}Um operador pede e outro aprova.
      </p>
    );
  }

  async function pedir(e: React.FormEvent) {
    e.preventDefault();
    const ej = conferirJustificativa(justificativa);
    setErroCampo(ej);
    if (ej || ocupado) return;
    setOcupado(true);
    const r = await pedirApagamento(casa.enteId, justificativa, token);
    setOcupado(false);
    if (!r.ok) return erro(r.mensagem);
    setJustificativa("");
    ok("Apagamento pedido. Outro operador precisa aprovar — a aprovação apaga os dados na hora.");
  }

  return (
    <form className="op-form op-form-curto" onSubmit={pedir} noValidate>
      <p className="aj">
        A guarda passou. Apagar remove os dados da câmara do banco, dos arquivos, do login e da IA. Fica conosco só o
        registro: a data, o pedido e o código da exportação entregue.
      </p>
      <div className="op-campo">
        <label htmlFor="justificativa-apagamento">Justificativa do apagamento</label>
        <textarea id="justificativa-apagamento" rows={2} value={justificativa}
          onChange={(e) => setJustificativa(e.target.value)} aria-invalid={!!erroCampo} />
        {erroCampo && <span className="erro">{erroCampo}</span>}
      </div>
      <div className="op-acoes">
        <button className="btn op-btn-perigo" type="submit" disabled={ocupado} aria-busy={ocupado}>
          {ocupado ? "Pedindo…" : "Pedir apagamento"}
        </button>
      </div>
    </form>
  );
}

function ResumoDoApagamento({ enc }: { enc: Encerramento }) {
  const r = enc.apagamento;
  const tabelas = tabelasDoResumo(r?.tabelas);
  return (
    <>
      <p className="aj">
        Encerrada em {enc.encerradaEm ? dataLonga(enc.encerradaEm) : "—"}. Este é o registro de que entregamos e de que
        apagamos — fica conosco, junto da atuação selada.
      </p>
      <dl className="op-dl">
        <dt>Linhas apagadas</dt><dd>{inteiro(r?.linhasTotal)} em {inteiro(tabelas.length)} tabelas</dd>
        <dt>Arquivos apagados</dt><dd>{inteiro(r?.objetos)}</dd>
        <dt>Login da câmara</dt><dd>{r?.["realmApagado?"] ? "Removido" : "—"}</dd>
        <dt>Exportação entregue</dt>
        <dd>
          <code className="op-codigo">{r?.exportacao?.sha256 ? codigoEmGrupos(r.exportacao.sha256) : "—"}</code>
          {r?.exportacao?.confirmadaEm && (
            <span className="op-manifesto">
              confirmada em {dataLonga(r.exportacao.confirmadaEm)}
              {r.exportacao.confirmadaPor === "oficio" ? " por ofício" : " pelo administrador da câmara"}
            </span>
          )}
        </dd>
        <dt>Acervo público</dt>
        <dd>{enc.destinoAcervoUrl ? <a className="op-link" href={enc.destinoAcervoUrl} rel="noopener noreferrer" target="_blank">{enc.destinoAcervoUrl}</a> : "Não informado"}</dd>
      </dl>
      {tabelas.length > 0 && (
        <details className="op-tabelas">
          <summary>Tabelas apagadas ({inteiro(tabelas.length)})</summary>
          <ul>
            {tabelas.map(([t, n]) => <li key={t}><code>{t}</code> <b>{inteiro(n)}</b></li>)}
          </ul>
        </details>
      )}
    </>
  );
}

function ListaDeExportacoes({ exportacoes }: { exportacoes: Exportacao[] }) {
  return (
    <div className="op-cartao">
      {exportacoes.map((e) => (
        <div className="op-cartao-corpo op-exportacao" key={e.id}>
          <DadosDaExportacao e={e} />
        </div>
      ))}
    </div>
  );
}
