-- HU-705 (PICA-416): detección de conductas fraudulentas y alertas para soporte.
-- Reglas configurables en parametro_plataforma, tabla alerta_fraude con unicidad sobre alertas pendientes
-- y permiso ALERTA_FRAUDE_REVISAR en el módulo AUDITORIA para SOPORTE, ADMINISTRADOR y SUPER_USUARIO.

INSERT INTO parametro_plataforma (clave, valor, descripcion, modificado_por)
VALUES ('FRAUDE_RIFA_NUMEROS_MAX', 10,
        'Cantidad de números comprados en una rifa dentro de la ventana tras la cual se alerta por compra masiva',
        'SISTEMA'),
       ('FRAUDE_RIFA_VENTANA_MIN', 30,
        'Ventana en minutos para evaluar la compra masiva de números en una misma rifa',
        'SISTEMA'),
       ('FRAUDE_CARRUSEL_OPERACIONES_MIN', 4,
        'Cantidad mínima de cargas y retiros combinados en la ventana para alertar por carrusel de saldo',
        'SISTEMA'),
       ('FRAUDE_CARRUSEL_VENTANA_MIN', 60,
        'Ventana en minutos para evaluar cargas y retiros seguidos en la billetera',
        'SISTEMA'),
       ('FRAUDE_CANCELACIONES_MIN', 2,
        'Cantidad mínima de torneos cancelados por un organizador para alertar por cancelaciones reiteradas',
        'SISTEMA'),
       ('FRAUDE_OBJECIONES_MIN', 3,
        'Cantidad mínima de resultados objetados por un mismo usuario para alertar por objeciones repetidas',
        'SISTEMA'),
       ('FRAUDE_CUENTAS_USUARIOS_MIN', 2,
        'Cantidad mínima de usuarios distintos que comparten una misma cuenta bancaria para generar alerta',
        'SISTEMA');

ALTER TABLE parametro_plataforma
    ADD CONSTRAINT ck_parametro_plataforma_fraude
        CHECK (clave NOT LIKE 'FRAUDE\_%' OR (valor > 0 AND valor = trunc(valor)));

INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('ALERTA_FRAUDE_REVISAR', 'AUDITORIA', 'Consultar y revisar las alertas de fraude');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'SOPORTE')
  AND p.codigo = 'ALERTA_FRAUDE_REVISAR';

CREATE TABLE alerta_fraude (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    regla VARCHAR(40) NOT NULL,
    torneo_id BIGINT,
    rifa_id BIGINT,
    usuario_id BIGINT NOT NULL,
    motivo TEXT NOT NULL,
    datos JSONB NOT NULL DEFAULT '{}'::jsonb,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    revisada_por BIGINT,
    revisada_en TIMESTAMPTZ,
    nota TEXT,
    creada_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_alerta_fraude PRIMARY KEY (id),
    CONSTRAINT fk_alerta_fraude_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_alerta_fraude_rifa FOREIGN KEY (rifa_id) REFERENCES rifa (id),
    CONSTRAINT fk_alerta_fraude_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_alerta_fraude_revisada_por FOREIGN KEY (revisada_por) REFERENCES usuario (id),
    CONSTRAINT ck_alerta_fraude_regla CHECK (regla IN (
        'COMPRA_MASIVA_RIFA', 'CARRUSEL_CARGA_RETIRO', 'CANCELACIONES_REITERADAS',
        'OBJECIONES_ABUSIVAS', 'CUENTAS_COMPARTIDAS')),
    CONSTRAINT ck_alerta_fraude_motivo CHECK (btrim(motivo) <> ''),
    CONSTRAINT ck_alerta_fraude_estado CHECK (estado IN ('PENDIENTE', 'REVISADA')),
    CONSTRAINT ck_alerta_fraude_revision CHECK (
        CASE estado
            WHEN 'PENDIENTE' THEN revisada_por IS NULL AND revisada_en IS NULL AND nota IS NULL
            WHEN 'REVISADA' THEN revisada_por IS NOT NULL AND revisada_en IS NOT NULL AND nota IS NOT NULL AND btrim(nota) <> ''
        END)
);

-- No duplica una alerta abierta (PENDIENTE) para el mismo caso.
CREATE UNIQUE INDEX uq_alerta_fraude_pendiente
    ON alerta_fraude (regla, usuario_id, COALESCE(torneo_id, 0), COALESCE(rifa_id, 0))
    WHERE estado = 'PENDIENTE';

CREATE INDEX idx_alerta_fraude_estado_creada ON alerta_fraude (estado, creada_en DESC, id DESC);
CREATE INDEX idx_alerta_fraude_usuario ON alerta_fraude (usuario_id);
CREATE INDEX idx_alerta_fraude_torneo ON alerta_fraude (torneo_id);
CREATE INDEX idx_alerta_fraude_rifa ON alerta_fraude (rifa_id);
