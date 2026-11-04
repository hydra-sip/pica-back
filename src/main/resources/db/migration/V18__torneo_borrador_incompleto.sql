-- Un torneo en BORRADOR se guarda incompleto (HU-201, PICA-199): el asistente lo crea en el paso 1 y lo completa
-- en los pasos 2 y 3. Es V18 porque V17 es la de publicar la rifa (HU-402). Fuera de BORRADOR siguen siendo
-- obligatorias las mismas columnas que antes, como hizo V10 con la rifa. Los CHECK de cupos, fechas, porcentajes y
-- tamaño de equipo siguen valiendo: con NULL no fallan.
ALTER TABLE torneo
    ALTER COLUMN reglamento_url DROP NOT NULL,
    ALTER COLUMN modalidad DROP NOT NULL,
    ALTER COLUMN tipo_participante DROP NOT NULL,
    ALTER COLUMN cupo_maximo DROP NOT NULL,
    ALTER COLUMN cupo_minimo DROP NOT NULL,
    ALTER COLUMN valor_inscripcion DROP NOT NULL,
    ALTER COLUMN pct_pozo_competidores DROP NOT NULL,
    ALTER COLUMN pct_premio_rifa DROP NOT NULL,
    ALTER COLUMN cierre_inscripcion_en DROP NOT NULL,
    ALTER COLUMN fecha_inicio DROP NOT NULL;

-- El reglamento se escribe en el paso 1; el PDF (reglamento_url) pasa a ser opcional y alcanza con uno de los dos.
ALTER TABLE torneo ADD COLUMN reglamento_texto VARCHAR(2000);

ALTER TABLE torneo
    ADD CONSTRAINT ck_torneo_completo_fuera_de_borrador CHECK (
        estado = 'BORRADOR'
        OR (modalidad IS NOT NULL AND tipo_participante IS NOT NULL
            AND cupo_maximo IS NOT NULL AND cupo_minimo IS NOT NULL
            AND valor_inscripcion IS NOT NULL AND pct_pozo_competidores IS NOT NULL AND pct_premio_rifa IS NOT NULL
            AND cierre_inscripcion_en IS NOT NULL AND fecha_inicio IS NOT NULL
            AND (reglamento_texto IS NOT NULL OR reglamento_url IS NOT NULL)));

-- V16 exige el máximo de nómina en todo torneo por equipos; en BORRADOR puede faltar. Mismo nombre, para que
-- los errores sigan diciendo ck_torneo_nomina_maxima.
ALTER TABLE torneo DROP CONSTRAINT ck_torneo_nomina_maxima;
ALTER TABLE torneo
    ADD CONSTRAINT ck_torneo_nomina_maxima CHECK (
        CASE tipo_participante
            WHEN 'EQUIPO' THEN (nomina_maxima IS NOT NULL OR estado = 'BORRADOR') AND nomina_maxima >= tamanio_equipo
            ELSE nomina_maxima IS NULL END);

-- Solo un organizador arma torneos: permiso TORNEO_GESTIONAR en un módulo nuevo, misma convención que
-- RIFA_GESTIONAR en V11 (ADMINISTRADOR y SUPER_USUARIO también, para poder hacer organizador a alguien).
-- Que el torneo sea propio lo controla el servicio.
ALTER TABLE permiso DROP CONSTRAINT ck_permiso_modulo;
ALTER TABLE permiso
    ADD CONSTRAINT ck_permiso_modulo CHECK (
        modulo IN ('USUARIOS', 'PERSONAS', 'ROLES', 'RIFAS', 'INSCRIPCIONES', 'TORNEOS'));

INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('TORNEO_GESTIONAR', 'TORNEOS', 'Crear, editar y ver sus propios torneos');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'ORGANIZADOR')
  AND p.codigo = 'TORNEO_GESTIONAR';
