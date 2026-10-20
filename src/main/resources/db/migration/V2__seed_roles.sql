-- Roles iniciales del sistema; los identificadores nominales quedan reservados por nombre.
INSERT INTO rol (nombre, nombre_amigable, es_sistema)
VALUES
    ('SUPER_USUARIO', 'Super Usuario', TRUE),
    ('ADMINISTRADOR', 'Administrador', FALSE),
    ('ORGANIZADOR', 'Organizador', FALSE),
    ('ARBITRO', 'Árbitro', FALSE),
    ('SOPORTE', 'Soporte', FALSE),
    ('PARTICIPANTE', 'Participante', FALSE);
