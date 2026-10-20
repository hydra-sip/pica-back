-- Catálogo fijo de permisos. El código es lo que viaja en el JWT y lo que chequea @PreAuthorize;
-- no se crean por API, solo por migración. El módulo agrupa los checkboxes en la pantalla de roles.
CREATE TABLE permiso (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    codigo VARCHAR(50) NOT NULL,
    modulo VARCHAR(30) NOT NULL,
    descripcion VARCHAR(255) NOT NULL,
    CONSTRAINT pk_permiso PRIMARY KEY (id),
    CONSTRAINT uq_permiso_codigo UNIQUE (codigo),
    CONSTRAINT ck_permiso_modulo CHECK (modulo IN ('USUARIOS', 'PERSONAS', 'ROLES'))
);

-- Qué permisos tiene cada rol. Sin baja lógica: reemplazar los permisos de un rol borra y vuelve a
-- insertar filas, y el historial de quién cambió qué queda en modificado_por/modificado_en del rol.
CREATE TABLE rol_permiso (
    rol_id BIGINT NOT NULL,
    permiso_id BIGINT NOT NULL,
    CONSTRAINT pk_rol_permiso PRIMARY KEY (rol_id, permiso_id),
    CONSTRAINT fk_rol_permiso_rol FOREIGN KEY (rol_id) REFERENCES rol (id),
    CONSTRAINT fk_rol_permiso_permiso FOREIGN KEY (permiso_id) REFERENCES permiso (id)
);

CREATE INDEX idx_rol_permiso_permiso_id
    ON rol_permiso (permiso_id);

INSERT INTO permiso (codigo, modulo, descripcion)
VALUES
    ('USUARIO_VER',      'USUARIOS', 'Ver el listado y el detalle de usuarios'),
    ('USUARIO_CREAR',    'USUARIOS', 'Dar de alta usuarios'),
    ('USUARIO_EDITAR',   'USUARIOS', 'Modificar usuarios, bloquearlos y resetear su contraseña'),
    ('USUARIO_ELIMINAR', 'USUARIOS', 'Dar de baja y reactivar usuarios'),
    ('PERSONA_VER',      'PERSONAS', 'Ver el listado y el detalle de personas'),
    ('PERSONA_CREAR',    'PERSONAS', 'Dar de alta personas'),
    ('PERSONA_EDITAR',   'PERSONAS', 'Modificar datos de personas'),
    ('PERSONA_ELIMINAR', 'PERSONAS', 'Dar de baja y reactivar personas'),
    ('ROL_VER',          'ROLES',    'Ver roles y el catálogo de permisos'),
    ('ROL_CREAR',        'ROLES',    'Crear roles'),
    ('ROL_EDITAR',       'ROLES',    'Modificar roles y sus permisos'),
    ('ROL_ELIMINAR',     'ROLES',    'Dar de baja y reactivar roles'),
    ('ROL_ASIGNAR',      'ROLES',    'Asignar y quitar roles a usuarios');

-- Super Usuario: todo.
INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre = 'SUPER_USUARIO';

-- Administrador: gestiona usuarios y personas, y puede ver y asignar roles, pero no tocar el
-- catálogo (crear, editar o eliminar roles queda para el Super Usuario).
INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM rol r, permiso p
WHERE r.nombre = 'ADMINISTRADOR'
  AND p.codigo NOT IN ('ROL_CREAR', 'ROL_EDITAR', 'ROL_ELIMINAR');
