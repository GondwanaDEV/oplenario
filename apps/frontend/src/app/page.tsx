import Link from "next/link";

// A raiz `/` — a porta pública do produto. Texto fixo, sem binding: nada aqui depende de dado, e nenhum caminho é
// prometido que não exista. Os dois destinos são páginas reais, e `page.test.tsx` confere isso contra o disco.
//
// O que NÃO tem, de propósito: lista de Câmaras (não há rota pública que liste os clientes: o portal de cada Casa
// vive no endereço `/portal/casa/<id>` que ela mesma divulga), preço, contato e promessa de produto. Quando existir
// uma lista pública de Casas, ela entra aqui — não antes.
//
// Quem já está autenticado também chega aqui sem ser redirecionado: o login devolve para `/inicio` (ver
// `api/auth/redirect.ts`), e esta página não depende de sessão.
export default function Home() {
  return (
    <main className="envelope" style={{ maxWidth: 640, padding: "4rem 1.5rem" }}>
      <h1 style={{ fontFamily: "var(--display)", fontSize: "var(--t-40)", lineHeight: 1.1, margin: "0 0 0.4rem", color: "var(--texto)" }}>
        O Plenário
      </h1>
      <p style={{ fontFamily: "var(--display)", fontSize: "var(--t-21)", margin: "0 0 1.2rem", color: "var(--texto)" }}>
        Onde a câmara acontece.
      </p>
      <p style={{ color: "var(--texto-2)", maxWidth: "46ch" }}>
        Plataforma de gestão para câmaras municipais: proposições, sessões e votações, os prazos com o Tribunal de
        Contas e o portal de transparência do cidadão.
      </p>
      <p style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", marginTop: "1.5rem" }}>
        <Link className="btn btn-primaria" href="/entrar">
          Entrar na sua Câmara
        </Link>
        <Link className="btn" href="/status">
          Status da plataforma
        </Link>
      </p>
      <p style={{ color: "var(--texto-2)", fontSize: "var(--t-13)", maxWidth: "46ch", marginTop: "1.5rem" }}>
        Cada Câmara tem o seu endereço de entrada e o seu portal do cidadão: use o link que a sua Câmara divulgou.
      </p>
    </main>
  );
}
