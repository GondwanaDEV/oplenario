(ns oplenario.motor.db.regra-tenant)

;; funcoes sobre datasource — 'motor.compliance_regra_tenant' (binding por tenant, B2 §22.7.6). Resolucao
;; POR ESCOPO junta com template_compliance no MESMO schema (motor) -> sem cross-schema JOIN (§22.10). §22.4.4 deferida.
