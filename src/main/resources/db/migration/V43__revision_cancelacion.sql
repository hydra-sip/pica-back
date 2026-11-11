-- HU-605 (PICA-348): soporte revisa las cancelaciones con gastos. Es el primer permiso del rol SOPORTE; lo tienen también
-- el admin y el super usuario. Con él se ve la cola, se bajan los comprobantes y se aprueba o rechaza.
INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('CANCELACION_REVISAR', 'TORNEOS', 'Revisar las cancelaciones de torneos con gastos: aprobarlas o rechazarlas');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR', 'SOPORTE')
  AND p.codigo = 'CANCELACION_REVISAR';
