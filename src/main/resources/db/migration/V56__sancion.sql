-- Sanciones a jugadores (HU-803, T803-1): el organizador o un árbitro del torneo sanciona a un jugador con un motivo,
-- y la gravedad mueve su índice de fair play (HU-802). El partido es opcional: una sanción puede ser por algo que pasó
-- fuera de un partido. anulada_en queda para anularla sin borrar el registro.

CREATE TABLE sancion (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,
    partido_id BIGINT,
    gravedad VARCHAR(10) NOT NULL,
    motivo TEXT NOT NULL,
    aplicada_por BIGINT NOT NULL,
    aplicada_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    anulada_en TIMESTAMPTZ,
    CONSTRAINT pk_sancion PRIMARY KEY (id),
    CONSTRAINT fk_sancion_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_sancion_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_sancion_partido FOREIGN KEY (partido_id) REFERENCES partido (id),
    CONSTRAINT fk_sancion_aplicada_por FOREIGN KEY (aplicada_por) REFERENCES usuario (id),
    CONSTRAINT ck_sancion_gravedad CHECK (gravedad IN ('LEVE', 'MEDIA', 'GRAVE')),
    CONSTRAINT ck_sancion_motivo CHECK (btrim(motivo) <> '')
);

CREATE INDEX idx_sancion_torneo_id ON sancion (torneo_id);
CREATE INDEX idx_sancion_usuario_id ON sancion (usuario_id);

-- Cada sanción queda en la auditoría.
ALTER TABLE auditoria DROP CONSTRAINT ck_auditoria_accion;
ALTER TABLE auditoria
    ADD CONSTRAINT ck_auditoria_accion CHECK (accion IN ('LOGIN', 'LOGIN_FALLIDO', 'LOGOUT', 'ALTA', 'MODIFICACION',
                                                         'BAJA', 'BLOQUEO', 'DESBLOQUEO', 'PAGO', 'LAUDO', 'SORTEO',
                                                         'CIERRE_INSCRIPCION', 'CIERRE_VENTA', 'LIQUIDACION',
                                                         'CANCELACION', 'REINTEGRO', 'SANCION'));
