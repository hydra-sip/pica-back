-- Reprogramación de un torneo (CR-01, PICA-391): el organizador cambia las fechas sin cancelarlo y la plata sigue en
-- custodia. Hasta rechazo_hasta los inscriptos pueden decir que la nueva fecha no les sirve; al vencer el plazo la
-- inscripción se reabre hasta cierre_inscripcion_nuevo si quedaron lugares (plazo_procesado_en marca que ya se miró).
-- Queda una fila por reprogramación: es el historial que se muestra en la ficha.

CREATE TABLE reprogramacion (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT NOT NULL,
    fecha_inicio_anterior TIMESTAMPTZ NOT NULL,
    fecha_fin_anterior TIMESTAMPTZ,
    fecha_inicio_nueva TIMESTAMPTZ NOT NULL,
    fecha_fin_nueva TIMESTAMPTZ,
    cierre_inscripcion_nuevo TIMESTAMPTZ NOT NULL,
    rechazo_hasta TIMESTAMPTZ NOT NULL,
    motivo VARCHAR(500) NOT NULL,
    plazo_procesado_en TIMESTAMPTZ,
    creada_por BIGINT NOT NULL,
    creada_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_reprogramacion PRIMARY KEY (id),
    CONSTRAINT fk_reprogramacion_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_reprogramacion_creada_por FOREIGN KEY (creada_por) REFERENCES usuario (id),
    CONSTRAINT ck_reprogramacion_motivo CHECK (btrim(motivo) <> ''),
    CONSTRAINT ck_reprogramacion_fechas CHECK (fecha_fin_nueva IS NULL OR fecha_fin_nueva >= fecha_inicio_nueva),
    CONSTRAINT ck_reprogramacion_cierre CHECK (cierre_inscripcion_nuevo <= fecha_inicio_nueva)
);

CREATE INDEX idx_reprogramacion_torneo_id ON reprogramacion (torneo_id);

-- Una sola con el plazo abierto por torneo.
CREATE UNIQUE INDEX uq_reprogramacion_plazo_abierto ON reprogramacion (torneo_id) WHERE plazo_procesado_en IS NULL;

-- Cada reprogramación queda en la auditoría.
ALTER TABLE auditoria DROP CONSTRAINT ck_auditoria_accion;
ALTER TABLE auditoria
    ADD CONSTRAINT ck_auditoria_accion CHECK (accion IN ('LOGIN', 'LOGIN_FALLIDO', 'LOGOUT', 'ALTA', 'MODIFICACION',
                                                         'BAJA', 'BLOQUEO', 'DESBLOQUEO', 'PAGO', 'LAUDO', 'SORTEO',
                                                         'CIERRE_INSCRIPCION', 'CIERRE_VENTA', 'LIQUIDACION',
                                                         'CANCELACION', 'REINTEGRO', 'SANCION', 'CALIFICACION',
                                                         'REPROGRAMACION'));
