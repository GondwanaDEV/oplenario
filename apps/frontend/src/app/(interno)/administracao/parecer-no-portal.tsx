"use client";

// Bloco "Parecer jurídico no portal" da área do administrador (ADR-0019 fatia 2a, Eixo 4). Por padrão o parecer jurídico
// assinado só vai ao portal DEPOIS da deliberação da matéria (LAI art. 7º §3º); a Casa pode antecipar para "assim que
// o jurídico assinar". A consulta avulsa nunca vai ao portal. Só o `admin_ente` lê e altera (a authz real é o backend).

import { useState } from "react";
import { AVISO_CONSULTA_AVULSA, ROTULO_PORTAL, frasePortal } from "@/lib/juridico-vista";
import { salvarParametrosParecerJuridico, useParametrosParecerJuridico } from "@/lib/use-juridico";

export function ParecerNoPortal({ token }: { token: string | null }) {
  const { estado, setEstado } = useParametrosParecerJuridico(token);
  // a escolha em curso; `null` = ainda não mexeu (vale o que está salvo)
  const [escolha, setEscolha] = useState<boolean | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [salvo, setSalvo] = useState(false);

  const atual = estado.fase === "pronto" ? estado.dado.publicarAoAssinar : null;
  const valor = escolha ?? atual;
  const mudou = atual !== null && valor !== atual;

  async function salvar() {
    if (valor === null) return;
    setEnviando(true);
    setErro(null);
    setSalvo(false);
    const r = await salvarParametrosParecerJuridico(token, valor);
    setEnviando(false);
    if (r.ok) {
      setEstado({ fase: "pronto", dado: r.dado });
      setEscolha(null);
      setSalvo(true);
    } else setErro(r.mensagem);
  }

  return (
    <section className="adm-auditoria" aria-labelledby="adm-portal-titulo">
      <h2 id="adm-portal-titulo">{ROTULO_PORTAL.titulo}</h2>
      <p className="adm-texto">
        Escolha quando o parecer jurídico assinado aparece no portal do cidadão. O parecer é opinativo, e o portal mostra
        sempre só o vigente. {AVISO_CONSULTA_AVULSA}
      </p>
      {estado.fase === "carregando" && <p className="adm-texto" role="status">Carregando a configuração…</p>}
      {estado.fase === "erro" && <p role="alert" className="adm-texto">{estado.mensagem}</p>}
      {estado.fase === "pronto" && valor !== null && (
        <>
          <fieldset className="adm-portal-opcoes">
            <legend className="adm-texto">Quando publicar</legend>
            <label>
              <input type="radio" name="adm-portal" checked={valor === false}
                onChange={() => { setEscolha(false); setSalvo(false); }} />
              {ROTULO_PORTAL.depois}
            </label>
            <label>
              <input type="radio" name="adm-portal" checked={valor === true}
                onChange={() => { setEscolha(true); setSalvo(false); }} />
              {ROTULO_PORTAL.ao_assinar}
            </label>
          </fieldset>
          <p className="adm-texto">Hoje: {frasePortal(estado.dado.publicarAoAssinar)}</p>
          {salvo && <p role="status" className="adm-aviso">Configuração salva.</p>}
          {erro && <p role="alert" className="adm-texto">{erro}</p>}
          <button type="button" className="btn btn-contorno btn-mini" disabled={enviando || !mudou} onClick={salvar}>
            {enviando ? "Salvando…" : "Salvar"}
          </button>
        </>
      )}
    </section>
  );
}
