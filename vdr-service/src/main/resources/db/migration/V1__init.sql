-- VDR de extensión (ERSo 2026-004/006/008). Registro para did:web respaldado en PostgreSQL.

CREATE TABLE entity_accounts (
    client_id    text PRIMARY KEY,
    display_name text        NOT NULL,
    enabled      boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Ruta did:web reservada (namespace propio) y su perfil de métodos de verificación.
CREATE TABLE namespaces (
    path            text PRIMARY KEY,
    owner_client_id text        NOT NULL REFERENCES entity_accounts(client_id),
    profile         jsonb       NOT NULL,
    reserved_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE did_documents (
    did             text PRIMARY KEY,
    namespace       text        NOT NULL REFERENCES namespaces(path),
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'DEACTIVATED')),
    current_version integer     NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Historial de versiones: solo se agrega, nunca se modifica (trigger más abajo).
CREATE TABLE did_document_versions (
    did          text        NOT NULL REFERENCES did_documents(did),
    version      integer     NOT NULL,
    operation    text        NOT NULL CHECK (operation IN ('CREATE', 'UPDATE', 'DEACTIVATE')),
    document     text,                       -- JSON canónico; NULL en DEACTIVATE
    hash         text        NOT NULL,
    actor        text        NOT NULL,
    operation_id uuid        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (did, version)
);

CREATE TABLE challenges (
    id         uuid PRIMARY KEY,
    nonce      text        NOT NULL,
    did        text        NOT NULL,
    purpose    text        NOT NULL CHECK (purpose IN ('CREATE', 'UPDATE', 'DEACTIVATE')),
    audience   text        NOT NULL,
    client_id  text        NOT NULL,
    issued_at  timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);

CREATE TABLE operations (
    id              uuid PRIMARY KEY,
    did             text        NOT NULL,
    purpose         text        NOT NULL,
    client_id       text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'SUPERSEDED')),
    version         integer     NOT NULL,
    hash            text        NOT NULL,
    public_url      text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    confirmed_at    timestamptz,
    UNIQUE (did, idempotency_key)
);

CREATE TABLE audit_log (
    id      bigserial PRIMARY KEY,
    at      timestamptz NOT NULL DEFAULT now(),
    actor   text        NOT NULL,
    action  text        NOT NULL,
    did     text,
    version integer,
    detail  jsonb       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_log_did_idx ON audit_log (did);
CREATE INDEX versions_at_idx ON did_document_versions (did, created_at);

CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'tabla append-only: % no permitido sobre %', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER versions_append_only BEFORE UPDATE OR DELETE ON did_document_versions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
