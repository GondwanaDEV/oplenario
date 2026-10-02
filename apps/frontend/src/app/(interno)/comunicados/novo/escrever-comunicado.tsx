"use client";

// ESCREVER um comunicado (ADR-0020) — arquétipo FORMULÁRIO com revisão antes do ato, porque o ato não volta: o
// comunicado recebe protocolo e é imutável (Eixo 5). Três fases na mesma tela:
//   1. editar — destinatários por tipo, assunto, texto, ciência (com prazo opcional), anexos e o link opcional;
//   2. revisar — "Vai para N pessoas" e o resumo do que vai sair; "Enviar comunicado" ou "Voltar e editar";
//   3. enviado — o número de protocolo, quantas pessoas de fato (o 201), quem ficou de fora por não ter acesso, e os
//      anexos subindo UM A UM depois do 201 (a rota de anexo pede o comunicado já existente), com "Tentar de novo".
//
// Destinatário em dois seletores (tipo → quem): uma lista por tipo cabe numa Câmara (dezenas de nomes) e é o controle
// mais acessível que há. Os tipos de GRUPO (setor, comissão, todos) só aparecem para quem pode enviar a grupos (Eixo 3,
// `pode-enviar-a-grupos`) — o backend recusa de qualquer forma (403).
//
// O alcance antes do envio é ESTIMATIVA (ver `fraseDoAlcance`): o número final é o do 201.

import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { enviarAnexo, enviarComunicado, useComunicado, useDestinos } from "@/lib/use-comunicados";
import { LIMITE_DE_ANEXOS, type ComunicadoOut, type DestinosOut, type EnvioOut, type TipoDestino, type TipoObjeto } from "@/lib/contrato-comunicacao";
import {
  NOME_DO_TIPO_DE_OBJETO,
  adicionarAnexos,
  chaveDoDestino,
  enriquecerDestinos,
  entradaDoForm,
  extrairIdDoObjeto,
  formDoSubstituto,
  fraseDoAlcance,
  fraseSemAcesso,
  opcoesDoTipo,
  hojeNaCasa,
  plural,
  prazoLegivel,
  prazoParaDia,
  rotuloDoDestino,
  rotuloDoEscolhido,
  tamanhoLegivel,
  tiposDisponiveis,
  validarComunicado,
  type CampoDoForm,
  type DestinoEscolhido,
  type ErrosDoForm,
  type FormDoComunicado,
} from "@/lib/comunicacao-vista";

const ROTULO_DO_ALVO: Record<TipoDestino, string> = {
  pessoa: "Pessoa",
  vereador: "Vereador",
  setor: "Setor",
  comissao: "Comissão",
  todos: "",
};

const ESCOLHA_DO_ALVO: Record<TipoDestino, string> = {
  pessoa: "Escolha a pessoa",
  vereador: "Escolha o vereador",
  setor: "Escolha o setor",
  comissao: "Escolha a comissão",
  todos: "",
};

const ORDEM_DOS_CAMPOS: CampoDoForm[] = ["destinos", "assunto", "corpo", "prazo", "objeto", "anexos"];
const ID_DO_CAMPO: Record<CampoDoForm, string> = {
  destinos: "com-tipo",
  assunto: "com-assunto",
  corpo: "com-corpo",
  prazo: "com-prazo",
  objeto: "com-objeto-ref",
  anexos: "com-anexos",
};

export function EscreverComunicado({ substituiId }: { substituiId: string | null }) {
  const { token } = useAuth();
  const destinos = useDestinos(token);
  const original = useComunicado(token, substituiId);
  const [rodada, setRodada] = useState(0);
  const router = useRouter();

  if (destinos.estado.fase === "carregando" || (substituiId && original.estado.fase === "carregando")) {
    return <p role="status">Carregando os destinatários…</p>;
  }
  if (destinos.estado.fase === "erro") {
    return (
      <div className="com-estado" role="alert">
        <p>{destinos.estado.mensagem}</p>
        <Link href={comToken("/caixa", token)} prefetch={false}>Voltar à caixa</Link>
      </div>
    );
  }
  if (substituiId && original.estado.fase === "erro") {
    return (
      <div className="com-estado" role="alert">
        <p>Não foi possível abrir o comunicado a substituir. {original.estado.mensagem}</p>
        <Link href={comToken("/comunicados/enviados", token)} prefetch={false}>Ver os enviados</Link>
      </div>
    );
  }
  const anterior = substituiId && original.estado.fase === "pronto" ? original.estado.dado : null;
  return (
    <Formulario
      key={`${anterior?.id ?? "novo"}:${rodada}`}
      opcoes={destinos.estado.dado}
      anterior={anterior}
      token={token}
      aoRecomecar={() => {
        setRodada((n) => n + 1);
        if (substituiId) router.replace(comToken("/comunicados/novo", token));
      }}
    />
  );
}

type EstadoDoAnexo = { arquivo: File; fase: "esperando" | "enviando" | "ok" | "erro"; mensagem?: string };

function formInicial(anterior: ComunicadoOut | null, opcoes: DestinosOut): FormDoComunicado {
  const base = anterior ? formDoSubstituto(anterior) : { assunto: "", corpo: "", destinos: [], exigeCiencia: false };
  return { ...base, destinos: enriquecerDestinos(base.destinos, opcoes), prazo: "", objetoTipo: "", objetoRef: "", anexos: [] };
}

function Formulario({
  opcoes,
  anterior,
  token,
  aoRecomecar,
}: {
  opcoes: DestinosOut;
  anterior: ComunicadoOut | null;
  token: string | null;
  aoRecomecar: () => void;
}) {
  const tipos = tiposDisponiveis(opcoes.podeEnviarAGrupos);
  const [form, setForm] = useState<FormDoComunicado>(() => formInicial(anterior, opcoes));
  const [tipo, setTipo] = useState<TipoDestino>(tipos[0]?.tipo ?? "pessoa");
  const [alvo, setAlvo] = useState("");
  const [erros, setErros] = useState<ErrosDoForm>({});
  const [recusados, setRecusados] = useState<string[]>([]);
  const [fase, setFase] = useState<"editar" | "revisar" | "enviado">("editar");
  const [enviando, setEnviando] = useState(false);
  const [erroEnvio, setErroEnvio] = useState<string | null>(null);
  const [envio, setEnvio] = useState<EnvioOut | null>(null);
  const [anexos, setAnexos] = useState<EstadoDoAnexo[]>([]);
  const [agora] = useState(() => Date.now());
  const tituloRef = useRef<HTMLHeadingElement>(null);

  // o foco acompanha a fase: quem usa teclado ou leitor de tela chega ao que mudou
  useEffect(() => {
    if (fase !== "editar") tituloRef.current?.focus();
  }, [fase]);

  function mudar<K extends keyof FormDoComunicado>(campo: K, valor: FormDoComunicado[K]) {
    setForm((f) => ({ ...f, [campo]: valor }));
  }

  function adicionarDestino() {
    if (tipo !== "todos" && !alvo) {
      setErros((e) => ({ ...e, destinos: `${ESCOLHA_DO_ALVO[tipo]} na lista antes de adicionar.` }));
      return;
    }
    const op = opcoesDoTipo(tipo, opcoes).find((o) => o.id === alvo);
    const novo: DestinoEscolhido =
      tipo === "todos" ? { tipo, alvoId: null, nome: null, membros: null } : { tipo, alvoId: alvo, nome: op?.nome ?? null, membros: op?.membros ?? null };
    if (form.destinos.some((d) => chaveDoDestino(d) === chaveDoDestino(novo))) {
      setErros((e) => ({ ...e, destinos: `${rotuloDoDestino({ tipo: novo.tipo, alvoNome: novo.nome })} já está na lista.` }));
      return;
    }
    mudar("destinos", [...form.destinos, novo]);
    setAlvo("");
    setErros((e) => ({ ...e, destinos: undefined }));
  }

  function escolherArquivos(ev: ChangeEvent<HTMLInputElement>) {
    const novos = Array.from(ev.target.files ?? []);
    const r = adicionarAnexos(form.anexos, novos);
    mudar("anexos", r.anexos);
    setRecusados(r.recusados);
    ev.target.value = "";
  }

  function revisar(ev: FormEvent) {
    ev.preventDefault();
    const e = validarComunicado(form, Date.now());
    setErros(e);
    const primeiro = ORDEM_DOS_CAMPOS.find((c) => e[c]);
    if (primeiro) {
      document.getElementById(ID_DO_CAMPO[primeiro])?.focus();
      return;
    }
    setErroEnvio(null);
    setFase("revisar");
  }

  async function enviarAnexos(lista: EstadoDoAnexo[], comunicadoId: string, indices: number[]) {
    for (const i of indices) {
      setAnexos((xs) => xs.map((x, j) => (j === i ? { ...x, fase: "enviando", mensagem: undefined } : x)));
      const r = await enviarAnexo(token, comunicadoId, lista[i].arquivo);
      setAnexos((xs) => xs.map((x, j) => (j === i ? (r.ok ? { ...x, fase: "ok" } : { ...x, fase: "erro", mensagem: r.mensagem }) : x)));
    }
  }

  async function enviar() {
    setEnviando(true);
    setErroEnvio(null);
    const r = await enviarComunicado(token, entradaDoForm(form, anterior?.id ?? null));
    setEnviando(false);
    if (!r.ok) {
      setErroEnvio(r.mensagem);
      return;
    }
    const lista: EstadoDoAnexo[] = form.anexos.map((arquivo) => ({ arquivo, fase: "esperando" }));
    setEnvio(r.dado);
    setAnexos(lista);
    setFase("enviado");
    if (lista.length) await enviarAnexos(lista, r.dado.comunicado.id, lista.map((_, i) => i));
  }

  const alcance = fraseDoAlcance(form.destinos);

  if (fase === "enviado" && envio) {
    const temComissao = form.destinos.some((d) => d.tipo === "comissao");
    const semAcesso = fraseSemAcesso(envio.semAcesso, temComissao);
    const c = envio.comunicado;
    return (
      <section className="com-enviado" aria-labelledby="com-enviado-t">
        <h2 id="com-enviado-t" ref={tituloRef} tabIndex={-1}>
          Comunicado {c.protocolo} enviado
        </h2>
        <p role="status">
          Foi para {plural(envio.destinatarios, "pessoa", "pessoas")}. Cada uma vê o comunicado na própria caixa
          {c.exigeCiencia ? " e confirma a ciência com um toque" : ""}.
        </p>
        {semAcesso && (
          <p className="com-nota com-nota-forte" role="note">
            {semAcesso}
          </p>
        )}
        {anexos.length > 0 && (
          <div className="com-anexos-envio">
            <h3>Anexos</h3>
            <ul aria-live="polite">
              {anexos.map((a, i) => (
                <li key={`${a.arquivo.name}:${i}`}>
                  <span className="com-anexo-nome">{a.arquivo.name}</span>
                  <span className={`com-anexo-fase com-anexo-${a.fase}`}>
                    {a.fase === "esperando" && "na fila"}
                    {a.fase === "enviando" && "enviando…"}
                    {a.fase === "ok" && "anexado"}
                    {a.fase === "erro" && `não foi anexado: ${a.mensagem}`}
                  </span>
                  {a.fase === "erro" && (
                    <button
                      type="button"
                      className="btn btn-contorno btn-mini"
                      aria-label={`Tentar de novo o anexo ${a.arquivo.name}`}
                      onClick={() => void enviarAnexos(anexos, c.id, [i])}
                    >
                      Tentar de novo
                    </button>
                  )}
                </li>
              ))}
            </ul>
            {anexos.some((a) => a.fase === "erro") && (
              <p className="com-dica">
                O comunicado já foi enviado. Os anexos que faltam podem ser enviados de novo por aqui nos primeiros 10
                minutos.
              </p>
            )}
          </div>
        )}
        <div className="com-acoes">
          <Link className="btn btn-primaria btn-mini" href={comToken(`/comunicados/${encodeURIComponent(c.id)}?de=enviados`, token)} prefetch={false}>
            Abrir o comunicado
          </Link>
          <Link className="btn btn-contorno btn-mini" href={comToken("/comunicados/enviados", token)} prefetch={false}>
            Ver os enviados
          </Link>
          <button type="button" className="btn btn-fantasma btn-mini" onClick={aoRecomecar}>
            Escrever outro
          </button>
        </div>
      </section>
    );
  }

  if (fase === "revisar") {
    const dia = form.exigeCiencia ? prazoParaDia(form.prazo) : null;
    const idObjeto = form.objetoTipo ? extrairIdDoObjeto(form.objetoRef) : null;
    return (
      <section className="com-revisao" aria-labelledby="com-revisao-t">
        <h2 id="com-revisao-t" ref={tituloRef} tabIndex={-1}>
          Confira antes de enviar
        </h2>
        <dl className="com-resumo">
          <dt>Para</dt>
          <dd>{form.destinos.map(rotuloDoEscolhido).join("; ")}</dd>
          <dt>Assunto</dt>
          <dd>{form.assunto.trim()}</dd>
          <dt>Ciência</dt>
          <dd>{form.exigeCiencia ? (dia ? `Pedida, até ${prazoLegivel(dia)}` : "Pedida, sem prazo") : "Não pedida"}</dd>
          {form.anexos.length > 0 && (
            <>
              <dt>Anexos</dt>
              <dd>{form.anexos.map((a) => a.name).join(", ")}</dd>
            </>
          )}
          {form.objetoTipo && idObjeto && (
            <>
              <dt>Ligado a</dt>
              <dd>
                {NOME_DO_TIPO_DE_OBJETO[form.objetoTipo]} <span className="com-objeto-id">{idObjeto}</span>
              </dd>
            </>
          )}
          {anterior && (
            <>
              <dt>Substitui</dt>
              <dd>{anterior.protocolo}</dd>
            </>
          )}
        </dl>
        {alcance && <p className="com-alcance-forte">{alcance}</p>}
        <p className="com-dica">
          O número final aparece depois do envio: quem está em mais de um destino recebe uma vez só, e você não entra na
          própria lista. Enviado, o comunicado ganha um número de protocolo e não muda mais — para corrigir, envia-se outro
          que o substitui.
        </p>
        <div className="com-acoes">
          <button type="button" className="btn btn-primaria" onClick={() => void enviar()} disabled={enviando}>
            {enviando ? "Enviando…" : "Enviar comunicado"}
          </button>
          <button type="button" className="btn btn-contorno" onClick={() => setFase("editar")} disabled={enviando}>
            Voltar e editar
          </button>
        </div>
        {erroEnvio && (
          <p className="com-erro" role="alert">
            {erroEnvio}
          </p>
        )}
      </section>
    );
  }

  const opcoesDoAlvo = opcoesDoTipo(tipo, opcoes);
  const descreve = (campo: CampoDoForm, extra?: string) =>
    [erros[campo] ? `com-erro-${campo}` : "", extra ?? ""].filter(Boolean).join(" ") || undefined;

  return (
    <form className="com-form" onSubmit={revisar} noValidate aria-label="Escrever comunicado">
      {anterior && (
        <p className="com-nota" role="note">
          Este comunicado vai <b>substituir o {anterior.protocolo}</b>. O texto e os destinatários vieram dele: corrija o
          que for preciso. O anterior passa a mostrar “substituído por”, e as marcas de leitura dos dois ficam guardadas.
        </p>
      )}

      <fieldset className="com-bloco">
        <legend>Para quem</legend>
        <div className="com-linha-destino">
          <div className="com-campo">
            <label htmlFor="com-tipo">Tipo de destinatário</label>
            <select
              id="com-tipo"
              value={tipo}
              aria-describedby={descreve("destinos")}
              onChange={(e) => {
                setTipo(e.target.value as TipoDestino);
                setAlvo("");
              }}
            >
              {tipos.map((t) => (
                <option key={t.tipo} value={t.tipo}>
                  {t.rotulo}
                </option>
              ))}
            </select>
          </div>
          {tipo !== "todos" && (
            <div className="com-campo">
              <label htmlFor="com-alvo">{ROTULO_DO_ALVO[tipo]}</label>
              <select id="com-alvo" value={alvo} onChange={(e) => setAlvo(e.target.value)}>
                <option value="">{opcoesDoAlvo.length ? "Escolha na lista…" : "Nenhuma opção cadastrada"}</option>
                {opcoesDoAlvo.map((o) => (
                  <option key={o.id} value={o.id}>
                    {tipo === "setor" || tipo === "comissao" ? `${o.nome} (${plural(o.membros ?? 0, "pessoa", "pessoas")})` : o.nome}
                  </option>
                ))}
              </select>
            </div>
          )}
          <button type="button" className="btn btn-contorno btn-mini" onClick={adicionarDestino}>
            Adicionar
          </button>
        </div>
        {!opcoes.podeEnviarAGrupos && (
          <p className="com-dica">Enviar a um setor, a uma comissão ou a todos os setores é da secretaria, da administração e da Mesa.</p>
        )}
        {form.destinos.length > 0 && (
          <ul className="com-escolhidos" aria-label="Destinatários escolhidos">
            {form.destinos.map((d) => (
              <li key={chaveDoDestino(d)}>
                <span>{rotuloDoEscolhido(d)}</span>
                <button
                  type="button"
                  aria-label={`Remover ${rotuloDoDestino({ tipo: d.tipo, alvoNome: d.nome })}`}
                  onClick={() => mudar("destinos", form.destinos.filter((x) => chaveDoDestino(x) !== chaveDoDestino(d)))}
                >
                  <span aria-hidden="true">×</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="com-alcance" aria-live="polite">
          {alcance ?? "Nenhum destinatário ainda."}
        </p>
        {erros.destinos && (
          <p className="com-erro-campo" id="com-erro-destinos">
            {erros.destinos}
          </p>
        )}
      </fieldset>

      <div className="com-campo">
        <label htmlFor="com-assunto">Assunto</label>
        <input
          id="com-assunto"
          value={form.assunto}
          onChange={(e) => mudar("assunto", e.target.value)}
          aria-invalid={!!erros.assunto}
          aria-describedby={descreve("assunto")}
          autoComplete="off"
        />
        {erros.assunto && (
          <p className="com-erro-campo" id="com-erro-assunto">
            {erros.assunto}
          </p>
        )}
      </div>

      <div className="com-campo">
        <label htmlFor="com-corpo">Texto</label>
        <textarea
          id="com-corpo"
          rows={10}
          value={form.corpo}
          onChange={(e) => mudar("corpo", e.target.value)}
          aria-invalid={!!erros.corpo}
          aria-describedby={descreve("corpo", "com-corpo-dica")}
        />
        <p className="com-dica" id="com-corpo-dica">
          Texto simples: as quebras de linha são mantidas. Um passo a passo pode ir em itens numerados.
        </p>
        {erros.corpo && (
          <p className="com-erro-campo" id="com-erro-corpo">
            {erros.corpo}
          </p>
        )}
      </div>

      <fieldset className="com-bloco">
        <legend>Ciência</legend>
        <label className="com-marcar">
          <input type="checkbox" checked={form.exigeCiencia} onChange={(e) => mudar("exigeCiencia", e.target.checked)} />
          <span>
            Pedir ciência <small>— cada pessoa confirma com um toque que tomou conhecimento, e você vê quem confirmou.</small>
          </span>
        </label>
        {form.exigeCiencia && (
          <div className="com-campo">
            <label htmlFor="com-prazo">Prazo para a ciência (opcional)</label>
            <input
              id="com-prazo"
              type="date"
              value={form.prazo}
              min={hojeNaCasa(agora)}
              onChange={(e) => mudar("prazo", e.target.value)}
              aria-invalid={!!erros.prazo}
              aria-describedby={descreve("prazo")}
            />
            {erros.prazo && (
              <p className="com-erro-campo" id="com-erro-prazo">
                {erros.prazo}
              </p>
            )}
          </div>
        )}
      </fieldset>

      <fieldset className="com-bloco">
        <legend>Anexos</legend>
        <div className="com-campo">
          <label htmlFor="com-anexos">Escolher arquivos (até {LIMITE_DE_ANEXOS}, de até 10 MB cada)</label>
          <input id="com-anexos" type="file" multiple onChange={escolherArquivos} aria-describedby={descreve("anexos")} />
        </div>
        {form.anexos.length > 0 && (
          <ul className="com-escolhidos" aria-label="Arquivos escolhidos">
            {form.anexos.map((a) => (
              <li key={`${a.name}:${a.size}`}>
                <span>
                  {a.name} <small>{tamanhoLegivel(a.size)}</small>
                </span>
                <button type="button" aria-label={`Remover o arquivo ${a.name}`} onClick={() => mudar("anexos", form.anexos.filter((x) => x !== a))}>
                  <span aria-hidden="true">×</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {recusados.length > 0 && (
          <div role="status" className="com-erro-campo">
            {recusados.map((r) => (
              <p key={r}>{r}</p>
            ))}
          </div>
        )}
        {erros.anexos && (
          <p className="com-erro-campo" id="com-erro-anexos">
            {erros.anexos}
          </p>
        )}
      </fieldset>

      <fieldset className="com-bloco">
        <legend>Ligar a um item do sistema (opcional)</legend>
        <div className="com-linha-destino">
          <div className="com-campo">
            <label htmlFor="com-objeto-tipo">Item</label>
            <select id="com-objeto-tipo" value={form.objetoTipo} onChange={(e) => mudar("objetoTipo", e.target.value as TipoObjeto | "")}>
              <option value="">Nenhum</option>
              <option value="sessao">Uma sessão</option>
              <option value="proposicao">Uma proposição</option>
              <option value="protocolo">Um protocolo</option>
            </select>
          </div>
          {form.objetoTipo && (
            <div className="com-campo com-campo-largo">
              <label htmlFor="com-objeto-ref">Link ou identificador</label>
              <input
                id="com-objeto-ref"
                value={form.objetoRef}
                onChange={(e) => mudar("objetoRef", e.target.value)}
                aria-invalid={!!erros.objeto}
                aria-describedby={descreve("objeto", "com-objeto-dica")}
                autoComplete="off"
              />
            </div>
          )}
        </div>
        {form.objetoTipo && (
          <p className="com-dica" id="com-objeto-dica">
            Abra o item noutra aba e cole aqui o endereço da página. Quem receber verá o atalho para ele.
          </p>
        )}
        {erros.objeto && (
          <p className="com-erro-campo" id="com-erro-objeto">
            {erros.objeto}
          </p>
        )}
      </fieldset>

      <div className="com-acoes">
        <button type="submit" className="btn btn-primaria">
          Revisar o envio
        </button>
        <Link className="btn btn-fantasma" href={comToken("/caixa", token)} prefetch={false}>
          Cancelar
        </Link>
      </div>
    </form>
  );
}
