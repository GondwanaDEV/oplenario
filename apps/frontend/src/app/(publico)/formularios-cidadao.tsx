"use client";

// Os formulários de escrita do cidadão no portal (ADR-0015): abrir pedido de e-SIC, exercer um direito LGPD e
// registrar manifestação de ouvidoria. Porte de ouvidoria.html (o único com o formulário desenhado campo a campo);
// e-SIC e LGPD seguem o mesmo arquétipo (hero + faixa da lei + form-card + trilho). Sem sessão, o form-card vira o
// convite do gov.br, que volta a ESTE formulário. Enviado, vira o recibo: o protocolo é a prova e o marco do prazo.
//
// Fora, por não existir no backend: o anexo da ouvidoria e o cartão "Ouvidor responsável" do design.

import { useState, type ReactNode } from "react";
import { buscarPublico } from "@/lib/portal-api";
import { hrefEntrarComGovbr } from "@/lib/participar-vista";
import {
  DIREITOS_LGPD,
  LIMITES,
  MENSAGEM_CAMPO,
  TIPOS_MANIFESTACAO,
  corpoEsic,
  corpoLgpd,
  corpoManifestacao,
  rotuloEstado,
  type Resultado,
} from "@/lib/formularios-cidadao";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import { avisoNoRecibo } from "@/lib/faixa-acesso-restrito";
import { useEnvioCidadao } from "@/lib/use-envio-cidadao";
import type { EstadoSessaoCidada } from "@/lib/use-sessao-cidada";
import { useSessaoCidada } from "@/lib/use-sessao-cidada";
import "./participacao.css";
import "./participar.css";

type Sessao = { estado: EstadoSessaoCidada; token: string | null };
// `acessoRestritoDesde` (ADR-0018): a Casa está com o sistema restrito — o pedido foi recebido e o prazo corre.
type Recibo = { protocolo: string; reciboEm: string; acessoRestritoDesde?: string | null };

const IconeEscudo = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
    <path d="M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z" />
  </svg>
);

export function Hero({ rotulo, titulo, texto, lei }: { rotulo: string; titulo: string; texto: string; lei: ReactNode }) {
  return (
    <div className="pf-hero">
      <span className="eyebrow">{rotulo}</span>
      <h1>{titulo}</h1>
      <p>{texto}</p>
      <span className="pf-lei">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
          <path d="M12 3v18M5 7h14M7 7l-3 7a3 3 0 0 0 6 0zM17 7l-3 7a3 3 0 0 0 6 0z" />
        </svg>
        <span>{lei}</span>
      </span>
    </div>
  );
}

// O que ocupa o lugar do formulário quando a sessão não serve para escrever aqui.
function Portao({ ente, sessao, voltarPara, porque }: { ente: string; sessao: Sessao; voltarPara: string; porque: string }) {
  if (sessao.estado === "carregando") return <div className="pf-card" aria-busy="true" />;
  if (sessao.estado === "outra-casa") {
    return (
      <div className="pf-card">
        <h2>Você entrou por outra Câmara</h2>
        <p className="pf-ajuda">
          Para enviar aqui, <a href={`/portal/casa/${ente}/participar`}>entre por esta Câmara</a>.
        </p>
      </div>
    );
  }
  return (
    <div className="pf-card">
      <h2>Identifique-se para enviar</h2>
      <p className="pf-ajuda">{porque}</p>
      {sessao.estado === "erro" && (
        <p className="form-erro" role="alert">
          Não conseguimos confirmar sua entrada agora. Tente de novo em instantes.
        </p>
      )}
      <a className="pt-govbr" href={hrefEntrarComGovbr(ente, voltarPara)}>
        Entrar com{" "}
        <span className="pt-wm">
          gov<b>.br</b>
        </span>
      </a>
      <p className="pf-protege">
        <IconeEscudo />
        A Câmara recebe do gov.br apenas seu nome e CPF verificado.
      </p>
    </div>
  );
}

function ReciboProtocolo({
  titulo,
  recibo,
  ente,
  anonima = false,
}: {
  titulo: string;
  recibo: Recibo;
  ente: string;
  anonima?: boolean;
}) {
  return (
    <div className="pf-card pf-recibo" role="status">
      <span className="eyebrow">Recibo</span>
      <h2>{titulo}</h2>
      <p className="pf-protocolo">{recibo.protocolo}</p>
      <p className="pf-ajuda">
        Recebido em {formatarData(recibo.reciboEm)} às {formatarHora(recibo.reciboEm)}. Este recibo é a prova do seu
        pedido e o <b>marco em que o prazo começa a contar</b>.
      </p>
      {recibo.acessoRestritoDesde && <p className="pf-ajuda pf-restrito">{avisoNoRecibo(recibo.acessoRestritoDesde)}</p>}
      {anonima ? (
        <p className="pf-ajuda">
          <b>Guarde este número:</b> sem identificação, é só por ele que você acompanha a resposta.
        </p>
      ) : (
        <p className="pf-acoes">
          <a className="btn btn-primaria" href="/meus-protocolos">
            Acompanhar em Meus protocolos
          </a>
          <a className="btn btn-contorno" href={`/portal/casa/${ente}`}>
            Voltar ao portal
          </a>
        </p>
      )}
    </div>
  );
}

function Campo({
  id,
  rotulo,
  obrigatorio,
  dica,
  children,
}: {
  id: string;
  rotulo: string;
  obrigatorio?: boolean;
  dica?: string;
  children: ReactNode;
}) {
  return (
    <div className="pf-campo">
      <label htmlFor={id}>
        {rotulo}
        {obrigatorio && (
          <span className="pf-req" aria-hidden="true">
            {" "}*
          </span>
        )}
      </label>
      {children}
      {dica && <span className="pf-dica">{dica}</span>}
    </div>
  );
}

// Validação local + envio + recibo — o mesmo ciclo nos três formulários.
function useFormulario(token: string | null) {
  const { enviar, estado, erro } = useEnvioCidadao(token);
  const [falta, setFalta] = useState<string | null>(null);
  const [recibo, setRecibo] = useState<Recibo | null>(null);
  async function submeter<T>(montado: Resultado<T>, caminho: string) {
    if (!montado.ok) {
      setFalta(MENSAGEM_CAMPO[montado.campo] ?? "Confira os campos.");
      return;
    }
    setFalta(null);
    try {
      setRecibo(await enviar<Recibo>(caminho, montado.corpo));
    } catch {
      // `erro` já traz a mensagem
    }
  }
  return { submeter, recibo, enviando: estado === "enviando", mensagem: falta ?? erro };
}

function Alerta({ mensagem }: { mensagem: string | null }) {
  return mensagem ? (
    <p className="form-erro" role="alert">
      {mensagem}
    </p>
  ) : null;
}

function Enviar({ rotulo, enviando }: { rotulo: string; enviando: boolean }) {
  return (
    <div className="pf-enviar">
      <button className="btn btn-primaria" type="submit" disabled={enviando}>
        {enviando ? "Enviando…" : rotulo}
      </button>
      <span className="pf-protege">
        <IconeEscudo />
        Tratado conforme a LGPD.
      </span>
    </div>
  );
}

function Rail({ children }: { children: ReactNode }) {
  return (
    <aside className="pf-rail" aria-label="Prazos e acompanhamento">
      {children}
    </aside>
  );
}

function PrazoLista({ itens }: { itens: ReactNode[] }) {
  return (
    <div className="pf-rcard">
      <h3>O que acontece depois</h3>
      <ol className="pf-prazo-lista">
        {itens.map((t, i) => (
          <li key={i}>{t}</li>
        ))}
      </ol>
    </div>
  );
}

// ---------------------------------------------------------------- e-SIC

export function FormEsic({ ente, sessao }: { ente: string; sessao: Sessao }) {
  const f = useFormulario(sessao.token);
  const [assunto, setAssunto] = useState("");
  const [descricao, setDescricao] = useState("");

  let card: ReactNode;
  if (sessao.estado !== "cidada") {
    card = (
      <Portao
        ente={ente}
        sessao={sessao}
        voltarPara={`/portal/casa/${ente}/esic/novo`}
        porque="O pedido de acesso à informação é registrado em seu nome, para você receber a resposta e poder recorrer."
      />
    );
  } else if (f.recibo) {
    card = <ReciboProtocolo titulo="Pedido registrado" recibo={f.recibo} ente={ente} />;
  } else {
    card = (
      <form
        className="pf-card"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void f.submeter(corpoEsic({ assunto, descricao }), "/portal/esic/pedidos");
        }}
      >
        <h2>Novo pedido de informação</h2>
        <p className="pf-ajuda">Você não precisa explicar por que quer saber. Seja específico sobre o que quer receber.</p>
        <Campo id="esic-assunto" rotulo="Assunto" obrigatorio>
          <input id="esic-assunto" type="text" value={assunto} maxLength={LIMITES.assunto}
            placeholder="Resuma em uma frase" onChange={(e) => setAssunto(e.target.value)} />
        </Campo>
        <Campo id="esic-descricao" rotulo="O que você quer saber" obrigatorio
          dica="Não inclua senhas ou dados de terceiros sem necessidade.">
          <textarea id="esic-descricao" value={descricao} maxLength={LIMITES.descricao}
            placeholder="Ex.: a lista dos contratos de limpeza assinados em 2025, com valores e empresas."
            onChange={(e) => setDescricao(e.target.value)} />
        </Campo>
        <Alerta mensagem={f.mensagem} />
        <Enviar rotulo="Enviar pedido" enviando={f.enviando} />
      </form>
    );
  }

  return (
    <div className="pf-grade">
      {card}
      <Rail>
        <PrazoLista
          itens={[
            <>Você recebe um <b>número de protocolo</b> na hora.</>,
            <>A Câmara responde em até <b>20 dias</b>, prorrogáveis por mais 10 com justificativa.</>,
            <>Não concordou com a resposta? Você pode <b>recorrer</b> em Meus protocolos.</>,
          ]}
        />
      </Rail>
    </div>
  );
}

// ---------------------------------------------------------------- LGPD

export function FormLgpd({ ente, sessao, tipoInicial }: { ente: string; sessao: Sessao; tipoInicial: string }) {
  const f = useFormulario(sessao.token);
  const [tipo, setTipo] = useState(tipoInicial);
  const [detalhe, setDetalhe] = useState("");
  const voltar = `/portal/casa/${ente}/lgpd/novo${tipoInicial ? `?tipo=${tipoInicial}` : ""}`;

  let card: ReactNode;
  if (sessao.estado !== "cidada") {
    card = (
      <Portao ente={ente} sessao={sessao} voltarPara={voltar}
        porque="Os pedidos sobre os seus dados exigem identificação formal: a Câmara só responde ao próprio titular." />
    );
  } else if (f.recibo) {
    card = <ReciboProtocolo titulo="Pedido registrado" recibo={f.recibo} ente={ente} />;
  } else {
    card = (
      <form
        className="pf-card"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void f.submeter(corpoLgpd({ tipo, detalhe }), "/portal/lgpd/solicitacoes");
        }}
      >
        <h2>O que você quer pedir</h2>
        <p className="pf-ajuda">Escolha um direito por pedido. A resposta vem para você, titular dos dados.</p>
        <fieldset className="pf-fieldset">
          <legend>
            Direito <span className="pf-req" aria-hidden="true">*</span>
          </legend>
          <div className="pf-tipos">
            {DIREITOS_LGPD.map((d) => (
              <div className="pf-tipo" key={d.valor}>
                <input type="radio" name="direito" id={`dir-${d.valor}`} value={d.valor}
                  checked={tipo === d.valor} onChange={() => setTipo(d.valor)} />
                <label htmlFor={`dir-${d.valor}`}>
                  <b>{d.rotulo}</b>
                  <span>{d.descricao}</span>
                </label>
              </div>
            ))}
          </div>
        </fieldset>
        <Campo id="lgpd-detalhe" rotulo="Detalhe (opcional)" dica="Ex.: qual dado corrigir e o valor certo.">
          <textarea id="lgpd-detalhe" value={detalhe} maxLength={LIMITES.detalhe} onChange={(e) => setDetalhe(e.target.value)} />
        </Campo>
        <Alerta mensagem={f.mensagem} />
        <Enviar rotulo="Enviar pedido" enviando={f.enviando} />
      </form>
    );
  }

  return (
    <div className="pf-grade">
      {card}
      <Rail>
        <PrazoLista
          itens={[
            <>Você recebe um <b>número de protocolo</b> na hora.</>,
            <>O Encarregado de Dados da Câmara responde <b>dentro do prazo legal</b>.</>,
            <>Acompanhe o andamento em <b>Meus protocolos</b>.</>,
          ]}
        />
      </Rail>
    </div>
  );
}

// ---------------------------------------------------------------- Ouvidoria

function AcompanharManifestacao({ ente }: { ente: string }) {
  const [protocolo, setProtocolo] = useState("");
  const [resultado, setResultado] = useState<
    { estado: "buscando" } | { estado: "achou"; status: string; dias: number | null } | { estado: "nao" } | null
  >(null);
  return (
    <div className="pf-rcard">
      <h3>Acompanhar manifestação</h3>
      <p>Já tem um protocolo? Veja o andamento.</p>
      <form
        className="pf-acompanhar"
        onSubmit={async (e) => {
          e.preventDefault();
          const p = protocolo.trim();
          if (!p || resultado?.estado === "buscando") return;
          setResultado({ estado: "buscando" });
          const r = await buscarPublico<{ estado: string; diasRestantes: number | null }>(
            ente, "ouvidoria", "acompanhar", p,
          );
          setResultado(r ? { estado: "achou", status: r.estado, dias: r.diasRestantes } : { estado: "nao" });
        }}
      >
        <input type="text" value={protocolo} placeholder="OUV-2026-000000" aria-label="Número do protocolo"
          onChange={(e) => setProtocolo(e.target.value)} />
        <button className="btn btn-contorno btn-mini" type="submit">
          Ver
        </button>
      </form>
      <div role="status" aria-live="polite">
        {resultado?.estado === "achou" && (
          <p>
            <b>{rotuloEstado(resultado.status)}</b>
            {resultado.dias !== null && resultado.status !== "respondida" && resultado.status !== "arquivada"
              ? ` · ${resultado.dias} dia(s) para a resposta`
              : ""}
          </p>
        )}
        {resultado?.estado === "nao" && <p>Não encontramos esse protocolo. Confira o número e tente de novo.</p>}
      </div>
    </div>
  );
}

export function FormOuvidoria({ ente, sessao }: { ente: string; sessao: Sessao }) {
  const f = useFormulario(sessao.token);
  const [tipo, setTipo] = useState("reclamacao");
  const [assunto, setAssunto] = useState("");
  const [descricao, setDescricao] = useState("");
  const [anonima, setAnonima] = useState(false);
  const [enviadaAnonima, setEnviadaAnonima] = useState(false);

  let card: ReactNode;
  if (sessao.estado !== "cidada") {
    card = (
      <Portao ente={ente} sessao={sessao} voltarPara={`/portal/casa/${ente}/ouvidoria`}
        porque="Mesmo para manifestar sem se identificar, entre com o gov.br: se escolher o anonimato, seu nome não fica registrado na manifestação." />
    );
  } else if (f.recibo) {
    card = <ReciboProtocolo titulo="Manifestação registrada" recibo={f.recibo} ente={ente} anonima={enviadaAnonima} />;
  } else {
    card = (
      <form
        className="pf-card"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          setEnviadaAnonima(anonima);
          void f.submeter(corpoManifestacao({ tipo, assunto, descricao, anonima }), "/portal/ouvidoria/manifestacoes");
        }}
      >
        <h2>Nova manifestação</h2>
        <p className="pf-ajuda">Quanto mais específica, mais rápido conseguimos encaminhar e responder.</p>
        <fieldset className="pf-fieldset">
          <legend>
            Tipo de manifestação <span className="pf-req" aria-hidden="true">*</span>
          </legend>
          <div className="pf-tipos">
            {TIPOS_MANIFESTACAO.map((t) => (
              <div className="pf-tipo" key={t.valor}>
                <input type="radio" name="tipo" id={`t-${t.valor}`} value={t.valor}
                  checked={tipo === t.valor} onChange={() => setTipo(t.valor)} />
                <label htmlFor={`t-${t.valor}`}>
                  <b>{t.rotulo}</b>
                  <span>{t.descricao}</span>
                </label>
              </div>
            ))}
          </div>
        </fieldset>
        <Campo id="ouv-assunto" rotulo="Assunto" obrigatorio>
          <input id="ouv-assunto" type="text" value={assunto} maxLength={LIMITES.assunto}
            placeholder="Resuma em uma frase" onChange={(e) => setAssunto(e.target.value)} />
        </Campo>
        <Campo id="ouv-descricao" rotulo="Descrição" obrigatorio
          dica="Não inclua senhas ou dados de terceiros sem necessidade.">
          <textarea id="ouv-descricao" value={descricao} maxLength={LIMITES.descricao}
            placeholder="Conte o que aconteceu: o quê, quando e onde. Inclua o que ajudar a entender."
            onChange={(e) => setDescricao(e.target.value)} />
        </Campo>
        <fieldset className="pf-ident">
          <legend className="sr-only">Como deseja se identificar</legend>
          <label className="pf-op">
            <input type="radio" name="ident" checked={!anonima} onChange={() => setAnonima(false)} />
            <span className="t">
              <b>Identificar-me</b>
              <span>Você acompanha a resposta e pode receber retorno individual. Seus dados ficam protegidos pela LGPD.</span>
            </span>
          </label>
          <label className="pf-op">
            <input type="radio" name="ident" checked={anonima} onChange={() => setAnonima(true)} />
            <span className="t">
              <b>Manifestar sem me identificar</b>
              <span>
                Permitido por lei. Sem identificação, não há retorno individual — você acompanha apenas pelo número de
                protocolo.
              </span>
            </span>
          </label>
        </fieldset>
        <Alerta mensagem={f.mensagem} />
        <Enviar rotulo="Enviar manifestação" enviando={f.enviando} />
      </form>
    );
  }

  return (
    <div className="pf-grade">
      {card}
      <Rail>
        <PrazoLista
          itens={[
            <>Você recebe um <b>número de protocolo</b> na hora.</>,
            <>A Ouvidoria responde em até <b>30 dias</b>.</>,
            <>O prazo pode ser prorrogado <b>uma vez</b>, com justificativa (Lei 13.460, art. 16/17).</>,
          ]}
        />
        <AcompanharManifestacao ente={ente} />
      </Rail>
    </div>
  );
}

// A página (Server Component) não resolve sessão; este invólucro resolve e entrega ao formulário.
export function FormularioComSessao({
  ente,
  qual,
  tipoInicial = "",
}: {
  ente: string;
  qual: "esic" | "lgpd" | "ouvidoria";
  tipoInicial?: string;
}) {
  const sessao = useSessaoCidada(ente);
  if (qual === "esic") return <FormEsic ente={ente} sessao={sessao} />;
  if (qual === "lgpd") return <FormLgpd ente={ente} sessao={sessao} tipoInicial={tipoInicial} />;
  return <FormOuvidoria ente={ente} sessao={sessao} />;
}
