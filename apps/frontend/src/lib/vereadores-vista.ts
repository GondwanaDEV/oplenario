// Vista da LISTA PÚBLICA dos vereadores em exercício (GET /portal/casa/{ente}/vereadores): contrato
// `VereadoresOut` -> o que a tela desenha. Toda decisão de APRESENTAÇÃO mora aqui, como no perfil
// (`perfil-vereador-vista.ts`): o servidor faz pass-through cru do cadastro, e o fallback do apelido para o nome
// civil, a ordem e o texto são da tela.
//
// NENHUM UUID nasce na tela: o `vereadorId` só entra no `href` do perfil (codificado), nunca como texto, nem como
// iniciais do avatar.

import type { VereadoresOut } from "./contrato-portal.gen";
import { derivarIniciais } from "./perfil-vereador-vista";

export type VereadorDaListaVista = {
  vereadorId: string;
  nome: string; // o apelido parlamentar, ou o nome civil quando não há
  nomeSecundario: string | null; // o nome civil, SÓ quando difere do parlamentar
  iniciais: string;
  partido: string | null;
  cargoMesa: string | null;
  href: string; // o perfil público
};

export type ListaVista = {
  vereadores: VereadorDaListaVista[];
  /** "21 vereadores em exercício" — o número é o da lista que a tela mostra, nunca outro. */
  resumo: string;
};

/** O endereço do perfil público. O `ente` vem do path já decodificado pelo Next: codificado aqui, como o resto do
 *  portal, para um `../` nunca resolver para fora de /portal/casa/. */
export function hrefDoPerfil(ente: string, vereadorId: string): string {
  return `/portal/casa/${segmento(ente)}/vereadores/${segmento(vereadorId)}`;
}

// `encodeURIComponent` não escapa "." — um segmento só de pontos ("..") continuaria subindo um nível na resolução do
// navegador. Mesmo reforço de `codificarSegmento` em portal-api.ts.
function segmento(s: string): string {
  const c = encodeURIComponent(s);
  return /^\.+$/.test(c) ? c.replace(/\./g, "%2E") : c;
}

export function resumoDaLista(n: number): string {
  if (n === 0) return "Nenhum vereador em exercício";
  return n === 1 ? "1 vereador em exercício" : `${n} vereadores em exercício`;
}

export function derivarLista(out: VereadoresOut, ente: string): ListaVista {
  const vereadores = out.vereadores
    .map((v): VereadorDaListaVista => {
      // `||` e não `??`: apelido em branco conta como ausente (o servidor não normaliza o apelido de propósito).
      const apelido = v.nomeParlamentar?.trim() || null;
      const nome = apelido || v.nomeCivil;
      return {
        vereadorId: v.vereadorId,
        nome,
        // iguais não repetem: o mesmo nome duas vezes insinua duas pessoas.
        nomeSecundario: apelido && apelido !== v.nomeCivil ? v.nomeCivil : null,
        iniciais: derivarIniciais(nome),
        partido: v.partido,
        cargoMesa: v.cargoMesa,
        href: hrefDoPerfil(ente, v.vereadorId),
      };
    })
    // ordem alfabética do nome EXIBIDO (a que o cidadão lê), e não a do cadastro; o id desempata homônimos para a
    // ordem não mudar entre duas leituras.
    .sort((a, b) => a.nome.localeCompare(b.nome, "pt-BR", { sensitivity: "base" }) || a.vereadorId.localeCompare(b.vereadorId));
  return { vereadores, resumo: resumoDaLista(vereadores.length) };
}
