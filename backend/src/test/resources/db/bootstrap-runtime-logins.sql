CREATE ROLE law_api_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE law_worker_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT law_app_command, law_app_query, law_audit_append TO law_api_login;
GRANT law_app_worker TO law_worker_login;
GRANT CONNECT ON DATABASE law_contract_runtime TO law_api_login, law_worker_login;
