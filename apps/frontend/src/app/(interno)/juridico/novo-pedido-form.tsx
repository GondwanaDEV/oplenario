"use client";

// Formulário de PEDIDO DE PARECER (ADR-0019, Eixo 2), da secretaria. Serve a duas portas: a consulta avulsa da fila
// `/juridico` (sem matéria: decoro, contas, admissibilidade de CPI — o assunto é obrigatório) e o "Pedir parecer
// jurídico" da ficha da matéria (com `proposicaoId`: o assunto é opcional e o servidor põe "Análise jurídica da
// matéria"). O pedido NÃO move a matéria: é um documento a mais na ficha. `emNomeDe` registra a determinação da
// Presidência, quando é o caso.

import { useState } from "react";
import { criarPedido } from "@/lib/use-juridico";
import {
  ASSUNTO_MAX,
  ASSUNTO_PADRAO_DA_MATERIA,
  EM_NOME_DE_MAX,
  validarPedido,
  type EntradaPedido,
} from "@/lib/juridico-vista";
import type { PedidoJuridicoOut } from "@/lib/contrato-juridico.gen";
import "./juridico.css";

export function NovoPedidoForm({ token, proposicaoId, onCriado, onCancelar }: {
  token: string | null;
  proposicaoId?: string;
  onCriado: (pedido: PedidoJuridicoOut) => void;
  onCancelar: () => void;
}) {
  const comMateria = !!proposicaoId;
  const [entrada, setEntrada] = useState<EntradaPedido>({ assunto: "", prazo: "", emNomeDe: "" });
  const [tocado, setTocado] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const erros = validarPedido(entrada, comMateria);
  const valido = Object.keys(erros).length === 0;
  const idBase = comMateria ? "np-m" : "np-a";

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido || enviando) return;
    setEnviando(true);
    setErro(null);
    const r = await criarPedido(token, entrada, proposicaoId);
    setEnviando(false);
    if (r.ok) onCriado(r.dado);
    else setErro(r.mensagem);
  }

  function mudar<K extends keyof EntradaPedido>(campo: K, valor: EntradaPedido[K]) {
    setEntrada((atual) => ({ ...atual, [campo]: valor }));
  }

  return (
    <form className="jur-form" onSubmit={aoEnviar} aria-label={comMateria ? "Pedir parecer jurídico da matéria" : "Novo pedido de parecer"}>
      <p className="jur-dica">
        {comMateria
          ? "O pedido vai para a fila do jurídico e não move a matéria na tramitação: o parecer é mais um documento na ficha."
          : "Consulta avulsa, sem matéria: o jurídico responde ao que a Presidência ou a Mesa perguntar. Ela não vai ao portal."}
      </p>
      <div className="jur-campo">
        <label htmlFor={`${idBase}-assunto`}>{comMateria ? "Assunto (opcional)" : "Assunto*"}</label>
        <textarea id={`${idBase}-assunto`} rows={3} value={entrada.assunto} maxLength={ASSUNTO_MAX + 50}
          placeholder={comMateria ? ASSUNTO_PADRAO_DA_MATERIA : "O que o jurídico deve analisar"}
          onChange={(e) => mudar("assunto", e.target.value)}
          aria-invalid={tocado && !!erros.assunto} aria-describedby={erros.assunto ? `${idBase}-assunto-erro` : undefined} />
        {tocado && erros.assunto && <p id={`${idBase}-assunto-erro`} role="alert" className="jur-erro-campo">{erros.assunto}</p>}
      </div>
      <div className="jur-campo">
        <label htmlFor={`${idBase}-prazo`}>Prazo (opcional)</label>
        <input id={`${idBase}-prazo`} type="date" value={entrada.prazo} onChange={(e) => mudar("prazo", e.target.value)}
          aria-invalid={tocado && !!erros.prazo} aria-describedby={erros.prazo ? `${idBase}-prazo-erro` : undefined} />
        {tocado && erros.prazo && <p id={`${idBase}-prazo-erro`} role="alert" className="jur-erro-campo">{erros.prazo}</p>}
      </div>
      <div className="jur-campo">
        <label htmlFor={`${idBase}-nome`}>Em nome de (opcional)</label>
        <input id={`${idBase}-nome`} value={entrada.emNomeDe} maxLength={EM_NOME_DE_MAX + 20} placeholder="Ex.: Presidência"
          onChange={(e) => mudar("emNomeDe", e.target.value)}
          aria-invalid={tocado && !!erros.emNomeDe} aria-describedby={erros.emNomeDe ? `${idBase}-nome-erro` : undefined} />
        {tocado && erros.emNomeDe && <p id={`${idBase}-nome-erro`} role="alert" className="jur-erro-campo">{erros.emNomeDe}</p>}
      </div>
      {erro && <p role="alert" className="jur-erro">{erro}</p>}
      <div className="jur-acoes">
        <button type="submit" className="btn btn-primaria" disabled={enviando || (tocado && !valido)}>
          {enviando ? "Enviando…" : "Abrir o pedido"}
        </button>
        <button type="button" className="btn btn-fantasma" disabled={enviando} onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
