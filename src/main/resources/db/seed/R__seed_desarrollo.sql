-- Seed de desarrollo (B1-3). Solo corre con el perfil seed (application-seed.yml suma db/seed a Flyway);
-- en el CI, staging y Railway no existe. Es repetible: Flyway lo vuelve a correr cada vez que cambia,
-- así que cada insert se apoya en una clave natural y no duplica lo que ya está.
--
-- Usuarios (todos activos y verificados, contraseña PicaDev2026):
--   dev_organizador (ORGANIZADOR), dev_arbitro (ARBITRO), dev_jugador1 a dev_jugador6 (PARTICIPANTE).
-- Datos:
--   - Equipos "Los Halcones" (capitán dev_jugador1, con 2 y 3) y "Las Panteras" (capitán dev_jugador4, con 5 y 6).
--   - "Copa abierta" (dev-copa-abierta): por equipos, inscripción abierta, con Los Halcones inscriptos y
--     pagados y su rifa en venta (números 1 a 100; 7, 13 y 42 vendidos a dev_jugador5).
--   - "Torneo cerrado" (dev-torneo-cerrado): individual, inscripción cerrada con dev_jugador1 a 4,
--     el árbitro designado, la Mesa 1 y el bracket: dos semifinales con sus lados y la final vacía.
--   - Cada pago es un movimiento CONFIRMADO en la billetera del que paga, como lo deja el cobro simulado.
-- Las fechas son relativas al momento en que corre.

-- ---------------------------------------------------------------------------------------------------
-- Usuarios
-- ---------------------------------------------------------------------------------------------------

INSERT INTO persona (nombres, apellidos, tipo_doc, nro_doc, fecha_nacimiento, creado_por, modificado_por)
VALUES ('Olga', 'Organizadora', 'DNI', '90000001', DATE '1985-03-10', 'SEED', 'SEED'),
       ('Arturo', 'Árbitro', 'DNI', '90000002', DATE '1980-07-22', 'SEED', 'SEED'),
       ('Juan', 'Uno', 'DNI', '90000011', DATE '1998-01-15', 'SEED', 'SEED'),
       ('Julia', 'Dos', 'DNI', '90000012', DATE '1999-02-20', 'SEED', 'SEED'),
       ('Javier', 'Tres', 'DNI', '90000013', DATE '2000-03-25', 'SEED', 'SEED'),
       ('Juana', 'Cuatro', 'DNI', '90000014', DATE '2001-04-30', 'SEED', 'SEED'),
       ('Jorge', 'Cinco', 'DNI', '90000015', DATE '1997-05-05', 'SEED', 'SEED'),
       ('Josefina', 'Seis', 'DNI', '90000016', DATE '1996-06-10', 'SEED', 'SEED')
ON CONFLICT DO NOTHING;

INSERT INTO usuario (username, email, password_hash, estado, email_verificado, persona_id, creado_por, modificado_por)
SELECT v.username, v.username || '@pica.local', '$2a$12$rSo6OKEPd3Qx4rRRR3rP2etYtouYDmpAAWeXN8a9pPtcOAhz5qd2.', 'ACTIVO', TRUE, p.id, 'SEED', 'SEED'
FROM (VALUES ('dev_organizador', '90000001'),
             ('dev_arbitro', '90000002'),
             ('dev_jugador1', '90000011'),
             ('dev_jugador2', '90000012'),
             ('dev_jugador3', '90000013'),
             ('dev_jugador4', '90000014'),
             ('dev_jugador5', '90000015'),
             ('dev_jugador6', '90000016')) AS v (username, nro_doc)
JOIN persona p ON p.tipo_doc = 'DNI' AND p.nro_doc = v.nro_doc
ON CONFLICT DO NOTHING;

INSERT INTO usuario_rol (usuario_id, rol_id, asignado_por)
SELECT u.id, r.id, 'SEED'
FROM (VALUES ('dev_organizador', 'ORGANIZADOR'),
             ('dev_arbitro', 'ARBITRO'),
             ('dev_jugador1', 'PARTICIPANTE'),
             ('dev_jugador2', 'PARTICIPANTE'),
             ('dev_jugador3', 'PARTICIPANTE'),
             ('dev_jugador4', 'PARTICIPANTE'),
             ('dev_jugador5', 'PARTICIPANTE'),
             ('dev_jugador6', 'PARTICIPANTE')) AS v (username, rol)
JOIN usuario u ON u.username = v.username
JOIN rol r ON r.nombre = v.rol
ON CONFLICT DO NOTHING;

-- Una billetera por jugador: el cobro simulado registra ahí los pagos.
INSERT INTO billetera (usuario_id, creado_por, modificado_por)
SELECT u.id, 'SEED', 'SEED'
FROM usuario u
WHERE u.username LIKE 'dev\_jugador%'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------------
-- Equipos (el capitán se chequea al final de la transacción, así que va después del equipo)
-- ---------------------------------------------------------------------------------------------------

INSERT INTO equipo (capitan_id, nombre, creado_por, modificado_por)
SELECT u.id, v.nombre, 'SEED', 'SEED'
FROM (VALUES ('Los Halcones', 'dev_jugador1'),
             ('Las Panteras', 'dev_jugador4')) AS v (nombre, capitan)
JOIN usuario u ON u.username = v.capitan
WHERE NOT EXISTS (SELECT 1 FROM equipo e WHERE e.nombre = v.nombre AND e.capitan_id = u.id);

INSERT INTO usuario_equipo (usuario_id, equipo_id, creado_por, modificado_por)
SELECT u.id, e.id, 'SEED', 'SEED'
FROM (VALUES ('Los Halcones', 'dev_jugador1'),
             ('Los Halcones', 'dev_jugador2'),
             ('Los Halcones', 'dev_jugador3'),
             ('Las Panteras', 'dev_jugador4'),
             ('Las Panteras', 'dev_jugador5'),
             ('Las Panteras', 'dev_jugador6')) AS v (equipo, username)
JOIN usuario u ON u.username = v.username
JOIN equipo e ON e.nombre = v.equipo AND e.creado_por = 'SEED'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------------
-- Torneos
-- ---------------------------------------------------------------------------------------------------

INSERT INTO torneo (organizador_id, nombre, descripcion, reglamento_url, reglamento_hash, disciplina, formato, modalidad,
                    tipo_participante, sede, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima, valor_inscripcion,
                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado, slug,
                    esquema_hash, publicado_en, lista_hash, lista_sellada_en, semilla_bracket,
                    checkin_min_antes, checkin_duracion_min, creado_por, modificado_por)
SELECT u.id, v.nombre, v.descripcion, 'https://pica.local/reglamentos/' || v.slug || '.pdf',
       encode(sha256(convert_to('reglamento ' || v.slug, 'UTF8')), 'hex'), v.disciplina, 'ELIMINACION_DIRECTA',
       'PRESENCIAL',
       v.tipo, 'Club UNLu', 8, v.cupo_minimo, v.tamanio, v.nomina, v.valor, 70, 20,
       now() + v.cierre, now() + v.inicio, v.estado, v.slug,
       encode(sha256(convert_to('esquema ' || v.slug, 'UTF8')), 'hex'), now() - interval '10 days',
       v.lista_hash, v.lista_sellada_en, v.semilla, 30, 20, 'SEED', 'SEED'
FROM (VALUES ('dev-copa-abierta', 'Copa abierta', 'Torneo de fútbol 5 por equipos con la inscripción abierta',
              'Fútbol 5', 'EQUIPO', 2, 3, 5, 5000.00, interval '7 days', interval '10 days', 'INSCRIPCION_ABIERTA',
              NULL::char(64), NULL::timestamptz, NULL),
             ('dev-torneo-cerrado', 'Torneo cerrado', 'Torneo individual de ajedrez con el bracket generado',
              'Ajedrez', 'INDIVIDUAL', 4, NULL, NULL, 2000.00, interval '-1 day', interval '2 days', 'INSCRIPCION_CERRADA',
              encode(sha256(convert_to('lista dev-torneo-cerrado', 'UTF8')), 'hex'), now() - interval '1 day',
              'semilla-dev-torneo-cerrado'))
     AS v (slug, nombre, descripcion, disciplina, tipo, cupo_minimo, tamanio, nomina, valor, cierre, inicio, estado,
           lista_hash, lista_sellada_en, semilla)
JOIN usuario u ON u.username = 'dev_organizador'
ON CONFLICT DO NOTHING;

INSERT INTO premio (torneo_id, puesto, pct)
SELECT t.id, v.puesto, v.pct
FROM (VALUES (1, 60.00), (2, 30.00), (3, 10.00)) AS v (puesto, pct)
CROSS JOIN torneo t
WHERE t.slug IN ('dev-copa-abierta', 'dev-torneo-cerrado')
ON CONFLICT DO NOTHING;

INSERT INTO cancha (nombre, sede, creado_por, modificado_por)
SELECT 'Mesa 1', 'Club UNLu', 'SEED', 'SEED'
WHERE NOT EXISTS (SELECT 1 FROM cancha WHERE nombre = 'Mesa 1' AND sede = 'Club UNLu');

INSERT INTO torneo_cancha (torneo_id, cancha_id)
SELECT t.id, c.id
FROM torneo t, cancha c
WHERE t.slug = 'dev-torneo-cerrado' AND c.nombre = 'Mesa 1' AND c.sede = 'Club UNLu'
ON CONFLICT DO NOTHING;

INSERT INTO torneo_arbitro (torneo_id, usuario_id)
SELECT t.id, u.id
FROM torneo t, usuario u
WHERE t.slug IN ('dev-copa-abierta', 'dev-torneo-cerrado') AND u.username = 'dev_arbitro'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------------
-- Inscripciones pagadas
-- ---------------------------------------------------------------------------------------------------

INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en, creado_por, modificado_por)
SELECT t.id, e.id, 'CONFIRMADA', now() - interval '3 days', 'SEED', 'SEED'
FROM torneo t, equipo e
WHERE t.slug = 'dev-copa-abierta' AND e.nombre = 'Los Halcones' AND e.creado_por = 'SEED'
ON CONFLICT DO NOTHING;

INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en, creado_por, modificado_por)
SELECT t.id, u.id, 'CONFIRMADA', now() - interval '5 days', 'SEED', 'SEED'
FROM torneo t, usuario u
WHERE t.slug = 'dev-torneo-cerrado'
  AND u.username IN ('dev_jugador1', 'dev_jugador2', 'dev_jugador3', 'dev_jugador4')
ON CONFLICT DO NOTHING;

-- El pago de cada inscripción sale de la billetera del jugador (del capitán, si es un equipo).
-- referencia_externa identifica el movimiento del seed para no repetirlo.
INSERT INTO movimiento (billetera_id, torneo_id, inscripcion_id, tipo, monto, saldo_resultante, estado,
                        referencia_externa, descripcion, creado_por, modificado_por)
SELECT b.id, t.id, i.id, 'INSCRIPCION', t.valor_inscripcion, 0, 'CONFIRMADO',
       'seed-inscripcion-' || i.id, 'Inscripción a ' || t.nombre, 'SEED', 'SEED'
FROM inscripcion i
JOIN torneo t ON t.id = i.torneo_id
LEFT JOIN equipo e ON e.id = i.equipo_id
JOIN billetera b ON b.usuario_id = coalesce(i.usuario_id, e.capitan_id)
WHERE i.creado_por = 'SEED'
  AND NOT EXISTS (SELECT 1 FROM movimiento m WHERE m.referencia_externa = 'seed-inscripcion-' || i.id);

-- ---------------------------------------------------------------------------------------------------
-- Bracket del torneo cerrado: semifinales con sus lados y la final vacía
-- ---------------------------------------------------------------------------------------------------

INSERT INTO partido (torneo_id, arbitro_id, cancha_id, ronda, posicion, fecha_programada, creado_por, modificado_por)
SELECT t.id, a.id, c.id, v.ronda, v.posicion, now() + v.cuando, 'SEED', 'SEED'
FROM (VALUES (1, 1, interval '2 days'),
             (1, 2, interval '2 days 2 hours'),
             (2, 1, interval '3 days')) AS v (ronda, posicion, cuando)
CROSS JOIN torneo t
JOIN usuario a ON a.username = 'dev_arbitro'
JOIN cancha c ON c.nombre = 'Mesa 1' AND c.sede = 'Club UNLu'
WHERE t.slug = 'dev-torneo-cerrado'
ON CONFLICT DO NOTHING;

INSERT INTO juega (partido_id, inscripcion_id, lado, creado_por, modificado_por)
SELECT p.id, i.id, v.lado, 'SEED', 'SEED'
FROM (VALUES (1, 'dev_jugador1', 'LOCAL'),
             (1, 'dev_jugador4', 'VISITANTE'),
             (2, 'dev_jugador2', 'LOCAL'),
             (2, 'dev_jugador3', 'VISITANTE')) AS v (posicion, username, lado)
JOIN torneo t ON t.slug = 'dev-torneo-cerrado'
JOIN partido p ON p.torneo_id = t.id AND p.ronda = 1 AND p.posicion = v.posicion
JOIN usuario u ON u.username = v.username
JOIN inscripcion i ON i.torneo_id = t.id AND i.usuario_id = u.id
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------------
-- Rifa en venta de la Copa abierta
-- ---------------------------------------------------------------------------------------------------

INSERT INTO rifa (organizador_id, torneo_id, titulo, descripcion, numero_desde, numero_hasta, precio_numero,
                  venta_desde, venta_hasta, terminos_aceptados_en, loteria_declarada_en, estado,
                  creado_por, modificado_por)
SELECT u.id, t.id, 'Rifa de la Copa abierta', 'El premio se sortea con la lotería nacional', 1, 100, 1000.00,
       now() - interval '1 day', now() + interval '7 days', now() - interval '1 day', now() - interval '1 day',
       'EN_VENTA', 'SEED', 'SEED'
FROM torneo t
JOIN usuario u ON u.username = 'dev_organizador'
WHERE t.slug = 'dev-copa-abierta'
ON CONFLICT DO NOTHING;

INSERT INTO premio_rifa (rifa_id, puesto, pct, descripcion)
SELECT r.id, 1, 100.00, 'Camiseta oficial del torneo'
FROM rifa r
JOIN torneo t ON t.id = r.torneo_id
WHERE t.slug = 'dev-copa-abierta'
ON CONFLICT DO NOTHING;

-- Una compra de tres números: el movimiento de pago, el ticket y los números que pasan a VENDIDO.
INSERT INTO movimiento (billetera_id, tipo, monto, saldo_resultante, estado, referencia_externa, descripcion,
                        creado_por, modificado_por)
SELECT b.id, 'COMPRA_NUMERO', 3150.00, 0, 'CONFIRMADO', 'seed-ticket-0001', 'Compra de 3 números de rifa',
       'SEED', 'SEED'
FROM billetera b
JOIN usuario u ON u.id = b.usuario_id
WHERE u.username = 'dev_jugador5'
  AND NOT EXISTS (SELECT 1 FROM movimiento m WHERE m.referencia_externa = 'seed-ticket-0001');

INSERT INTO ticket (codigo, rifa_id, comprador_id, movimiento_id, monto, comision, clave_idempotencia, creado_por)
SELECT 'SEED-0001', r.id, u.id, m.id, 3000.00, 150.00, 'seed-ticket-0001', 'SEED'
FROM rifa r
JOIN torneo t ON t.id = r.torneo_id
JOIN usuario u ON u.username = 'dev_jugador5'
JOIN movimiento m ON m.referencia_externa = 'seed-ticket-0001'
WHERE t.slug = 'dev-copa-abierta'
ON CONFLICT DO NOTHING;

-- Un número sin fila está libre: solo existen los que alguien eligió. Estos tres quedan VENDIDOS con el ticket.
INSERT INTO numero (rifa_id, numero, estado, ticket_id, creado_por, modificado_por)
SELECT tk.rifa_id, n, 'VENDIDO', tk.id, 'SEED', 'SEED'
FROM ticket tk
CROSS JOIN unnest(ARRAY[7, 13, 42]) AS n
WHERE tk.codigo = 'SEED-0001'
ON CONFLICT DO NOTHING;
