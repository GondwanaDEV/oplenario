"use client";

// Seletor de COAUTORES do requerimento (fatia 2c) — o bloco "Autoria" de autoria-apoiamento.html: você é o
// autor principal; adicione colegas, que recebem o pedido e confirmam a subscrição com a própria assinatura.
// Opcional: sem coautor o requerimento é individual e segue o fluxo da 2a (assina e protocola na hora).
// Os colegas vêm do servidor (mandato vigente nesta Casa, menos você) — a tela não inventa quem pode.

import { useState } from "react";
import type { ColegaOut } from "@/lib/contrato-legislativo.gen";
import { filtrarColegas, iniciais } from "@/lib/subscricao-vista";

export function Coautores({
  colegas,
  selecionados,
  onMudar,
}: {
  colegas: readonly ColegaOut[];
  selecionados: readonly string[];
  onMudar: (ids: string[]) => void;
}) {
  const [busca, setBusca] = useState("");
  const escolhidos = colegas.filter((c) => selecionados.includes(c.id));
  const opcoes = filtrarColegas(colegas, busca).filter((c) => !selecionados.includes(c.id));

  return (
    <fieldset className="coa">
      <legend>Coautores (opcional)</legend>
      <p className="req-ajuda">
        Colegas que você convida recebem o pedido e confirmam a subscrição com a própria assinatura. Quem não
        confirmar até você protocolar não consta.
      </p>

      {escolhidos.length > 0 && (
        <ul className="coa-escolhidos" aria-label="Coautores convidados">
          {escolhidos.map((c) => (
            <li key={c.id} className="coa-linha">
              <span className="coa-av" aria-hidden="true">
                {iniciais(c.nome)}
              </span>
              <span className="coa-nm">
                <b>{c.nome}</b>
                {c.partido && <span>{c.partido}</span>}
              </span>
              <button
                type="button"
                className="coa-rem"
                aria-label={`Tirar ${c.nome}`}
                onClick={() => onMudar(selecionados.filter((id) => id !== c.id))}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="req-campo">
        <label htmlFor="coa-busca">Adicionar colega</label>
        <input
          id="coa-busca"
          type="search"
          placeholder="Nome ou partido"
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
          autoComplete="off"
        />
      </div>
      {busca.trim() !== "" && (
        <ul className="coa-opcoes" aria-label="Colegas encontrados">
          {opcoes.length === 0 ? (
            <li className="coa-nada">Ninguém com esse nome entre os colegas com mandato.</li>
          ) : (
            opcoes.slice(0, 8).map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  className="coa-add"
                  onClick={() => {
                    onMudar([...selecionados, c.id]);
                    setBusca("");
                  }}
                >
                  <span className="coa-av" aria-hidden="true">
                    {iniciais(c.nome)}
                  </span>
                  <span className="coa-nm">
                    <b>{c.nome}</b>
                    {c.partido && <span>{c.partido}</span>}
                  </span>
                  <span className="coa-mais" aria-hidden="true">
                    Convidar
                  </span>
                </button>
              </li>
            ))
          )}
        </ul>
      )}
    </fieldset>
  );
}
