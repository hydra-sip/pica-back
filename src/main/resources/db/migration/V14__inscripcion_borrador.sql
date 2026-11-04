-- Un borrador todavía no tiene el reglamento aceptado (PICA-208): la fecha se guarda al confirmar. En
-- cualquier otro estado la base la exige.
ALTER TABLE inscripcion ALTER COLUMN reglamento_aceptado_en DROP NOT NULL;
ALTER TABLE inscripcion
    ADD CONSTRAINT ck_inscripcion_reglamento CHECK (estado = 'BORRADOR' OR reglamento_aceptado_en IS NOT NULL);

-- Una sola inscripción activa (pendiente de pago o confirmada) por usuario y torneo. Los borradores y las
-- canceladas no cuentan: el UNIQUE de V9 impedía volver a inscribirse después de cancelar.
ALTER TABLE inscripcion DROP CONSTRAINT uq_inscripcion_torneo_usuario;
CREATE UNIQUE INDEX uq_inscripcion_activa_torneo_usuario
    ON inscripcion (torneo_id, usuario_id)
    WHERE estado IN ('PENDIENTE_PAGO', 'CONFIRMADA');

-- Quien se inscribe necesita INSCRIPCION_GESTIONAR, en un módulo nuevo (misma convención que RIFA_GESTIONAR
-- en V11): lo tiene el rol PARTICIPANTE. ADMINISTRADOR y SUPER_USUARIO también, porque nadie asigna un rol con
-- permisos que no tiene (UsuarioRolService) y sin él no podrían hacer participante a nadie.
ALTER TABLE permiso DROP CONSTRAINT ck_permiso_modulo;
ALTER TABLE permiso
    ADD CONSTRAINT ck_permiso_modulo CHECK (modulo IN ('USUARIOS', 'PERSONAS', 'ROLES', 'RIFAS', 'INSCRIPCIONES'));

INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('INSCRIPCION_GESTIONAR', 'INSCRIPCIONES', 'Crear, ver y cancelar sus propias inscripciones');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'PARTICIPANTE')
  AND p.codigo = 'INSCRIPCION_GESTIONAR';
