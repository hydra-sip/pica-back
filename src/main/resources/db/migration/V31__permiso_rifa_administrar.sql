-- Administrar rifas ajenas (HU-404, PICA-224): por ahora, ver el ticket de cualquier comprador. Lo tienen
-- ADMINISTRADOR y SUPER_USUARIO; el resto de la administración de rifas (listados en el backoffice) va en
-- otra tarea con este mismo permiso.
INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('RIFA_ADMINISTRAR', 'RIFAS', 'Ver y administrar las rifas y los tickets de todos');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR')
  AND p.codigo = 'RIFA_ADMINISTRAR';
