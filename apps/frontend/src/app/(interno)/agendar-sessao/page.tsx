"use client";

// Rota /agendar-sessao (interno) — o formulário de agendar uma nova sessão (GAP docs/20: POST /sessoes só
// existia via API). Gate de papel via <GuardSecretaria> (a authz REAL é o backend: exige-papel "secretario"
// em POST /sessoes; o guard só evita mostrar o erro cru a quem não tem o papel). AuthProvider + TemaProvider
// vêm do layout do grupo (interno).

import { GuardSecretaria } from "../guard-secretaria";
import { ConteudoAgendarSessao } from "./conteudo-agendar-sessao";

export default function PaginaAgendarSessao() {
  return (
    <GuardSecretaria>
      <ConteudoAgendarSessao />
    </GuardSecretaria>
  );
}
