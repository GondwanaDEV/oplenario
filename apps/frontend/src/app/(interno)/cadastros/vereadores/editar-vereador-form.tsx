"use client";

// Form "Editar cadastro" (Onda D Slice 4). Mesmo shape de NovoVereadorForm (painel inline, não modal),
// pré-preenchido a partir de `inicial` (a ficha atual). Envia SÓ os campos que mudaram (PATCH parcial —
// espelha useEditarVereador/adapters/in) — se nada mudou, validarEditar recusa com "geral" (preencha ao
// menos um campo), forçando uma alteração real antes do submit.

import { useState } from "react";
import { useEditarVereador } from "@/lib/use-editar-vereador";
import { validarEditar } from "@/lib/cadastro-vereadores-forms";

export function EditarVereadorForm({
  token, vereadorId, inicial, onSucesso, onCancelar,
}: {
  token: string | null;
  vereadorId: string;
  inicial: { nome: string; nomeParlamentar?: string | null };
  onSucesso: (vereadorId: string) => void;
  onCancelar: () => void;
}) {
  const [nome, setNome] = useState(inicial.nome);
  const [nomeParlamentar, setNomeParlamentar] = useState(inicial.nomeParlamentar ?? "");
  const [tocado, setTocado] = useState(false);
  const { editar, estado, erro } = useEditarVereador(token, vereadorId);

  const mudouNome = nome.trim() !== inicial.nome;
  const mudouParlamentar = nomeParlamentar.trim() !== (inicial.nomeParlamentar ?? "");
  const candidato = {
    nome: mudouNome ? nome.trim() : undefined,
    nomeParlamentar: mudouParlamentar ? nomeParlamentar.trim() : undefined,
  };
  const { erros, valido } = validarEditar(candidato);

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      await editar(candidato);
      onSucesso(vereadorId);
    } catch { /* estado 'erro' já exibido abaixo */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Editar cadastro">
      <div className="campo">
        <label htmlFor="ev-nome">Nome</label>
        <input id="ev-nome" value={nome} onChange={(e) => setNome(e.target.value)}
               aria-invalid={tocado && !!erros.nome} aria-describedby={erros.nome ? "ev-nome-erro" : undefined} />
        {tocado && erros.nome && <p id="ev-nome-erro" role="alert" className="campo-erro">{erros.nome}</p>}
      </div>
      <div className="campo">
        <label htmlFor="ev-parlamentar">Nome parlamentar</label>
        <input id="ev-parlamentar" value={nomeParlamentar} onChange={(e) => setNomeParlamentar(e.target.value)} />
      </div>
      {tocado && erros.geral && <p role="alert" className="campo-erro">{erros.geral}</p>}
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Salvando…" : "Salvar alterações"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
