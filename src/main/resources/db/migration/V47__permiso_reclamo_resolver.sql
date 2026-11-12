-- HU-706 (PICA-339): permiso para ver los reclamos abiertos en el admin y dictar su resolución definitiva.
-- Lo tienen SOPORTE, ADMINISTRADOR y SUPER_USUARIO.
INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('RECLAMO_RESOLVER', 'RIFAS', 'Ver los reclamos de rifas y registrar su resolución definitiva');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'SOPORTE')
  AND p.codigo = 'RECLAMO_RESOLVER';

