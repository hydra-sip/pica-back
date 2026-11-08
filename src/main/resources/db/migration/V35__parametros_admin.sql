-- HU-702 (PICA-282): el admin edita los parámetros de la plataforma. Suma el tope de los gastos administrativos,
-- el máximo de la ventana de check-in y los topes del retiro (HU-607), y el permiso para editarlos.
-- Las reglas entre parámetros (gastos <= tope, retiro mínimo <= máximo) las valida el servicio.

INSERT INTO parametro_plataforma (clave, valor, descripcion, modificado_por)
VALUES ('GASTOS_ADMINISTRATIVOS_TOPE', 20,
        'Tope del % de gastos administrativos: el % vigente no puede superarlo',
        'SISTEMA'),
       ('CHECKIN_DURACION_MAX_MIN', 60,
        'Minutos que puede durar como máximo la ventana de check-in que configura el organizador',
        'SISTEMA'),
       ('RETIRO_MINIMO', 1000,
        'Monto mínimo en pesos de un retiro de la billetera',
        'SISTEMA'),
       ('RETIRO_MAXIMO', 500000,
        'Monto máximo en pesos de un retiro de la billetera',
        'SISTEMA');

ALTER TABLE parametro_plataforma
    ADD CONSTRAINT ck_parametro_plataforma_tope
        CHECK (clave <> 'GASTOS_ADMINISTRATIVOS_TOPE' OR valor BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_parametro_plataforma_checkin
        CHECK (clave <> 'CHECKIN_DURACION_MAX_MIN' OR (valor > 0 AND valor = trunc(valor))),
    ADD CONSTRAINT ck_parametro_plataforma_retiro
        CHECK (clave NOT IN ('RETIRO_MINIMO', 'RETIRO_MAXIMO') OR valor > 0);

ALTER TABLE permiso DROP CONSTRAINT ck_permiso_modulo;
ALTER TABLE permiso
    ADD CONSTRAINT ck_permiso_modulo CHECK (
        modulo IN ('USUARIOS', 'PERSONAS', 'ROLES', 'RIFAS', 'INSCRIPCIONES', 'TORNEOS', 'PARAMETROS'));

INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('PARAMETRO_ADMINISTRAR', 'PARAMETROS', 'Ver y modificar los parámetros de la plataforma');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR')
  AND p.codigo = 'PARAMETRO_ADMINISTRAR';
