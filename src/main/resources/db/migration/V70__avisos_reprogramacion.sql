-- Avisos de la reprogramación de un torneo (CR-01-4, PICA-393). recordatorio_enviado_en marca que ya salió el
-- recordatorio del día antes de que venza el plazo para rechazar (sale una sola vez); rechazos cuenta los que se
-- bajaron en esta reprogramación, para el aviso al organizador cuando vence el plazo.
ALTER TABLE reprogramacion
    ADD COLUMN recordatorio_enviado_en TIMESTAMPTZ,
    ADD COLUMN rechazos INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_reprogramacion_rechazos CHECK (rechazos >= 0);

-- Los avisos nuevos del centro de notificaciones: a los inscriptos (la nueva fecha y el recordatorio) y al
-- organizador (quedó por debajo del mínimo y cómo terminó el plazo).
ALTER TABLE notificacion DROP CONSTRAINT ck_notificacion_tipo;
ALTER TABLE notificacion
    ADD CONSTRAINT ck_notificacion_tipo CHECK (tipo IN ('RIVAL_ASIGNADO', 'CHECKIN_ABIERTO', 'CRUCE_INICIADO',
                                                        'RECLAMO_RESUELTO', 'TORNEO_REPROGRAMADO',
                                                        'REPROGRAMACION_POR_VENCER', 'INSCRIPTOS_BAJO_EL_MINIMO',
                                                        'REPROGRAMACION_RESUELTA'));
