-- ordem reversa: binding (FK -> template) antes da definicao; depois as de dominio; por fim o schema.
DROP TABLE IF EXISTS motor.compliance_regra_tenant;
DROP TABLE IF EXISTS motor.template_compliance;
DROP TABLE IF EXISTS motor.prazo_dominio_vigente;
DROP TABLE IF EXISTS motor.calendario_feriado;
DROP TABLE IF EXISTS motor.registry_catalogo_versao;
DROP SCHEMA IF EXISTS motor;
