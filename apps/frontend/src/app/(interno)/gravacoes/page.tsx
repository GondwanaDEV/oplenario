"use client";

// Rota /gravacoes (interno) — a fila de GRAVAÇÕES recebidas sem sessão (Faixa A / A.2 da Track IA). O arquivo do
// OBS chega pelo utilitário de captação; a secretaria confere e vincula à sessão. Gate de papel via
// <GuardSecretaria> (a authz REAL é o backend: exige-papel "secretario" em GET /gravacoes/pendentes e no vínculo).

import { GuardSecretaria } from "../guard-secretaria";
import { ConteudoGravacoes } from "./conteudo-gravacoes";

export default function PaginaGravacoes() {
  return (
    <GuardSecretaria>
      <ConteudoGravacoes />
    </GuardSecretaria>
  );
}
