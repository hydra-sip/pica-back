-- Solo un organizador emite rifas (PICA-226): permiso RIFA_GESTIONAR en un módulo nuevo, para el rol
-- ORGANIZADOR y para SUPER_USUARIO, que tiene todos. Con él cada uno crea, edita y lista las suyas;
-- que sean las propias lo sigue controlando el servicio.
-- ADMINISTRADOR también lo tiene: nadie asigna un rol con permisos que no tiene (UsuarioRolService),
-- así que sin él un administrador no podría hacer organizador a nadie.
ALTER TABLE permiso DROP CONSTRAINT ck_permiso_modulo;
ALTER TABLE permiso
    ADD CONSTRAINT ck_permiso_modulo CHECK (modulo IN ('USUARIOS', 'PERSONAS', 'ROLES', 'RIFAS'));

INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('RIFA_GESTIONAR', 'RIFAS', 'Crear, editar y ver sus propias rifas');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'ORGANIZADOR')
  AND p.codigo = 'RIFA_GESTIONAR';
