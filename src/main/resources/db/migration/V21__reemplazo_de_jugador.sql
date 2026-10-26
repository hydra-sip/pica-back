CREATE TABLE reemplazo (
    id BIGSERIAL PRIMARY KEY,
    inscripcion_id BIGINT NOT NULL,
    equipo_id BIGINT NOT NULL,
    sale_usuario_id BIGINT NOT NULL,
    entra_usuario_id BIGINT NOT NULL,
    realizado_por_usuario_id BIGINT NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT fk_reemplazo_inscripcion
        FOREIGN KEY (inscripcion_id, equipo_id)
        REFERENCES inscripcion (id, equipo_id),
    CONSTRAINT fk_reemplazo_sale_integrante
        FOREIGN KEY (sale_usuario_id, equipo_id)
        REFERENCES usuario_equipo (usuario_id, equipo_id),
    CONSTRAINT fk_reemplazo_entra_integrante
        FOREIGN KEY (entra_usuario_id, equipo_id)
        REFERENCES usuario_equipo (usuario_id, equipo_id),
    CONSTRAINT fk_reemplazo_realizado_por
        FOREIGN KEY (realizado_por_usuario_id)
        REFERENCES usuario (id),
    CONSTRAINT ck_reemplazo_usuarios_distintos CHECK (sale_usuario_id <> entra_usuario_id)
);

CREATE INDEX idx_reemplazo_inscripcion_creado
    ON reemplazo (inscripcion_id, creado_en, id);
