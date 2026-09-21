-- Tabla base de datos personales; el documento solo es único mientras la persona siga activa.
CREATE TABLE persona (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    nombres VARCHAR(100) NOT NULL,
    apellidos VARCHAR(100) NOT NULL,
    tipo_doc VARCHAR(20),
    nro_doc VARCHAR(30),
    fecha_nacimiento DATE,
    domicilio_postal VARCHAR(255),
    telefono VARCHAR(30),
    descripcion TEXT,
    estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVO',
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_persona PRIMARY KEY (id),
    CONSTRAINT ck_persona_estado CHECK (estado IN ('ACTIVO', 'INACTIVO'))
);

-- No se restringe tipo_doc porque la validación de tipos documentales pertenece a la aplicación.
-- Este índice parcial permite reutilizar un documento luego de la baja lógica de su persona anterior.
CREATE UNIQUE INDEX uq_persona_tipo_doc_nro_doc_activa
    ON persona (tipo_doc, nro_doc)
    WHERE nro_doc IS NOT NULL AND eliminado_en IS NULL;

-- Tabla de cuentas; los usuarios autenticados por Google pueden no tener password_hash.
CREATE TABLE usuario (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    google_sub VARCHAR(255),
    descripcion TEXT,
    estado VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE_VERIFICACION',
    email_verificado BOOLEAN NOT NULL DEFAULT FALSE,
    persona_id BIGINT NOT NULL,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_usuario PRIMARY KEY (id),
    CONSTRAINT fk_usuario_persona FOREIGN KEY (persona_id) REFERENCES persona (id),
    CONSTRAINT uq_usuario_google_sub UNIQUE (google_sub),
    CONSTRAINT uq_usuario_persona_id UNIQUE (persona_id),
    CONSTRAINT ck_usuario_estado CHECK (estado IN ('PENDIENTE_VERIFICACION', 'ACTIVO', 'BLOQUEADO'))
);

-- Los identificadores de cuenta no se reutilizan tras una baja lógica; la unicidad es total y sin distinguir mayúsculas.
CREATE UNIQUE INDEX uq_usuario_username_lower
    ON usuario (lower(username));

CREATE UNIQUE INDEX uq_usuario_email_lower
    ON usuario (lower(email));

-- Tabla de roles del sistema; es_sistema distingue roles protegidos de roles administrables.
CREATE TABLE rol (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    nombre VARCHAR(50) NOT NULL,
    nombre_amigable VARCHAR(100) NOT NULL,
    descripcion TEXT,
    estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVO',
    es_sistema BOOLEAN NOT NULL DEFAULT FALSE,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_rol PRIMARY KEY (id),
    CONSTRAINT uq_rol_nombre UNIQUE (nombre),
    CONSTRAINT ck_rol_estado CHECK (estado IN ('ACTIVO', 'INACTIVO'))
);

-- Tabla puente de asignaciones; la baja lógica conserva el par usuario-rol para permitir su reactivación.
CREATE TABLE usuario_rol (
    usuario_id BIGINT NOT NULL,
    rol_id BIGINT NOT NULL,
    asignado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    asignado_por VARCHAR(100),
    eliminado_en TIMESTAMPTZ,
    eliminado_por VARCHAR(100),
    CONSTRAINT pk_usuario_rol PRIMARY KEY (usuario_id, rol_id),
    CONSTRAINT fk_usuario_rol_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_usuario_rol_rol FOREIGN KEY (rol_id) REFERENCES rol (id)
);

-- La PK impide duplicar el par: al reasignar un rol revocado se reactiva la fila existente,
-- limpiando eliminado_en/eliminado_por y actualizando asignado_en/asignado_por.
CREATE INDEX idx_usuario_rol_rol_id
    ON usuario_rol (rol_id);
