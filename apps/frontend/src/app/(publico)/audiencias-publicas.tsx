"use client";

// AUDIÊNCIAS PÚBLICAS no portal do cidadão (ADR-0021 A2/A5). Porte de audiencia-publica.html: a lista (próximas e
// realizadas) e a página de uma audiência — "Sobre a audiência", a matéria relacionada, o "Quero falar" e, no trilho,
// as próximas. O nome de quem se inscreve NÃO é digitado: vem da identidade do gov.br (A2) — por isso, sem sessão, o
// cartão vira o convite do gov.br (`Portao`, o mesmo dos outros formulários), que volta a ESTA audiência. Antes de
// enviar, a pessoa confirma que a fala é pública e entra na ata e na transmissão (caixa obrigatória). Depois de
// realizada: quem falou (só quem falou — desistência e ausência não aparecem em lugar público) e o link para a ata no
// livro de atas, se já publicada.
//
// Fora da V1 (ADR A5): fala remota do cidadão e consulta pública estruturada.

import { useEffect, useState, type ReactNode } from "react";
import { buscarPublico } from "@/lib/portal-api";
import {
  FALA_COMO,
  ROTAS_AUDIENCIA,
  doFio,
  formaValida,
  hrefAtaNoPortal,
  rotuloEstadoAudiencia,
  rotuloFalaComo,
  rotuloFinalidade,
  rotuloReferencia,
  type AudienciaPublicaOut,
  type AudienciasPublicasOut,
  type ReciboInscricaoOut,
  type ResumoAudiencia,
} from "@/lib/contrato-audiencia";
import {
  LIMITE_ENTIDADE,
  LIMITE_TEMA,
  diaEMes,
  horaCurta,
  quandoPorExtenso,
  rotuloTempoFala,
  situacaoDaInscricao,
  validarInscricaoCidada,
} from "@/lib/audiencia-vista";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import { useEnvioCidadao } from "@/lib/use-envio-cidadao";
import { useSessaoCidada, type EstadoSessaoCidada } from "@/lib/use-sessao-cidada";
import { Alerta, Campo, IconeEscudo, Portao } from "./formularios-cidadao";
import "./participacao.css";
import "./participar.css";
import "./audiencias-publicas.css";

type Sessao = { estado: EstadoSessaoCidada; token: string | null };
type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro" };

const enc = encodeURIComponent;
const hrefAudiencia = (ente: string, sessaoId: string) => `/portal/casa/${enc(ente)}/audiencias/${enc(sessaoId)}`;

/** Leitura pública (sem auth): `buscarPublico` → acerto do fio → forma. Falha ou forma estranha = erro na tela. */
function useLeituraPublica<T>(segmentos: string[], adaptar: (d: unknown) => unknown, valido: (d: unknown) => boolean): Carga<T> {
  const chave = segmentos.join("/");
  const [r, setR] = useState<{ de: string | null; carga: Carga<T> }>({ de: null, carga: { fase: "carregando" } });
  useEffect(() => {
    let vivo = true;
    (async () => {
      const cru = await buscarPublico<unknown>(...chave.split("/"));
      const d = cru === null ? null : adaptar(cru);
      if (vivo) setR({ de: chave, carga: d !== null && valido(d) ? { fase: "pronto", dado: d as T } : { fase: "erro" } });
    })();
    return () => {
      vivo = false;
    };
    // `adaptar` e `valido` são constantes do contrato
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chave]);
  return r.de === chave ? r.carga : { fase: "carregando" };
}

const IconeCalendario = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <path d="M16 2v4M8 2v4M3 10h18" />
  </svg>
);

/** O quadradinho de data da lista (dia grande, mês em mono), ou "a definir". */
function DataCurta({ iso }: { iso: string | null }) {
  const d = diaEMes(iso);
  return (
    <span className="ap-data" aria-hidden="true">
      {d ? (
        <>
          <b>{d.dia}</b>
          <span>{d.mes}</span>
        </>
      ) : (
        <span>a definir</span>
      )}
    </span>
  );
}

function ItemDaLista({ ente, a }: { ente: string; a: ResumoAudiencia }) {
  const quando = quandoPorExtenso(a.agendadaPara);
  return (
    <li className="ap-item">
      <DataCurta iso={a.agendadaPara} />
      <div className="ap-info">
        <a href={hrefAudiencia(ente, a.sessaoId)}>
          <b>{a.tema}</b>
        </a>
        <span>
          {a.comissaoNome}
          {quando ? ` · ${quando}` : " · data a definir"}
          {a.local ? ` · ${a.local}` : ""}
        </span>
        <span className="ap-selos">
          <span className="ap-selo">{rotuloFinalidade(a.finalidade)}</span>
          <span className={`ap-selo ap-estado-${a.estado}`}>{rotuloEstadoAudiencia(a.estado)}</span>
        </span>
      </div>
    </li>
  );
}

// ---------------------------------------------------------------- a lista

export function ListaAudiencias({ ente }: { ente: string }) {
  const carga = useLeituraPublica<AudienciasPublicasOut>(ROTAS_AUDIENCIA.publicas(ente), doFio.publicas, formaValida.publicas);
  return (
    <div className="audiencias-publicas">
      <div className="pf-hero">
        <span className="eyebrow">Participação</span>
        <h1>Audiências públicas</h1>
        <p>
          As audiências são abertas: qualquer pessoa pode assistir, e quem quiser pode se inscrever para falar. Depois de
          realizada, a ata fica no livro de atas.
        </p>
      </div>
      {carga.fase === "carregando" && <p className="estado">Carregando…</p>}
      {carga.fase === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar as audiências agora. Tente novamente em instantes.
        </p>
      )}
      {carga.fase === "pronto" && (
        <>
          <section className="ap-secao" aria-labelledby="ap-proximas">
            <h2 id="ap-proximas">Próximas</h2>
            {carga.dado.proximas.length === 0 ? (
              <p className="estado">Nenhuma audiência pública marcada por enquanto.</p>
            ) : (
              <ul className="ap-lista">
                {carga.dado.proximas.map((a) => (
                  <ItemDaLista key={a.sessaoId} ente={ente} a={a} />
                ))}
              </ul>
            )}
          </section>
          <section className="ap-secao" aria-labelledby="ap-realizadas">
            <h2 id="ap-realizadas">Realizadas</h2>
            {carga.dado.realizadas.length === 0 ? (
              <p className="estado">Nenhuma audiência realizada ainda.</p>
            ) : (
              <ul className="ap-lista">
                {carga.dado.realizadas.map((a) => (
                  <ItemDaLista key={a.sessaoId} ente={ente} a={a} />
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- uma audiência

export function PaginaAudiencia({ ente, sessaoId }: { ente: string; sessaoId: string }) {
  const sessao = useSessaoCidada(ente);
  return <Audiencia ente={ente} sessaoId={sessaoId} sessao={sessao} />;
}

/** A página com a sessão já resolvida (testável sem /api/eu). */
export function Audiencia({ ente, sessaoId, sessao }: { ente: string; sessaoId: string; sessao: Sessao }) {
  const carga = useLeituraPublica<AudienciaPublicaOut>(ROTAS_AUDIENCIA.publica(ente, sessaoId), doFio.publica, formaValida.publica);
  if (carga.fase === "carregando") return <p className="estado">Carregando a audiência…</p>;
  if (carga.fase === "erro") {
    return (
      <div className="audiencias-publicas">
        <p className="estado" role="alert">
          Audiência não encontrada. Ela pode não ser pública, ou o endereço está errado.{" "}
          <a href={`/portal/casa/${enc(ente)}/audiencias`}>Ver todas as audiências</a>.
        </p>
      </div>
    );
  }
  const a = carga.dado;
  const quando = quandoPorExtenso(a.agendadaPara);
  const referencia = rotuloReferencia(a.referencia);
  const situacao = situacaoDaInscricao(a);

  let cartao: ReactNode;
  if (situacao === "realizada") cartao = <QuemFalou ente={ente} a={a} />;
  else if (situacao === "nao_realizada") {
    cartao = (
      <div className="pf-card">
        <h2>Esta audiência não foi realizada</h2>
        <p className="pf-ajuda">Quando a Câmara marcar uma nova data, ela aparece na lista de audiências.</p>
      </div>
    );
  } else if (situacao === "fechada") {
    cartao = (
      <div className="pf-card">
        <h2>Inscrições para falar encerradas</h2>
        <p className="pf-ajuda">
          A Mesa fechou as inscrições desta audiência. Você ainda pode assistir — a audiência é aberta e não exige
          inscrição.
        </p>
      </div>
    );
  } else if (sessao.estado !== "cidada") {
    cartao = (
      <Portao
        ente={ente}
        sessao={sessao}
        voltarPara={hrefAudiencia(ente, sessaoId)}
        titulo="Quero falar na audiência"
        porque="Entre com o gov.br para se inscrever. A inscrição para falar é feita em seu nome, como o gov.br o confirma — é ele que a Mesa chama na hora da fala."
      />
    );
  } else {
    cartao = <QueroFalar ente={ente} sessaoId={sessaoId} tempoFalaSegundos={a.tempoFalaSegundos} token={sessao.token} />;
  }

  return (
    <div className="audiencias-publicas">
      <div className="ap-hero">
        <span className="ap-quando">
          <IconeCalendario />
          {quando ?? "Data a definir"}
        </span>
        <h1>Audiência pública: {a.tema}</h1>
        <p>
          Promovida pela {a.comissaoNome}.{" "}
          {situacao === "realizada"
            ? "A audiência já aconteceu: veja quem falou e a ata."
            : "A audiência é aberta: você pode assistir e se inscrever para falar."}
        </p>
      </div>

      <div className="pf-grade">
        <div className="ap-coluna">
          <div className="pf-card">
            <h2>Sobre a audiência</h2>
            <dl className="ap-dl">
              <dt>Tema</dt>
              <dd>{a.tema}</dd>
              <dt>Promovida por</dt>
              <dd>{a.comissaoNome}</dd>
              <dt>Finalidade</dt>
              <dd>
                {rotuloFinalidade(a.finalidade)}
                {referencia ? ` · ${referencia}` : ""}
              </dd>
              <dt>Data e hora</dt>
              <dd className="mono">{quando ?? "a definir"}</dd>
              <dt>Local</dt>
              <dd>{a.local ?? "a informar"}</dd>
              {a.proposicao && (
                <>
                  <dt>Matéria relacionada</dt>
                  <dd>
                    <a href={`/portal/casa/${enc(ente)}/materias/${enc(a.proposicao.id)}`}>
                      {a.proposicao.rotulo} — {a.proposicao.ementa}
                    </a>
                  </dd>
                </>
              )}
              <dt>Situação</dt>
              <dd>{rotuloEstadoAudiencia(a.estado)}</dd>
              {situacao !== "realizada" && situacao !== "nao_realizada" && (
                <>
                  <dt>Tempo de fala</dt>
                  <dd>{rotuloTempoFala(a.tempoFalaSegundos)} por pessoa</dd>
                  <dt>Inscritos para falar</dt>
                  <dd>{a.inscritos === 1 ? "1 pessoa" : `${a.inscritos} pessoas`}</dd>
                </>
              )}
            </dl>
          </div>
          {cartao}
        </div>
        <aside className="pf-rail" aria-label="Outras audiências">
          <Proximas ente={ente} atual={sessaoId} />
        </aside>
      </div>
    </div>
  );
}

function QuemFalou({ ente, a }: { ente: string; a: AudienciaPublicaOut }) {
  return (
    <div className="pf-card">
      <h2>Quem falou</h2>
      {a.falaram.length === 0 ? (
        <p className="pf-ajuda">Nenhum cidadão usou a palavra nesta audiência.</p>
      ) : (
        <ol className="ap-falaram">
          {a.falaram.map((f, i) => (
            <li key={`${i}-${f.nome}`}>
              <b>{f.nome}</b>
              <span>
                {rotuloFalaComo(f.falaComo)}
                {f.falaComo !== "individual" && f.entidade ? ` · ${f.entidade}` : ""}
              </span>
            </li>
          ))}
        </ol>
      )}
      {a.ataPublicada ? (
        <p className="pf-acoes">
          <a className="btn btn-primaria" href={hrefAtaNoPortal(ente, a.sessaoId)}>
            Ler a ata no livro de atas
          </a>
        </p>
      ) : (
        <p className="pf-ajuda">A ata desta audiência ainda não foi publicada. Quando for, ela aparece no livro de atas.</p>
      )}
    </div>
  );
}

function Proximas({ ente, atual }: { ente: string; atual: string }) {
  const carga = useLeituraPublica<AudienciasPublicasOut>(ROTAS_AUDIENCIA.publicas(ente), doFio.publicas, formaValida.publicas);
  // o trilho degrada sozinho: falhou, some (a página da audiência segue de pé)
  if (carga.fase !== "pronto") return null;
  const outras = carga.dado.proximas.filter((p) => p.sessaoId !== atual).slice(0, 4);
  return (
    <div className="pf-rcard">
      <h3>Próximas audiências</h3>
      {outras.length === 0 ? (
        <p>Nenhuma outra audiência marcada por enquanto.</p>
      ) : (
        <ul className="ap-prox">
          {outras.map((p) => (
            <li key={p.sessaoId}>
              <DataCurta iso={p.agendadaPara} />
              <span className="ap-info">
                <a href={hrefAudiencia(ente, p.sessaoId)}>
                  <b>{p.tema}</b>
                </a>
                <span>
                  {p.comissaoNome}
                  {horaCurta(p.agendadaPara) ? ` · ${horaCurta(p.agendadaPara)}` : ""}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
      <p>
        <a href={`/portal/casa/${enc(ente)}/audiencias`}>Todas as audiências</a>
      </p>
    </div>
  );
}

// ---------------------------------------------------------------- "Quero falar"

const MENSAGENS_DO_SERVIDOR: Partial<Record<number, string>> = {
  409: "Não foi possível inscrever: você já tem inscrição nesta audiência, ou as inscrições acabaram de fechar.",
  423: "A Câmara está com acesso restrito: as inscrições para falar estão suspensas por enquanto.",
  404: "Esta audiência não recebe mais inscrições.",
};

export function QueroFalar({
  ente,
  sessaoId,
  tempoFalaSegundos,
  token,
}: {
  ente: string;
  sessaoId: string;
  tempoFalaSegundos: number;
  token: string | null;
}) {
  const { enviar, estado, erro } = useEnvioCidadao(token);
  const [falaComo, setFalaComo] = useState("individual");
  const [entidade, setEntidade] = useState("");
  const [tema, setTema] = useState("");
  const [ciente, setCiente] = useState(false);
  const [falta, setFalta] = useState<string | null>(null);
  const [recibo, setRecibo] = useState<ReciboInscricaoOut | null>(null);

  if (recibo) {
    return (
      <div className="pf-card pf-recibo" role="status">
        <span className="eyebrow">Recibo</span>
        <h2>Inscrição feita</h2>
        <p className="pf-protocolo">{recibo.protocolo}</p>
        <p className="pf-ajuda">
          Você é a <b>{recibo.ordem}ª pessoa</b> da fila para falar
          {recibo.reciboEm ? ` (inscrição recebida em ${formatarData(recibo.reciboEm)} às ${formatarHora(recibo.reciboEm)})` : ""}.
          Na audiência, a Mesa chama pela ordem de inscrição; cada pessoa tem {rotuloTempoFala(tempoFalaSegundos)}.
        </p>
        <p className="pf-acoes">
          <a className="btn btn-primaria" href="/meus-protocolos">
            Acompanhar em Meus protocolos
          </a>
          <a className="btn btn-contorno" href={`/portal/casa/${enc(ente)}/audiencias`}>
            Ver outras audiências
          </a>
        </p>
      </div>
    );
  }

  async function submeter(e: React.FormEvent) {
    e.preventDefault();
    const v = validarInscricaoCidada({ falaComo, entidade, tema, ciente });
    if (!v.ok) {
      setFalta(v.mensagem);
      return;
    }
    setFalta(null);
    try {
      const d = await enviar<unknown>(ROTAS_AUDIENCIA.inscreverCidada(sessaoId), v.corpo, "POST", MENSAGENS_DO_SERVIDOR);
      if (formaValida.recibo(d)) setRecibo(d as ReciboInscricaoOut);
      else setFalta("A inscrição foi enviada, mas a resposta veio incompleta. Confira em Meus protocolos.");
    } catch {
      // `erro` já traz a mensagem
    }
  }

  return (
    <form className="pf-card" noValidate onSubmit={submeter}>
      <h2>Quero falar na audiência</h2>
      <p className="pf-ajuda">
        Inscreva-se para usar a palavra. As falas seguem a ordem de inscrição, com {rotuloTempoFala(tempoFalaSegundos)} para
        cada pessoa. <b>Seu nome vem do gov.br</b> — não é preciso digitá-lo.
      </p>
      <fieldset className="pf-fieldset">
        <legend>
          Você fala como <span className="pf-req" aria-hidden="true">*</span>
        </legend>
        <div className="pf-tipos">
          {FALA_COMO.map((f) => (
            <div className="pf-tipo" key={f.valor}>
              <input
                type="radio"
                name="fala-como"
                id={`fc-${f.valor}`}
                value={f.valor}
                checked={falaComo === f.valor}
                onChange={() => setFalaComo(f.valor)}
              />
              <label htmlFor={`fc-${f.valor}`}>
                <b>{f.rotulo}</b>
                <span>{f.descricao}</span>
              </label>
            </div>
          ))}
        </div>
      </fieldset>
      {falaComo !== "individual" && (
        <Campo id="ap-entidade" rotulo="Qual entidade, conselho ou movimento" obrigatorio>
          <input id="ap-entidade" type="text" value={entidade} maxLength={LIMITE_ENTIDADE} onChange={(e) => setEntidade(e.target.value)} />
        </Campo>
      )}
      <Campo
        id="ap-tema"
        rotulo="Em uma frase, sobre o que pretende falar"
        obrigatorio
        dica={`Ajuda a Mesa a organizar a ordem dos temas. Até ${LIMITE_TEMA} caracteres.`}
      >
        <textarea id="ap-tema" value={tema} maxLength={LIMITE_TEMA} onChange={(e) => setTema(e.target.value)} />
      </Campo>
      <label className="ap-ciente">
        <input type="checkbox" checked={ciente} onChange={(e) => setCiente(e.target.checked)} />
        <span>
          <b>Estou ciente de que a fala é pública e entra na ata e na transmissão.</b>
          <span>Seu nome e, se for o caso, a entidade que você representa são lidos e registrados.</span>
        </span>
      </label>
      <Alerta mensagem={falta ?? erro} />
      <div className="pf-enviar">
        <button className="btn btn-primaria" type="submit" disabled={estado === "enviando"}>
          {estado === "enviando" ? "Enviando…" : "Inscrever-me para falar"}
        </button>
        <span className="pf-protege">
          <IconeEscudo />
          Seus dados são tratados conforme a LGPD. Assistir não exige inscrição.
        </span>
      </div>
    </form>
  );
}
