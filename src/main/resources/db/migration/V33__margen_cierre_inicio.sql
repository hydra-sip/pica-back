-- Margen mínimo entre el cierre de la inscripción y el inicio del torneo (CE4-5, PICA-306), en minutos. Con el cierre
-- a la misma hora que el inicio no queda lugar para programar los partidos y el check-in. Lo valida el back al guardar
-- las fechas; editarlo desde el admin es HU-702.
INSERT INTO parametro_plataforma (clave, valor, descripcion, modificado_por)
VALUES ('MARGEN_CIERRE_INICIO_MIN', 60,
        'Minutos que tienen que pasar como mínimo entre el cierre de la inscripción y el inicio del torneo',
        'SISTEMA');

ALTER TABLE parametro_plataforma
    ADD CONSTRAINT ck_parametro_plataforma_minutos
        CHECK (clave <> 'MARGEN_CIERRE_INICIO_MIN' OR (valor >= 0 AND valor = trunc(valor)));
