-- HU-605 (PICA-347): el organizador cancela su torneo. Sin gastos se aprueba sola y se reintegra todo; la fila queda
-- como registro de la decisión. Una por torneo.
CREATE TABLE cancelacion (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT NOT NULL,
    estado VARCHAR(20) NOT NULL,
    revisor_id BIGINT,
    revisada_en TIMESTAMPTZ,
    motivo_rechazo VARCHAR(500),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_cancelacion PRIMARY KEY (id),
    CONSTRAINT fk_cancelacion_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_cancelacion_revisor FOREIGN KEY (revisor_id) REFERENCES usuario (id),
    CONSTRAINT uq_cancelacion_torneo_id UNIQUE (torneo_id),
    CONSTRAINT ck_cancelacion_estado CHECK (estado IN ('AUTOMATICA', 'EN_REVISION', 'APROBADA', 'RECHAZADA'))
);

-- La cancelación y cada reintegro quedan en la auditoría.
ALTER TABLE auditoria DROP CONSTRAINT ck_auditoria_accion;
ALTER TABLE auditoria
    ADD CONSTRAINT ck_auditoria_accion CHECK (accion IN ('LOGIN', 'LOGIN_FALLIDO', 'LOGOUT', 'ALTA', 'MODIFICACION',
                                                         'BAJA', 'BLOQUEO', 'DESBLOQUEO', 'PAGO', 'LAUDO', 'SORTEO',
                                                         'CIERRE_INSCRIPCION', 'CIERRE_VENTA', 'LIQUIDACION',
                                                         'CANCELACION', 'REINTEGRO'));
