-- HU-701 (PICA-412): soporte entra al admin para reclamos, cancelaciones, bloqueos y auditoría, sin la configuración
-- económica. USUARIO_BLOQUEAR y USUARIO_VER ya los tiene por V58; acá suma AUDITORIA_VER para el libro.
-- USUARIO_EDITAR ya no incluye bloquear: su descripción lo dice.
UPDATE permiso
SET descripcion = 'Modificar usuarios y resetear su contraseña'
WHERE codigo = 'USUARIO_EDITAR';

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre = 'SOPORTE'
  AND p.codigo = 'AUDITORIA_VER';
