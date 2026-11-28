-- HU-703 (PICA-414): bloquear y desbloquear tiene permiso propio, separado de USUARIO_EDITAR, así soporte puede
-- sancionar sin poder editar cuentas ni resetear contraseñas. Lo tienen SOPORTE, ADMINISTRADOR y SUPER_USUARIO.
-- SOPORTE recibe además USUARIO_VER, para llegar a la lista de usuarios desde la que bloquea.
INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('USUARIO_BLOQUEAR', 'USUARIOS', 'Bloquear y desbloquear usuarios');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'SOPORTE')
  AND p.codigo = 'USUARIO_BLOQUEAR';

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre = 'SOPORTE'
  AND p.codigo = 'USUARIO_VER';
