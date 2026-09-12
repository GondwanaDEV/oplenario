// Rótulo de exibição derivado dos PAPÉIS do vínculo ativo (identidade.vinculo.papeis) — nunca um CARGO
// (cadastros.mesa_diretora, ex.: "Presidente da Mesa"): o identidade module nunca importa cadastros
// (§22.10), e a leitura de GET /meu/identidade só enxerga papéis, não cargo de Mesa. Achado da demo
// (Daouda, 12/09/2026): o cabeçalho mostrava um ator FIXO — este é o conserto, e a régua é "mostrar o
// papel real", não fingir o cargo que o backend de identidade não vê.
//
// Precedência (não é hierarquia de domínio nova, é só qual rótulo GANHA quando o vínculo carrega mais de
// um papel): secretario e admin_ente nunca co-ocorrem no domínio atual (cada persona da Casa carrega um
// conjunto de papéis fixo) — a ordem entre eles é convenção estável para o dia em que isso mudar.
// admin_ente vem à frente de vereador porque é o papel mais RARO/distintivo do par (só quem administra o
// ente o carrega); um vereador comum não some do rótulo, só perde a prioridade quando também administra.
const ORDEM_DE_PRECEDENCIA: ReadonlyArray<{ papel: string; rotulo: string }> = [
  { papel: "secretario", rotulo: "Secretário(a)" },
  { papel: "admin_ente", rotulo: "Administrador(a) do Ente" },
  { papel: "vereador", rotulo: "Vereador(a)" },
];

const ROTULO_SEM_PAPEL = "Cidadã(o)";

export function rotuloPapel(papeis: string[] | null | undefined): string {
  const conjunto = new Set(papeis ?? []);
  const achado = ORDEM_DE_PRECEDENCIA.find((item) => conjunto.has(item.papel));
  return achado ? achado.rotulo : ROTULO_SEM_PAPEL;
}
