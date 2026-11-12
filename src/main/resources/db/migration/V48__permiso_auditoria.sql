-- El admin consulta el libro de auditoría (HU-704, PICA-345): permiso AUDITORIA_VER en un módulo nuevo, para
-- ADMINISTRADOR y SUPER_USUARIO. El filtro por torneo va por entidad y entidad_id (idx_auditoria_entidad de V9);
-- el listado ordena y filtra por fecha, así que creado_en lleva su índice.
ALTER TABLE permiso DROP CONSTRAINT ck_permiso_modulo;
ALTER TABLE permiso
    ADD CONSTRAINT ck_permiso_modulo CHECK (
        modulo IN ('USUARIOS', 'PERSONAS', 'ROLES', 'RIFAS', 'INSCRIPCIONES', 'TORNEOS', 'PARAMETROS', 'AUDITORIA'));

INSERT INTO permiso (codigo, modulo, descripcion)
VALUES ('AUDITORIA_VER', 'AUDITORIA', 'Consultar el libro de auditoría');

INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre IN ('SUPER_USUARIO', 'ADMINISTRADOR')
  AND p.codigo = 'AUDITORIA_VER';

CREATE INDEX idx_auditoria_creado_en
    ON auditoria (creado_en DESC, id DESC);
