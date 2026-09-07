// Iniciais de um nome para o avatar. Extraído de `app/sessoes/[id]/chamada/page.tsx` (onde era local)
// porque a tribuna do plenário passou a precisar da MESMA regra: as duas telas são irmãs do módulo de
// sessões e mostram o mesmo parlamentar, e duas cópias divergiriam com o tempo — já divergem hoje no
// repo (a cópia de `app/(publico)/balcao-lgpd.tsx` devolve UMA letra para nome de palavra única, esta
// devolve DUAS). Consolidar as três num só lugar mudaria o que o balcão renderiza hoje, então fica
// DÍVIDA registrada, não escopo desta fatia: aqui unificam-se apenas as duas telas de sessões.
//
// `?` para nome vazio/só-espaços é deliberado: a alternativa seria string vazia, que colapsa o avatar e
// tira a âncora visual da linha. Um glifo neutro é honesto — diz "não há nome", não finge um.
export function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return "?";
  if (partes.length === 1) return partes[0].slice(0, 2).toUpperCase();
  return (partes[0][0] + partes[partes.length - 1][0]).toUpperCase();
}
