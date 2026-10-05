"use client";

// A sessão no cabeçalho da chamada, da ata e da transcrição (ADR-0024, fatia 6): essas telas só carregam o `sessaoId`
// (`ChamadaOut`, as transcrições e `AtaSessaoOut`), então leem a sessão à parte, pela MESMA leitura do Comando da Mesa
// (`useConducaoSessao` → `GET /api/sessoes/:id`, com o token da página). Com ela, o cabeçalho diz qual é a sessão
// ("Sessão ordinária nº 15") e a tela publica a dica da Clara.
//
// Esta leitura NUNCA bloqueia a tela: enquanto carrega, ou se o servidor recusar (403/404) ou a rede falhar, o nome é
// `null` (o cabeçalho fica como antes) e não há dica. A tela segue com os próprios dados.

import { useConducaoSessao } from "@/lib/use-conducao-sessao";
import { nomeDaSessaoNoCabecalho } from "@/lib/rotulos-sessao";
import { dicaDaAta, dicaDaSessao, useDicaDaClara } from "@/app/(interno)/clara/dica";

/** `dica`: "sessao" (chamada, transcrição), "ata" (a revisão da ata) ou `null` para não publicar nada ainda (a ata só
 *  publica a dica com a própria ata carregada). Devolve o nome da sessão para o cabeçalho, ou `null`. */
export function useSessaoNoCabecalho(id: string, token: string | null, dica: "sessao" | "ata" | null): string | null {
  const { sessao } = useConducaoSessao(id, token);
  useDicaDaClara(!sessao || !dica ? null : dica === "ata" ? dicaDaAta(sessao) : dicaDaSessao(sessao));
  return nomeDaSessaoNoCabecalho(sessao);
}
