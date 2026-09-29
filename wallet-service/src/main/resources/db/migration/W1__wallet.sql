-- Wallet Backend (ERSo 2026-001 y 2026-003). Comparte la base de datos con el VDR; todas las tablas llevan prefijo wallet_.
-- NINGUNA columna almacena una clave privada: solo claves públicas (JWK), huellas, niveles y bytes cifrados opacos.

CREATE TABLE wallet_citizens (
    id              uuid PRIMARY KEY,
    recovery_salt   bytea       NOT NULL,
    recovery_hash   bytea       NOT NULL,
    failed_attempts integer     NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wallet_challenges (
    id          uuid PRIMARY KEY,
    nonce       text        NOT NULL,
    purpose     text        NOT NULL CHECK (purpose IN ('ACTIVATION', 'DID_KEY')),
    citizen_id  uuid        NOT NULL REFERENCES wallet_citizens(id),
    instance_id uuid,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);

CREATE TABLE wallet_recovery_tokens (
    token_hash bytea PRIMARY KEY,
    citizen_id uuid        NOT NULL REFERENCES wallet_citizens(id),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);

CREATE TABLE wallet_instances (
    id             uuid PRIMARY KEY,
    citizen_id     uuid        NOT NULL REFERENCES wallet_citizens(id),
    status         text        NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    public_jwk     jsonb       NOT NULL,
    thumbprint     text        NOT NULL,
    declared_level text        NOT NULL,
    verified_level text        NOT NULL,
    attested       boolean     NOT NULL,
    device_profile jsonb       NOT NULL,
    token_hash     bytea       NOT NULL,
    activated_at   timestamptz NOT NULL DEFAULT now(),
    revoked_at     timestamptz,
    revoked_reason text,
    replaced_by    uuid
);
-- Una sola cartera activa por ciudadano (regla garantizada por la base de datos, no solo por la aplicación).
CREATE UNIQUE INDEX wallet_one_active_per_citizen ON wallet_instances (citizen_id) WHERE status = 'ACTIVE';

CREATE TABLE wallet_did_publications (
    id                uuid PRIMARY KEY,
    instance_id       uuid        NOT NULL REFERENCES wallet_instances(id),
    did               text        NOT NULL,
    doc_hash          text        NOT NULL,
    document          jsonb       NOT NULL,
    key_level         text        NOT NULL,
    vdr_challenge_id  text        NOT NULL,
    vdr_nonce         text        NOT NULL,
    vdr_audience      text        NOT NULL,
    status            text        NOT NULL CHECK (status IN ('AWAITING_PROOF', 'PUBLISHED', 'PENDING', 'FAILED')),
    vdr_version       integer,
    public_url        text,
    failure           text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    completed_at      timestamptz
);

-- Respaldo CIFRADO del DID Document: el backend guarda bytes opacos que solo el titular sabe abrir.
CREATE TABLE wallet_did_backups (
    instance_id uuid PRIMARY KEY REFERENCES wallet_instances(id),
    blob        bytea       NOT NULL,
    checksum    text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wallet_audit (
    id          bigserial PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor       text        NOT NULL,
    action      text        NOT NULL,
    citizen_id  uuid,
    instance_id uuid,
    detail      jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX wallet_audit_instance_idx ON wallet_audit (instance_id);
CREATE INDEX wallet_audit_citizen_idx ON wallet_audit (citizen_id);

CREATE FUNCTION wallet_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'tabla append-only: % no permitido sobre %', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER wallet_audit_append_only BEFORE UPDATE OR DELETE ON wallet_audit
    FOR EACH ROW EXECUTE FUNCTION wallet_forbid_mutation();
