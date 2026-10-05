import { cookies } from "next/headers";
import { redirect } from "next/navigation";

// A raiz `/` não é uma página: quem chega sem sessão vai direto à entrada pelo CPF (que já descobre a Câmara da
// pessoa), e quem já entrou vai a `/inicio`, que leva cada papel à sua tela. Quem decide primeiro é o middleware;
// esta página só repete a regra para o caso de a raiz ser servida sem ele. Até 05/10/2026 havia aqui uma página de
// passagem ("Entrar na sua Câmara" e o status), um clique a mais antes do login.
export default async function Raiz() {
  const sessao = (await cookies()).has("sessao");
  redirect(sessao ? "/inicio" : "/entrar");
}
