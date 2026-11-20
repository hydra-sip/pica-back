-- Centro de notificaciones (HU-808, T808-1): cada aviso que le llega a un usuario por correo queda también como
-- notificación web. El enlace es la ruta del front (sin dominio); datos guarda lo propio de cada tipo para el front.
CREATE TABLE notificacion (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usuario_id BIGINT NOT NULL,
    tipo VARCHAR(30) NOT NULL,
    titulo VARCHAR(200) NOT NULL,
    cuerpo TEXT NOT NULL,
    enlace VARCHAR(255),
    datos JSONB,
    leida_en TIMESTAMPTZ,
    creada_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_notificacion PRIMARY KEY (id),
    CONSTRAINT fk_notificacion_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_notificacion_tipo CHECK (tipo IN ('RIVAL_ASIGNADO', 'CHECKIN_ABIERTO', 'CRUCE_INICIADO', 'RECLAMO_RESUELTO'))
);

-- la lista del usuario, de la más nueva a la más vieja
CREATE INDEX idx_notificacion_usuario
    ON notificacion (usuario_id, creada_en DESC, id DESC);

-- el contador de la campana
CREATE INDEX idx_notificacion_no_leidas
    ON notificacion (usuario_id)
    WHERE leida_en IS NULL;
