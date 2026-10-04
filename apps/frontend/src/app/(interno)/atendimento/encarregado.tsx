"use client";

// O encarregado de dados da Casa (LGPD art. 41): o contato que o portal publica para o titular, e quem responde os
// pedidos da fila LGPD. Bloco discreto no balcão: mostra o contato atual e deixa editar (PUT /api/lgpd/encarregado).

import { useState } from "react";
import { faltaNoEncarregado } from "@/lib/atendimento-vista";
import { salvarEncarregado, useEncarregado, type Encarregado } from "@/lib/use-atendimento";

export function BlocoEncarregado({ token = null }: { token?: string | null }) {
  const { estado, setEstado } = useEncarregado(token);
  const [editando, setEditando] = useState(false);
  const [campos, setCampos] = useState<Encarregado>({ nome: "", rotulo: "", email: "" });
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [salvo, setSalvo] = useState(false);

  if (estado.fase === "carregando") return null;
  const atual = estado.fase === "pronto" ? estado.dado : null;
  const falta = faltaNoEncarregado(campos);

  function abrir() {
    setCampos(atual ?? { nome: "", rotulo: "Encarregado(a) de Dados", email: "" });
    setErro(null);
    setSalvo(false);
    setEditando(true);
  }

  async function salvar() {
    setEnviando(true);
    setErro(null);
    const r = await salvarEncarregado(token, campos);
    setEnviando(false);
    if (r.ok) {
      setEstado({ fase: "pronto", dado: r.dado });
      setEditando(false);
      setSalvo(true);
    } else setErro(r.mensagem);
  }

  const campo = (k: keyof Encarregado, rotulo: string, tipo = "text") => (
    <div className="atd-campo">
      <label htmlFor={`atd-enc-${k}`}>{rotulo}</label>
      <input id={`atd-enc-${k}`} type={tipo} value={campos[k]} maxLength={k === "email" ? 320 : 200}
        onChange={(e) => setCampos({ ...campos, [k]: e.target.value })} />
    </div>
  );

  return (
    <section className="atd-encarregado" aria-labelledby="atd-enc-titulo">
      <h2 id="atd-enc-titulo">Encarregado de dados (LGPD)</h2>
      {estado.fase === "erro" && <p className="atd-erro" role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" && !editando && (
        <>
          {atual ? (
            <p className="atd-enc-atual">
              <b>{atual.nome}</b> · {atual.rotulo} · {atual.email}
            </p>
          ) : (
            <p className="atd-enc-atual">
              A Casa ainda não informou o encarregado de dados. O portal precisa mostrar esse contato ao cidadão (LGPD,
              art. 41).
            </p>
          )}
          {salvo && <p className="atd-ok" role="status">Contato salvo. O portal já mostra o novo contato.</p>}
          <div className="atd-acoes">
            <button type="button" className="btn btn-contorno" onClick={abrir}>
              {atual ? "Editar o contato" : "Informar o encarregado"}
            </button>
          </div>
        </>
      )}
      {editando && (
        <div className="atd-form" role="group" aria-label="Contato do encarregado de dados">
          <p className="atd-dica">Este contato aparece no portal da Câmara para quem quer exercer direitos sobre os próprios dados.</p>
          {campo("nome", "Nome")}
          {campo("rotulo", "Como o cargo aparece no portal")}
          {campo("email", "E-mail de contato", "email")}
          {falta && <p className="atd-falta">{falta}</p>}
          {erro && <p className="atd-erro" role="alert">{erro}</p>}
          <div className="atd-acoes">
            <button type="button" className="btn btn-primaria" disabled={enviando || falta !== null} onClick={salvar}>
              {enviando ? "Salvando…" : "Salvar o contato"}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={() => setEditando(false)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
