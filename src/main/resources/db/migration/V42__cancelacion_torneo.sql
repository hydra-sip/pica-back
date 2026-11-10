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

-- Con gastos, la cancelación espera a soporte (T605-2): el torneo queda en revisión, con su motivo, y la plata sigue en
-- custodia. Cada gasto lleva la clave de su comprobante en el almacenamiento.
ALTER TABLE torneo DROP CONSTRAINT ck_torneo_estado;
ALTER TABLE torneo
    ADD CONSTRAINT ck_torneo_estado CHECK (estado IN ('BORRADOR', 'INSCRIPCION_ABIERTA', 'INSCRIPCION_CERRADA',
                                                      'EN_CURSO', 'FINALIZADO', 'CANCELACION_EN_REVISION',
                                                      'CANCELADO'));
ALTER TABLE torneo DROP CONSTRAINT ck_torneo_motivo_cancelacion;
ALTER TABLE torneo
    ADD CONSTRAINT ck_torneo_motivo_cancelacion
        CHECK (estado NOT IN ('CANCELADO', 'CANCELACION_EN_REVISION') OR motivo_cancelacion IS NOT NULL);

CREATE TABLE gasto_cancelacion (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    cancelacion_id BIGINT NOT NULL,
    monto NUMERIC(14,2) NOT NULL,
    concepto VARCHAR(255) NOT NULL,
    comprobante VARCHAR(500) NOT NULL,
    tipo_contenido VARCHAR(50) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_gasto_cancelacion PRIMARY KEY (id),
    CONSTRAINT fk_gasto_cancelacion_cancelacion FOREIGN KEY (cancelacion_id) REFERENCES cancelacion (id),
    CONSTRAINT ck_gasto_cancelacion_monto CHECK (monto > 0)
);

CREATE INDEX idx_gasto_cancelacion_cancelacion_id ON gasto_cancelacion (cancelacion_id);

-- El tope de lo que el organizador puede retener en gastos al cancelar: un % del pozo (lo recaudado en
-- inscripciones). Lo edita el admin (HU-702).
INSERT INTO parametro_plataforma (clave, valor, descripcion, modificado_por)
VALUES ('CANCELACION_GASTOS_TOPE', 20,
        'Tope del % del pozo que el organizador puede retener en gastos al cancelar un torneo',
        'SISTEMA');

ALTER TABLE parametro_plataforma
    ADD CONSTRAINT ck_parametro_plataforma_cancelacion
        CHECK (clave <> 'CANCELACION_GASTOS_TOPE' OR valor BETWEEN 0 AND 100);
