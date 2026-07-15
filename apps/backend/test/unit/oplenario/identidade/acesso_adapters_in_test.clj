(ns oplenario.identidade.acesso-adapters-in-test
  "Gate de entrada da superficie ADMINISTRATIVA de identidade. O CPF e' validado AQUI (nao so' pela
  assertion do db/, que some com -da) — CPF invalido = 400, nunca linha ruim no banco."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.identidade.adapters.in.acesso :as adapters-in]))

(def ^:private ator {:ente-id (random-uuid) :identidade-id (random-uuid)})

(defn- tipo-do [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest cpf-invalido-e-recusado-na-borda
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "11111111111" "nome" "X"})))
      "11-iguais passa no regex mas falha no digito verificador — a borda recusa")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "12345678900" "nome" "X"})))
      "digito verificador errado -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "529.982.247-25" "nome" "X"})))
      "formatado com pontuacao -> 400 (o wire e' 11 digitos crus; nao coagimos silenciosamente)"))

(deftest cpf-valido-passa
  (let [m (adapters-in/criar-identidade->dominio ator {"cpf" "52998224725" "nome" "Helena Matos"})]
    (is (= "52998224725" (:cpf m)))
    (is (= "Helena Matos" (:nome m)))
    (is (uuid? (:id m)) "id gerado aqui, nunca vindo do corpo")))

(deftest chave-forjada-e-recusada
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "52998224725" "nome" "X"
                                                                "id" "00000000-0000-0000-0000-000000000000"})))
      "o :closed do schema recusa `id` forjado — por isso keywordizar NAO filtra antes de validar"))

(deftest conceder-acesso-so-aceita-papeis-conhecidos
  (let [ident (random-uuid)]
    (is (= :validacao/invalido
           (tipo-do #(adapters-in/conceder-acesso->dominio
                      ator {"identidade-id" (str ident) "tipo" "vereador"
                            "papeis" ["admin_ente"] "email" "h@c.local"})))
        "conceder admin_ente por esta rota seria escalada de privilegio — allowlist recusa")
    (let [m (adapters-in/conceder-acesso->dominio
             ator {"identidade-id" (str ident) "tipo" "vereador"
                   "papeis" ["vereador"] "email" "helena@camara.local"})]
      (is (= ident (:identidade-id m)))
      (is (= ["vereador"] (:papeis m))))))

(deftest email-malformado-recusado
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "vereador"
                          "papeis" ["vereador"] "email" "sem-arroba"})))
      "e-mail malformado -> 400 (o convite nunca chegaria; falhar cedo e' honesto)"))
