-- Modelo corregido de la Entrega 3 (B1-1): todas las tablas de torneos, partidos, rifas, billetera y
-- control de una vez, para que nadie espere las tablas de otro. Lo que se puede chequear en la fila va
-- como CHECK, las pertenencias entre tablas como FK compuestas y el resto como trigger.
-- Montos en NUMERIC(14,2) con moneda CHAR(3), ARS por defecto. Los hashes son SHA-256 en hexadecimal.

-- ---------------------------------------------------------------------------------------------------
-- Torneos
-- ---------------------------------------------------------------------------------------------------

-- Canchas reutilizables entre torneos; cada torneo elige las suyas en torneo_cancha.
CREATE TABLE cancha (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    nombre VARCHAR(100) NOT NULL,
    sede VARCHAR(255) NOT NULL,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_cancha PRIMARY KEY (id)
);

-- Los porcentajes no suman 100: pct_pozo_competidores es lo que va al pozo y pct_premio_rifa el % de
-- lo recaudado por la rifa que se suma al premio. La comisión es un recargo fijo, no un porcentaje.
-- slug arma el enlace público; los hashes se fijan al publicar (reglamento, esquema) y al cerrar la
-- inscripción (lista).
CREATE TABLE torneo (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    organizador_id BIGINT NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    descripcion TEXT,
    reglamento_url VARCHAR(500) NOT NULL,
    reglamento_hash CHAR(64),
    disciplina VARCHAR(50) NOT NULL,
    formato VARCHAR(30) NOT NULL,
    modalidad VARCHAR(20) NOT NULL,
    tipo_participante VARCHAR(20) NOT NULL,
    sede VARCHAR(255),
    cupo_maximo INTEGER NOT NULL,
    cupo_minimo INTEGER NOT NULL,
    tamanio_equipo INTEGER,
    valor_inscripcion NUMERIC(14,2) NOT NULL,
    moneda CHAR(3) NOT NULL DEFAULT 'ARS',
    pct_pozo_competidores NUMERIC(5,2) NOT NULL,
    pct_premio_rifa NUMERIC(5,2) NOT NULL,
    cierre_inscripcion_en TIMESTAMPTZ NOT NULL,
    fecha_inicio TIMESTAMPTZ NOT NULL,
    fecha_fin TIMESTAMPTZ,
    estado VARCHAR(30) NOT NULL DEFAULT 'BORRADOR',
    motivo_cancelacion TEXT,
    slug VARCHAR(150),
    esquema_hash CHAR(64),
    publicado_en TIMESTAMPTZ,
    lista_hash CHAR(64),
    lista_sellada_en TIMESTAMPTZ,
    semilla_bracket VARCHAR(64),
    checkin_min_antes INTEGER,
    checkin_duracion_min INTEGER,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_torneo PRIMARY KEY (id),
    CONSTRAINT fk_torneo_organizador FOREIGN KEY (organizador_id) REFERENCES usuario (id),
    CONSTRAINT uq_torneo_slug UNIQUE (slug),
    CONSTRAINT ck_torneo_formato CHECK (formato IN ('ELIMINACION_DIRECTA')),
    CONSTRAINT ck_torneo_modalidad CHECK (modalidad IN ('PRESENCIAL', 'ONLINE')),
    CONSTRAINT ck_torneo_tipo_participante CHECK (tipo_participante IN ('INDIVIDUAL', 'EQUIPO')),
    CONSTRAINT ck_torneo_estado CHECK (estado IN ('BORRADOR', 'INSCRIPCION_ABIERTA', 'INSCRIPCION_CERRADA',
                                                  'EN_CURSO', 'FINALIZADO', 'CANCELADO')),
    CONSTRAINT ck_torneo_cupos CHECK (cupo_minimo >= 2 AND cupo_minimo <= cupo_maximo),
    CONSTRAINT ck_torneo_tamanio_equipo CHECK (
        CASE tipo_participante WHEN 'EQUIPO' THEN tamanio_equipo >= 1 ELSE tamanio_equipo IS NULL END),
    CONSTRAINT ck_torneo_valor_inscripcion CHECK (valor_inscripcion >= 0),
    CONSTRAINT ck_torneo_pct_pozo CHECK (pct_pozo_competidores BETWEEN 0 AND 100),
    CONSTRAINT ck_torneo_pct_premio_rifa CHECK (pct_premio_rifa BETWEEN 0 AND 100),
    CONSTRAINT ck_torneo_fechas CHECK (cierre_inscripcion_en <= fecha_inicio
                                       AND (fecha_fin IS NULL OR fecha_fin >= fecha_inicio)),
    CONSTRAINT ck_torneo_checkin CHECK ((checkin_min_antes IS NULL OR checkin_min_antes > 0)
                                        AND (checkin_duracion_min IS NULL OR checkin_duracion_min > 0)),
    CONSTRAINT ck_torneo_motivo_cancelacion CHECK (estado <> 'CANCELADO' OR motivo_cancelacion IS NOT NULL)
);

CREATE INDEX idx_torneo_organizador_id
    ON torneo (organizador_id);

CREATE TABLE torneo_cancha (
    torneo_id BIGINT NOT NULL,
    cancha_id BIGINT NOT NULL,
    CONSTRAINT pk_torneo_cancha PRIMARY KEY (torneo_id, cancha_id),
    CONSTRAINT fk_torneo_cancha_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_torneo_cancha_cancha FOREIGN KEY (cancha_id) REFERENCES cancha (id)
);

CREATE INDEX idx_torneo_cancha_cancha_id
    ON torneo_cancha (cancha_id);

-- Árbitros designados para el torneo (usuarios con rol ARBITRO). partido.arbitro_id tiene que ser uno
-- de ellos: lo asegura la FK compuesta de partido.
CREATE TABLE torneo_arbitro (
    torneo_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,
    CONSTRAINT pk_torneo_arbitro PRIMARY KEY (torneo_id, usuario_id),
    CONSTRAINT fk_torneo_arbitro_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_torneo_arbitro_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id)
);

CREATE INDEX idx_torneo_arbitro_usuario_id
    ON torneo_arbitro (usuario_id);

-- % del pozo por puesto; cada torneo define cuántos puestos paga. Que sumen 100 lo valida la
-- aplicación al publicar: en la base, la suma queda a medias mientras se cargan los puestos.
CREATE TABLE premio (
    torneo_id BIGINT NOT NULL,
    puesto INTEGER NOT NULL,
    pct NUMERIC(5,2) NOT NULL,
    CONSTRAINT pk_premio PRIMARY KEY (torneo_id, puesto),
    CONSTRAINT fk_premio_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT ck_premio_puesto CHECK (puesto >= 1),
    CONSTRAINT ck_premio_pct CHECK (pct > 0 AND pct <= 100)
);

-- Entidad débil de torneo: gasto_id numera los gastos dentro de cada torneo.
CREATE TABLE gasto (
    torneo_id BIGINT NOT NULL,
    gasto_id INTEGER NOT NULL,
    beneficiario_id BIGINT NOT NULL,
    descripcion VARCHAR(255),
    monto NUMERIC(14,2),
    monto_abonado NUMERIC(14,2) NOT NULL,
    fecha_abonado TIMESTAMPTZ,
    comprobante VARCHAR(500) NOT NULL,
    CONSTRAINT pk_gasto PRIMARY KEY (torneo_id, gasto_id),
    CONSTRAINT fk_gasto_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_gasto_beneficiario FOREIGN KEY (beneficiario_id) REFERENCES usuario (id),
    CONSTRAINT ck_gasto_montos CHECK ((monto IS NULL OR monto >= 0) AND monto_abonado >= 0)
);

CREATE INDEX idx_gasto_beneficiario_id
    ON gasto (beneficiario_id);

-- ---------------------------------------------------------------------------------------------------
-- Equipos e inscripciones
-- ---------------------------------------------------------------------------------------------------

-- El capitán tiene que ser integrante del equipo (trg_equipo_capitan_integrante, al final de la
-- transacción: primero se crea el equipo y después sus integrantes).
CREATE TABLE equipo (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    capitan_id BIGINT NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_equipo PRIMARY KEY (id),
    CONSTRAINT fk_equipo_capitan FOREIGN KEY (capitan_id) REFERENCES usuario (id)
);

CREATE INDEX idx_equipo_capitan_id
    ON equipo (capitan_id);

-- Integrantes; la baja lógica conserva el par, como usuario_rol.
CREATE TABLE usuario_equipo (
    usuario_id BIGINT NOT NULL,
    equipo_id BIGINT NOT NULL,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_usuario_equipo PRIMARY KEY (usuario_id, equipo_id),
    CONSTRAINT fk_usuario_equipo_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_usuario_equipo_equipo FOREIGN KEY (equipo_id) REFERENCES equipo (id)
);

CREATE INDEX idx_usuario_equipo_equipo_id
    ON usuario_equipo (equipo_id);

-- Arco exclusivo: se inscribe un usuario o un equipo, según torneo.tipo_participante (el CHECK pide
-- exactamente uno y trg_inscripcion_tipo_participante que sea el que corresponde).
-- La inscripción pasa a CONFIRMADA cuando su movimiento de pago queda CONFIRMADO.
CREATE TABLE inscripcion (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT NOT NULL,
    usuario_id BIGINT,
    equipo_id BIGINT,
    estado VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    posicion INTEGER,
    reglamento_aceptado_en TIMESTAMPTZ NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_inscripcion PRIMARY KEY (id),
    CONSTRAINT fk_inscripcion_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_inscripcion_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_inscripcion_equipo FOREIGN KEY (equipo_id) REFERENCES equipo (id),
    CONSTRAINT uq_inscripcion_torneo_usuario UNIQUE (torneo_id, usuario_id),
    CONSTRAINT uq_inscripcion_torneo_equipo UNIQUE (torneo_id, equipo_id),
    CONSTRAINT ck_inscripcion_estado CHECK (estado IN ('BORRADOR', 'PENDIENTE_PAGO', 'CONFIRMADA',
                                                       'CANCELADA', 'REINTEGRADA')),
    CONSTRAINT ck_inscripcion_participante CHECK (num_nonnulls(usuario_id, equipo_id) = 1),
    CONSTRAINT ck_inscripcion_posicion CHECK (posicion IS NULL OR posicion >= 1)
);

CREATE INDEX idx_inscripcion_usuario_id
    ON inscripcion (usuario_id);

CREATE INDEX idx_inscripcion_equipo_id
    ON inscripcion (equipo_id);

-- ---------------------------------------------------------------------------------------------------
-- Partidos
-- ---------------------------------------------------------------------------------------------------

-- Los partidos de todas las rondas existen desde que se genera el bracket. Las FK compuestas obligan a
-- que el árbitro esté designado en el torneo y la cancha sea del torneo (cancha_id NULL no se chequea).
CREATE TABLE partido (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT NOT NULL,
    arbitro_id BIGINT NOT NULL,
    cancha_id BIGINT,
    ronda INTEGER NOT NULL,
    posicion INTEGER NOT NULL,
    fecha_programada TIMESTAMPTZ,
    estado VARCHAR(30) NOT NULL DEFAULT 'PROGRAMADO',
    resultado_cargado_en TIMESTAMPTZ,
    objecion_vence_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_partido PRIMARY KEY (id),
    CONSTRAINT fk_partido_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_partido_arbitro_designado FOREIGN KEY (torneo_id, arbitro_id)
        REFERENCES torneo_arbitro (torneo_id, usuario_id),
    CONSTRAINT fk_partido_cancha_del_torneo FOREIGN KEY (torneo_id, cancha_id)
        REFERENCES torneo_cancha (torneo_id, cancha_id),
    CONSTRAINT uq_partido_torneo_ronda_posicion UNIQUE (torneo_id, ronda, posicion),
    CONSTRAINT ck_partido_estado CHECK (estado IN ('PROGRAMADO', 'CHECK_IN', 'EN_JUEGO', 'RESULTADO_CARGADO',
                                                   'CONFIRMADO', 'OBJETADO', 'CORREGIDO', 'WALKOVER')),
    CONSTRAINT ck_partido_ronda_posicion CHECK (ronda >= 1 AND posicion >= 1)
);

CREATE INDEX idx_partido_arbitro_id
    ON partido (arbitro_id);

CREATE INDEX idx_partido_cancha_id
    ON partido (cancha_id);

-- Quién juega cada partido. UK (partido_id, lado) con dos lados posibles: como máximo dos inscripciones.
CREATE TABLE juega (
    partido_id BIGINT NOT NULL,
    inscripcion_id BIGINT NOT NULL,
    lado VARCHAR(10) NOT NULL,
    puntaje INTEGER,
    penales INTEGER,
    es_ganador BOOLEAN,
    checkin_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_juega PRIMARY KEY (partido_id, inscripcion_id),
    CONSTRAINT fk_juega_partido FOREIGN KEY (partido_id) REFERENCES partido (id),
    CONSTRAINT fk_juega_inscripcion FOREIGN KEY (inscripcion_id) REFERENCES inscripcion (id),
    CONSTRAINT uq_juega_partido_lado UNIQUE (partido_id, lado),
    CONSTRAINT ck_juega_lado CHECK (lado IN ('LOCAL', 'VISITANTE')),
    CONSTRAINT ck_juega_puntajes CHECK ((puntaje IS NULL OR puntaje >= 0) AND (penales IS NULL OR penales >= 0))
);

CREATE INDEX idx_juega_inscripcion_id
    ON juega (inscripcion_id);

-- Agregación del DER: qué integrantes del equipo jugaron cada partido. La FK compuesta a
-- usuario_equipo obliga a que el usuario sea integrante de ese equipo.
CREATE TABLE usuario_equipo_partido (
    partido_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,
    equipo_id BIGINT NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_usuario_equipo_partido PRIMARY KEY (partido_id, usuario_id, equipo_id),
    CONSTRAINT fk_usuario_equipo_partido_partido FOREIGN KEY (partido_id) REFERENCES partido (id),
    CONSTRAINT fk_usuario_equipo_partido_integrante FOREIGN KEY (usuario_id, equipo_id)
        REFERENCES usuario_equipo (usuario_id, equipo_id)
);

CREATE INDEX idx_usuario_equipo_partido_integrante
    ON usuario_equipo_partido (usuario_id, equipo_id);

-- Archivos que sube una de las partes del partido (la FK compuesta a juega lo asegura).
CREATE TABLE evidencia (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    partido_id BIGINT NOT NULL,
    inscripcion_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL,
    tipo_contenido VARCHAR(100) NOT NULL,
    tamanio_bytes BIGINT NOT NULL,
    hash CHAR(64) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    CONSTRAINT pk_evidencia PRIMARY KEY (id),
    CONSTRAINT fk_evidencia_parte FOREIGN KEY (partido_id, inscripcion_id)
        REFERENCES juega (partido_id, inscripcion_id),
    CONSTRAINT ck_evidencia_tamanio CHECK (tamanio_bytes > 0)
);

CREATE INDEX idx_evidencia_parte
    ON evidencia (partido_id, inscripcion_id);

-- Una objeción por partido, de una de las partes. Los campos del laudo quedan en NULL hasta que se
-- resuelve, y entonces se completan todos juntos.
CREATE TABLE objecion (
    partido_id BIGINT NOT NULL,
    inscripcion_id BIGINT NOT NULL,
    motivo TEXT NOT NULL,
    puntaje_local INTEGER NOT NULL,
    puntaje_visitante INTEGER NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    resuelta_por BIGINT,
    decision VARCHAR(20),
    justificacion TEXT,
    evidencia_id BIGINT,
    laudo_hash CHAR(64),
    resuelta_en TIMESTAMPTZ,
    CONSTRAINT pk_objecion PRIMARY KEY (partido_id),
    CONSTRAINT fk_objecion_parte FOREIGN KEY (partido_id, inscripcion_id)
        REFERENCES juega (partido_id, inscripcion_id),
    CONSTRAINT fk_objecion_resuelta_por FOREIGN KEY (resuelta_por) REFERENCES usuario (id),
    CONSTRAINT fk_objecion_evidencia FOREIGN KEY (evidencia_id) REFERENCES evidencia (id),
    CONSTRAINT ck_objecion_decision CHECK (decision IN ('CONFIRMADO', 'CORREGIDO')),
    CONSTRAINT ck_objecion_puntajes CHECK (puntaje_local >= 0 AND puntaje_visitante >= 0),
    CONSTRAINT ck_objecion_laudo CHECK (num_nulls(resuelta_por, decision, justificacion, laudo_hash, resuelta_en) IN (0, 5))
);

CREATE INDEX idx_objecion_resuelta_por
    ON objecion (resuelta_por);

CREATE INDEX idx_objecion_evidencia_id
    ON objecion (evidencia_id);

-- Uno por usuario y partido, editable hasta el inicio del partido (lo controla la aplicación).
CREATE TABLE pronostico (
    usuario_id BIGINT NOT NULL,
    partido_id BIGINT NOT NULL,
    eleccion CHAR(1) NOT NULL,
    puntos INTEGER,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_pronostico PRIMARY KEY (usuario_id, partido_id),
    CONSTRAINT fk_pronostico_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_pronostico_partido FOREIGN KEY (partido_id) REFERENCES partido (id),
    CONSTRAINT ck_pronostico_eleccion CHECK (eleccion IN ('1', 'X', '2')),
    CONSTRAINT ck_pronostico_puntos CHECK (puntos IS NULL OR puntos >= 0)
);

CREATE INDEX idx_pronostico_partido_id
    ON pronostico (partido_id);

-- ---------------------------------------------------------------------------------------------------
-- Billetera
-- ---------------------------------------------------------------------------------------------------

-- Una por usuario. Hasta que llegue PICA-6 la usa el cobro simulado (B1-4).
CREATE TABLE billetera (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usuario_id BIGINT NOT NULL,
    saldo_disponible NUMERIC(14,2) NOT NULL DEFAULT 0,
    saldo_retenido NUMERIC(14,2) NOT NULL DEFAULT 0,
    ganancia_acumulada NUMERIC(14,2) NOT NULL DEFAULT 0,
    moneda CHAR(3) NOT NULL DEFAULT 'ARS',
    estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVA',
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_billetera PRIMARY KEY (id),
    CONSTRAINT fk_billetera_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT uq_billetera_usuario_id UNIQUE (usuario_id),
    CONSTRAINT ck_billetera_estado CHECK (estado IN ('ACTIVA', 'BLOQUEADA')),
    CONSTRAINT ck_billetera_saldos CHECK (saldo_disponible >= 0 AND saldo_retenido >= 0 AND ganancia_acumulada >= 0)
);

-- Cargar la billetera (CARGA) y pagar (INSCRIPCION, COMPRA_NUMERO) son movimientos distintos. El monto
-- va siempre positivo: el tipo dice si entra o sale.
CREATE TABLE movimiento (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    billetera_id BIGINT NOT NULL,
    torneo_id BIGINT,
    inscripcion_id BIGINT,
    tipo VARCHAR(30) NOT NULL,
    monto NUMERIC(14,2) NOT NULL,
    moneda CHAR(3) NOT NULL DEFAULT 'ARS',
    saldo_resultante NUMERIC(14,2) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    referencia_externa VARCHAR(100),
    descripcion VARCHAR(255),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_movimiento PRIMARY KEY (id),
    CONSTRAINT fk_movimiento_billetera FOREIGN KEY (billetera_id) REFERENCES billetera (id),
    CONSTRAINT fk_movimiento_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_movimiento_inscripcion FOREIGN KEY (inscripcion_id) REFERENCES inscripcion (id),
    CONSTRAINT ck_movimiento_tipo CHECK (tipo IN ('CARGA', 'INSCRIPCION', 'COMPRA_NUMERO', 'PREMIO', 'REINTEGRO',
                                                  'COMISION', 'RETIRO', 'PAGO_SERVICIO')),
    CONSTRAINT ck_movimiento_estado CHECK (estado IN ('PENDIENTE', 'CONFIRMADO', 'RECHAZADO', 'REVERTIDO')),
    CONSTRAINT ck_movimiento_monto CHECK (monto > 0),
    CONSTRAINT ck_movimiento_inscripcion CHECK (tipo <> 'INSCRIPCION' OR inscripcion_id IS NOT NULL)
);

CREATE INDEX idx_movimiento_billetera_id
    ON movimiento (billetera_id);

CREATE INDEX idx_movimiento_torneo_id
    ON movimiento (torneo_id);

CREATE INDEX idx_movimiento_inscripcion_id
    ON movimiento (inscripcion_id);

-- Solo se retira a una cuenta VALIDADA.
CREATE TABLE cuenta_bancaria (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usuario_id BIGINT NOT NULL,
    tipo VARCHAR(10) NOT NULL,
    valor VARCHAR(30) NOT NULL,
    titular VARCHAR(150) NOT NULL,
    cuit_cuil VARCHAR(13) NOT NULL,
    estado_validacion VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    validada_en TIMESTAMPTZ,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_cuenta_bancaria PRIMARY KEY (id),
    CONSTRAINT fk_cuenta_bancaria_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT uq_cuenta_bancaria_usuario_valor UNIQUE (usuario_id, valor),
    CONSTRAINT ck_cuenta_bancaria_tipo CHECK (tipo IN ('CBU', 'CVU', 'ALIAS')),
    CONSTRAINT ck_cuenta_bancaria_estado_validacion CHECK (estado_validacion IN ('PENDIENTE', 'VALIDADA', 'RECHAZADA'))
);

-- ---------------------------------------------------------------------------------------------------
-- Rifas
-- ---------------------------------------------------------------------------------------------------

-- Un torneo tiene como máximo una rifa (UK torneo_id). lista_hash sella los vendidos al cerrar la
-- venta; compromiso y semilla son del sorteo verificable (PICA-5).
CREATE TABLE rifa (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    organizador_id BIGINT NOT NULL,
    torneo_id BIGINT,
    titulo VARCHAR(100) NOT NULL,
    descripcion TEXT,
    numero_desde INTEGER NOT NULL,
    numero_hasta INTEGER NOT NULL,
    precio_numero NUMERIC(14,2) NOT NULL,
    moneda CHAR(3) NOT NULL DEFAULT 'ARS',
    venta_desde TIMESTAMPTZ NOT NULL,
    venta_hasta TIMESTAMPTZ NOT NULL,
    terminos_aceptados_en TIMESTAMPTZ NOT NULL,
    loteria_declarada_en TIMESTAMPTZ,
    estado VARCHAR(30) NOT NULL DEFAULT 'BORRADOR',
    lista_hash CHAR(64),
    lista_sellada_en TIMESTAMPTZ,
    compromiso_hash CHAR(64),
    compromiso_publicado_en TIMESTAMPTZ,
    semilla VARCHAR(255),
    semilla_firma TEXT,
    semilla_obtenida_en TIMESTAMPTZ,
    sorteada_en TIMESTAMPTZ,
    eliminado_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_rifa PRIMARY KEY (id),
    CONSTRAINT fk_rifa_organizador FOREIGN KEY (organizador_id) REFERENCES usuario (id),
    CONSTRAINT fk_rifa_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT uq_rifa_torneo_id UNIQUE (torneo_id),
    CONSTRAINT ck_rifa_estado CHECK (estado IN ('BORRADOR', 'EN_VENTA', 'CERRADA', 'SORTEADA', 'EN_REVISION',
                                                'LIQUIDADA', 'CANCELADA')),
    CONSTRAINT ck_rifa_numeros CHECK (numero_desde >= 0 AND numero_desde <= numero_hasta),
    CONSTRAINT ck_rifa_venta CHECK (venta_desde < venta_hasta),
    CONSTRAINT ck_rifa_precio CHECK (precio_numero > 0)
);

CREATE INDEX idx_rifa_organizador_id
    ON rifa (organizador_id);

-- % del pozo de la rifa por puesto. Que sumen 100 lo valida la aplicación al publicar, como premio.
CREATE TABLE premio_rifa (
    rifa_id BIGINT NOT NULL,
    puesto INTEGER NOT NULL,
    pct NUMERIC(5,2) NOT NULL,
    descripcion VARCHAR(255),
    CONSTRAINT pk_premio_rifa PRIMARY KEY (rifa_id, puesto),
    CONSTRAINT fk_premio_rifa_rifa FOREIGN KEY (rifa_id) REFERENCES rifa (id),
    CONSTRAINT ck_premio_rifa_puesto CHECK (puesto >= 1),
    CONSTRAINT ck_premio_rifa_pct CHECK (pct > 0 AND pct <= 100)
);

-- Una compra de uno o más números, con su movimiento de pago y una clave de idempotencia para que un
-- reintento no cobre dos veces.
CREATE TABLE ticket (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    codigo VARCHAR(20) NOT NULL,
    rifa_id BIGINT NOT NULL,
    comprador_id BIGINT NOT NULL,
    movimiento_id BIGINT NOT NULL,
    monto NUMERIC(14,2) NOT NULL,
    comision NUMERIC(14,2) NOT NULL,
    moneda CHAR(3) NOT NULL DEFAULT 'ARS',
    clave_idempotencia VARCHAR(64) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    CONSTRAINT pk_ticket PRIMARY KEY (id),
    CONSTRAINT fk_ticket_rifa FOREIGN KEY (rifa_id) REFERENCES rifa (id),
    CONSTRAINT fk_ticket_comprador FOREIGN KEY (comprador_id) REFERENCES usuario (id),
    CONSTRAINT fk_ticket_movimiento FOREIGN KEY (movimiento_id) REFERENCES movimiento (id),
    CONSTRAINT uq_ticket_codigo UNIQUE (codigo),
    CONSTRAINT uq_ticket_movimiento_id UNIQUE (movimiento_id),
    CONSTRAINT uq_ticket_clave_idempotencia UNIQUE (clave_idempotencia),
    CONSTRAINT ck_ticket_montos CHECK (monto > 0 AND comision >= 0)
);

CREATE INDEX idx_ticket_rifa_id
    ON ticket (rifa_id);

CREATE INDEX idx_ticket_comprador_id
    ON ticket (comprador_id);

-- Se crean todos al publicar la rifa, como DISPONIBLE, y la compra los pasa a VENDIDO con un UPDATE
-- condicionado al estado. Un número sin ticket está DISPONIBLE y uno con ticket no.
-- posicion_sorteo es NULL si no ganó. Que el número esté en el rango de su rifa lo chequea
-- trg_numero_en_rango.
CREATE TABLE numero (
    rifa_id BIGINT NOT NULL,
    numero INTEGER NOT NULL,
    ticket_id BIGINT,
    estado VARCHAR(20) NOT NULL DEFAULT 'DISPONIBLE',
    posicion_sorteo INTEGER,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_numero PRIMARY KEY (rifa_id, numero),
    CONSTRAINT fk_numero_rifa FOREIGN KEY (rifa_id) REFERENCES rifa (id),
    CONSTRAINT fk_numero_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT uq_numero_rifa_posicion_sorteo UNIQUE (rifa_id, posicion_sorteo),
    CONSTRAINT ck_numero_estado CHECK (estado IN ('DISPONIBLE', 'VENDIDO', 'REINTEGRADO')),
    CONSTRAINT ck_numero_ticket CHECK ((estado = 'DISPONIBLE') = (ticket_id IS NULL)),
    CONSTRAINT ck_numero_posicion_sorteo CHECK (posicion_sorteo IS NULL OR posicion_sorteo >= 1)
);

CREATE INDEX idx_numero_ticket_id
    ON numero (ticket_id);

-- Reclamo de un participante sobre el sorteo; mientras está PENDIENTE frena la liquidación.
CREATE TABLE reclamo_rifa (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    rifa_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,
    motivo TEXT NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    resolucion TEXT,
    resuelto_en TIMESTAMPTZ,
    resuelto_por VARCHAR(100),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_reclamo_rifa PRIMARY KEY (id),
    CONSTRAINT fk_reclamo_rifa_rifa FOREIGN KEY (rifa_id) REFERENCES rifa (id),
    CONSTRAINT fk_reclamo_rifa_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_reclamo_rifa_estado CHECK (estado IN ('PENDIENTE', 'ACEPTADO', 'RECHAZADO'))
);

CREATE INDEX idx_reclamo_rifa_rifa_id
    ON reclamo_rifa (rifa_id);

CREATE INDEX idx_reclamo_rifa_usuario_id
    ON reclamo_rifa (usuario_id);

-- ---------------------------------------------------------------------------------------------------
-- Control
-- ---------------------------------------------------------------------------------------------------

-- Con hasta NULL es indefinido y lo levanta soporte; con fecha se levanta solo. alcance USUARIO no deja
-- iniciar sesión; BILLETERA solo frena retiros.
CREATE TABLE bloqueo (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usuario_id BIGINT NOT NULL,
    alcance VARCHAR(20) NOT NULL,
    motivo TEXT NOT NULL,
    desde TIMESTAMPTZ NOT NULL DEFAULT now(),
    hasta TIMESTAMPTZ,
    levantado_en TIMESTAMPTZ,
    levantado_por VARCHAR(100),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    CONSTRAINT pk_bloqueo PRIMARY KEY (id),
    CONSTRAINT fk_bloqueo_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_bloqueo_alcance CHECK (alcance IN ('USUARIO', 'BILLETERA')),
    CONSTRAINT ck_bloqueo_hasta CHECK (hasta IS NULL OR hasta > desde)
);

CREATE INDEX idx_bloqueo_usuario_id
    ON bloqueo (usuario_id);

-- Registro de solo agregar: incluye los inicios de sesión, también los fallidos (usuario_id NULL si el
-- usuario no existe). trg_auditoria_solo_agregar rechaza UPDATE y DELETE.
CREATE TABLE auditoria (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usuario_id BIGINT,
    accion VARCHAR(50) NOT NULL,
    entidad VARCHAR(50),
    entidad_id VARCHAR(50),
    ip VARCHAR(45),
    detalle JSONB,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_auditoria PRIMARY KEY (id),
    CONSTRAINT fk_auditoria_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_auditoria_accion CHECK (accion IN ('LOGIN', 'LOGIN_FALLIDO', 'LOGOUT', 'ALTA', 'MODIFICACION',
                                                     'BAJA', 'BLOQUEO', 'DESBLOQUEO', 'PAGO', 'LAUDO', 'SORTEO'))
);

CREATE INDEX idx_auditoria_usuario_id
    ON auditoria (usuario_id);

CREATE INDEX idx_auditoria_entidad
    ON auditoria (entidad, entidad_id);

-- IP desde la que se abrió la sesión (CE3-3).
ALTER TABLE refresh_token ADD COLUMN ip VARCHAR(45);

-- ---------------------------------------------------------------------------------------------------
-- Triggers: reglas que no se pueden escribir como CHECK ni como FK
-- ---------------------------------------------------------------------------------------------------

-- El número tiene que estar entre numero_desde y numero_hasta de su rifa.
CREATE FUNCTION fn_numero_en_rango() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM rifa r
                   WHERE r.id = NEW.rifa_id AND NEW.numero BETWEEN r.numero_desde AND r.numero_hasta) THEN
        RAISE EXCEPTION 'El número % está fuera del rango de la rifa %', NEW.numero, NEW.rifa_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_numero_en_rango';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_numero_en_rango
    BEFORE INSERT OR UPDATE OF rifa_id, numero ON numero
    FOR EACH ROW EXECUTE FUNCTION fn_numero_en_rango();

-- Un torneo INDIVIDUAL inscribe usuarios y uno por EQUIPO inscribe equipos.
CREATE FUNCTION fn_inscripcion_tipo_participante() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    tipo VARCHAR(20);
BEGIN
    SELECT t.tipo_participante INTO tipo FROM torneo t WHERE t.id = NEW.torneo_id;
    IF (tipo = 'INDIVIDUAL' AND NEW.usuario_id IS NULL) OR (tipo = 'EQUIPO' AND NEW.equipo_id IS NULL) THEN
        RAISE EXCEPTION 'El torneo % es %: la inscripción no corresponde', NEW.torneo_id, tipo
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_inscripcion_tipo_participante';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inscripcion_tipo_participante
    BEFORE INSERT OR UPDATE OF torneo_id, usuario_id, equipo_id ON inscripcion
    FOR EACH ROW EXECUTE FUNCTION fn_inscripcion_tipo_participante();

-- El capitán de un equipo activo tiene que ser integrante activo. Se chequea al final de la transacción
-- (DEFERRABLE INITIALLY DEFERRED), así se puede crear el equipo y después agregar al capitán.
CREATE FUNCTION fn_equipo_capitan_integrante() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    eq_id BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'equipo' THEN
        eq_id := NEW.id;
    ELSE
        eq_id := OLD.equipo_id;
    END IF;
    IF EXISTS (SELECT 1 FROM equipo e
               WHERE e.id = eq_id AND e.eliminado_en IS NULL
                 AND NOT EXISTS (SELECT 1 FROM usuario_equipo ue
                                 WHERE ue.equipo_id = e.id AND ue.usuario_id = e.capitan_id
                                   AND ue.eliminado_en IS NULL)) THEN
        RAISE EXCEPTION 'El capitán del equipo % no es integrante del equipo', eq_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_equipo_capitan_integrante';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_equipo_capitan_integrante
    AFTER INSERT OR UPDATE OF capitan_id, eliminado_en ON equipo
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_equipo_capitan_integrante();

-- Sacar al capitán del equipo (borrarlo o darlo de baja) también rompe la regla.
CREATE CONSTRAINT TRIGGER trg_usuario_equipo_capitan_integrante
    AFTER UPDATE OF usuario_id, equipo_id, eliminado_en OR DELETE ON usuario_equipo
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_equipo_capitan_integrante();

-- La auditoría solo se agrega: la aplicación se conecta como dueña de las tablas, así que quitarle
-- UPDATE y DELETE con permisos no alcanza.
CREATE FUNCTION fn_auditoria_solo_agregar() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'La auditoría no se modifica ni se borra'
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_auditoria_solo_agregar
    BEFORE UPDATE OR DELETE ON auditoria
    FOR EACH ROW EXECUTE FUNCTION fn_auditoria_solo_agregar();

CREATE TRIGGER trg_auditoria_sin_truncate
    BEFORE TRUNCATE ON auditoria
    FOR EACH STATEMENT EXECUTE FUNCTION fn_auditoria_solo_agregar();
