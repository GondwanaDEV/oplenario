// View-model puro de /entrar (Task 12, Onda D Slice 2 — telas de login PKCE, fast-follow). O login
// handler (app/api/auth/login/route.ts) falha fechado redirecionando para `/entrar?erro=login` sempre
// que a descoberta do tenant falha (404/rede/erro genérico — nunca diferencia qual, mesma razão de não
// vazar se um tenant existe). Antes desta função, a página /entrar (placeholder sem `ente` na URL) nunca
// lia esse `erro` — quem caía ali por login falho via a MESMA cópia genérica de sempre, sem indicação de
// que algo deu errado (beco silencioso). Esta função só deriva a mensagem; a página decide renderizar.

export function mensagemErroEntrada(erro?: string): string | null {
  if (!erro) return null;
  return "Não foi possível concluir o login. Verifique o endereço da sua câmara e tente novamente.";
}
