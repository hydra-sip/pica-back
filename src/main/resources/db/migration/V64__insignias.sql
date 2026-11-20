-- HU-804 (PICA-425): catálogo de insignias e insignias otorgadas a cada usuario (con PK compuesta para no duplicar).

CREATE TABLE insignia (
    codigo VARCHAR(50) NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    descripcion VARCHAR(255) NOT NULL,
    icono VARCHAR(50) NOT NULL,
    CONSTRAINT pk_insignia PRIMARY KEY (codigo),
    CONSTRAINT ck_insignia_codigo CHECK (btrim(codigo) <> ''),
    CONSTRAINT ck_insignia_nombre CHECK (btrim(nombre) <> ''),
    CONSTRAINT ck_insignia_descripcion CHECK (btrim(descripcion) <> ''),
    CONSTRAINT ck_insignia_icono CHECK (btrim(icono) <> '')
);

CREATE TABLE usuario_insignia (
    usuario_id BIGINT NOT NULL,
    codigo VARCHAR(50) NOT NULL,
    otorgada_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_usuario_insignia PRIMARY KEY (usuario_id, codigo),
    CONSTRAINT fk_usuario_insignia_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_usuario_insignia_codigo FOREIGN KEY (codigo) REFERENCES insignia (codigo)
);

CREATE INDEX idx_usuario_insignia_usuario
    ON usuario_insignia (usuario_id, otorgada_en DESC);

INSERT INTO insignia (codigo, nombre, descripcion, icono)
VALUES ('PRIMER_TORNEO_JUGADO', 'Primer torneo jugado',
        'Completaste tu primer partido oficial en un torneo de la plataforma', 'trofeo-inicio'),
       ('PRIMER_TORNEO_GANADO', 'Primer campeón',
        'Ganaste tu primer torneo en la plataforma', 'corona'),
       ('PRIMER_PODIO', 'Primer podio',
        'Alcanzaste el podio en un torneo finalizado', 'medalla'),
       ('PRIMERA_RIFA_GANADA', 'Suerte de campeón',
        'Ganaste un premio en una rifa sorteada', 'ticket-estrella'),
       ('DIEZ_CHECKINS_SEGUIDOS', 'Asistencia perfecta',
        'Completaste 10 check-ins seguidos sin ausencias', 'escudo-check');
