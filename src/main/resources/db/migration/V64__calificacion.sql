-- Calificaciones de jugadores (HU-805, T805-1): el organizador califica a cada jugador que participó de un torneo suyo
-- ya terminado, de 1 a 5, con un comentario opcional, y puede marcarlo como no confiable. Una por jugador y torneo.
-- El puntaje de reputación es el promedio de las que recibió; va aparte del índice de fair play (HU-802).

CREATE TABLE calificacion (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT NOT NULL,
    organizador_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,
    puntaje SMALLINT NOT NULL,
    comentario TEXT,
    no_confiable BOOLEAN NOT NULL DEFAULT FALSE,
    creada_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_calificacion PRIMARY KEY (id),
    CONSTRAINT fk_calificacion_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_calificacion_organizador FOREIGN KEY (organizador_id) REFERENCES usuario (id),
    CONSTRAINT fk_calificacion_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT uk_calificacion_torneo_usuario UNIQUE (torneo_id, usuario_id),
    CONSTRAINT ck_calificacion_puntaje CHECK (puntaje BETWEEN 1 AND 5)
);

CREATE INDEX idx_calificacion_usuario_id ON calificacion (usuario_id);

-- Cada calificación queda en la auditoría.
ALTER TABLE auditoria DROP CONSTRAINT ck_auditoria_accion;
ALTER TABLE auditoria
    ADD CONSTRAINT ck_auditoria_accion CHECK (accion IN ('LOGIN', 'LOGIN_FALLIDO', 'LOGOUT', 'ALTA', 'MODIFICACION',
                                                         'BAJA', 'BLOQUEO', 'DESBLOQUEO', 'PAGO', 'LAUDO', 'SORTEO',
                                                         'CIERRE_INSCRIPCION', 'CIERRE_VENTA', 'LIQUIDACION',
                                                         'CANCELACION', 'REINTEGRO', 'SANCION', 'CALIFICACION'));
