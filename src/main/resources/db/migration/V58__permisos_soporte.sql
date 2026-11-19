-- HU-701 (PICA-412): soporte entra al admin para reclamos, cancelaciones, bloqueos y auditoría, sin la configuración
-- económica. Bloquear deja de pedir USUARIO_EDITAR (que también edita y resetea la contraseña) y pasa a USUARIO_BLOQUEAR.
-- Soporte suma USUARIO_VER para encontrar al usuario y AUDITORIA_VER para el libro.
INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('USUARIO_BLOQUEAR', 'USUARIOS', 'Bloquear usuarios y levantarles el bloqueo');

UPDATE permiso
SET descripcion = 'Modificar usuarios y resetear su contraseña'
WHERE codigo = 'USUARIO_EDITAR';

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'SOPORTE')
  AND p.codigo = 'USUARIO_BLOQUEAR';

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre = 'SOPORTE'
  AND p.codigo IN ('USUARIO_VER', 'AUDITORIA_VER');
