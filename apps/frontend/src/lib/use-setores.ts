"use client";

// Os SETORES da Casa (ADR-0020, Eixo 1): cadastro do `admin_ente` em /administracao. Setor é ENDEREÇO, não
// permissão — por isso mora com os comunicados no FE, não com os papéis. As escritas não leem a resposta: a tela
// recarrega a lista depois de cada uma (ver o cabeçalho de contrato-comunicacao.ts).

import {
  ROTAS_COMUNICACAO as R,
  formaValida,
  type SetoresOut,
} from "./contrato-comunicacao";
import { pedirComunicacao, useCarregarComunicacao } from "./use-comunicados";

export function useSetores(token: string | null) {
  return useCarregarComunicacao<SetoresOut>(token, R.setores, "setores", formaValida.setores);
}

export function criarSetor(token: string | null, nome: string) {
  return pedirComunicacao<unknown>(token, R.setores, "criar-setor", { method: "POST", corpo: { nome: nome.trim() } });
}

/** Renomeia e/ou ativa/desativa. O setor desativado some do formulário de envio; os comunicados antigos ficam. */
export function salvarSetor(token: string | null, id: string, dados: { nome: string; ativo: boolean }) {
  return pedirComunicacao<unknown>(token, R.setor(id), "salvar-setor", {
    method: "PUT",
    corpo: { nome: dados.nome.trim(), ativo: dados.ativo },
  });
}

/** Troca a lotação INTEIRA do setor (a rota não é incremental). */
export function definirMembros(token: string | null, id: string, identidades: string[]) {
  return pedirComunicacao<unknown>(token, R.membrosDoSetor(id), "membros-setor", {
    method: "PUT",
    corpo: { identidades },
  });
}
