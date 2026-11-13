-- El admin ve el panel financiero y la liquidación de cualquier torneo (HU-608, PICA-342), como RIFA_ADMINISTRAR
-- con las rifas (V31). Lo tienen ADMINISTRADOR y SUPER_USUARIO.
INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('TORNEO_ADMINISTRAR', 'TORNEOS', 'Ver el panel financiero y la liquidación de los torneos de todos');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR')
  AND p.codigo = 'TORNEO_ADMINISTRAR';
