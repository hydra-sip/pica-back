CREATE TABLE codigo_canje_oauth (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    codigo_hash VARCHAR(64) NOT NULL,
    usuario_id BIGINT NOT NULL,
    vence_en TIMESTAMPTZ NOT NULL,
    usado BOOLEAN NOT NULL DEFAULT FALSE,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_codigo_canje_oauth PRIMARY KEY (id),
    CONSTRAINT fk_codigo_canje_oauth_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT uq_codigo_canje_oauth_hash UNIQUE (codigo_hash)
);

CREATE INDEX idx_codigo_canje_oauth_usuario_id ON codigo_canje_oauth (usuario_id);
