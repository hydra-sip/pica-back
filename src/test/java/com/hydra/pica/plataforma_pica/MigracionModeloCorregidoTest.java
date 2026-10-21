package com.hydra.pica.plataforma_pica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V9 (B1-1): las reglas del modelo corregido que quedan en la base. Cada test arma lo mínimo y termina
 * con la sentencia que la base tiene que rechazar (después de un error, Postgres aborta la transacción).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class MigracionModeloCorregidoTest {

    @Autowired private JdbcTemplate jdbc;

    private long organizador;
    private long arbitro;
    private long jugador;
    private long otroJugador;

    @BeforeEach
    void usuarios() {
        organizador = usuario("organizador");
        arbitro = usuario("arbitro");
        jugador = usuario("jugador");
        otroJugador = usuario("otro");
    }

    @Test
    void creaLasTablasDelModeloYLaIpDeLaSesion() {
        List<String> tablas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tablas).contains(
                "cancha", "torneo", "torneo_cancha", "torneo_arbitro", "premio", "gasto",
                "equipo", "usuario_equipo", "inscripcion",
                "partido", "juega", "usuario_equipo_partido", "evidencia", "objecion", "pronostico",
                "billetera", "movimiento", "cuenta_bancaria",
                "rifa", "premio_rifa", "ticket", "numero", "reclamo_rifa",
                "bloqueo", "auditoria");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'refresh_token' AND column_name = 'ip'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void elCupoMinimoNoPuedeSuperarAlMaximo() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio)
                VALUES (?, 'T', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 4, 8, 0, 50, 10,
                        now(), now() + interval '1 day')
                """, organizador))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_torneo_cupos");
    }

    @Test
    void unaInscripcionEsDeUnUsuarioODeUnEquipoPeroNoDeAmbos() {
        long torneo = torneo("EQUIPO");
        long equipo = equipoCon(jugador);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inscripcion (torneo_id, usuario_id, equipo_id, reglamento_aceptado_en) VALUES (?, ?, ?, now())",
                torneo, jugador, equipo))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_inscripcion_participante");
    }

    @Test
    void unTorneoPorEquiposNoInscribeUsuariosSueltos() {
        long torneo = torneo("EQUIPO");

        assertThatThrownBy(() -> inscribirUsuario(torneo, jugador))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("la inscripción no corresponde");
    }

    @Test
    void elCapitanTieneQueSerIntegranteDelEquipo() {
        assertThatCode(() -> {
            equipoCon(jugador);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }).doesNotThrowAnyException();

        // SET CONSTRAINTS dura hasta el final de la transacción: con IMMEDIATE, el insert ya falla solo
        assertThatThrownBy(() -> jdbc.update("INSERT INTO equipo (capitan_id, nombre) VALUES (?, 'Sin capitán')", otroJugador))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no es integrante del equipo");
    }

    @Test
    void elCapitanNoSePuedeIrDelEquipo() {
        long equipo = equipoCon(jugador);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM usuario_equipo WHERE usuario_id = ? AND equipo_id = ?", jugador, equipo))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no es integrante del equipo");
    }

    @Test
    void elArbitroDelPartidoTieneQueEstarDesignadoEnElTorneo() {
        long torneo = torneo("INDIVIDUAL");
        designarArbitro(torneo);
        partido(torneo, 1);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO partido (torneo_id, arbitro_id, ronda, posicion) VALUES (?, ?, 1, 2)", torneo, otroJugador))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_partido_arbitro_designado");
    }

    @Test
    void laCanchaDelPartidoTieneQueSerDelTorneo() {
        long torneo = torneo("INDIVIDUAL");
        designarArbitro(torneo);
        long cancha = id("INSERT INTO cancha (nombre, sede) VALUES ('Cancha 1', 'Club') RETURNING id");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO partido (torneo_id, arbitro_id, cancha_id, ronda, posicion) VALUES (?, ?, ?, 1, 1)",
                torneo, arbitro, cancha))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_partido_cancha_del_torneo");
    }

    @Test
    void unPartidoTieneComoMaximoDosLados() {
        long torneo = torneo("INDIVIDUAL");
        designarArbitro(torneo);
        long partido = partido(torneo, 1);
        jugar(partido, inscribirUsuario(torneo, jugador), "LOCAL");

        assertThatThrownBy(() -> jugar(partido, inscribirUsuario(torneo, otroJugador), "LOCAL"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_juega_partido_lado");
    }

    @Test
    void soloObjetaUnaDeLasPartesDelPartido() {
        long torneo = torneo("INDIVIDUAL");
        designarArbitro(torneo);
        long partido = partido(torneo, 1);
        jugar(partido, inscribirUsuario(torneo, jugador), "LOCAL");
        long ajena = inscribirUsuario(torneo, otroJugador);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO objecion (partido_id, inscripcion_id, motivo, puntaje_local, puntaje_visitante)
                VALUES (?, ?, 'No jugué', 0, 0)
                """, partido, ajena))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_objecion_parte");
    }

    @Test
    void elLaudoSeCompletaTodoJunto() {
        long torneo = torneo("INDIVIDUAL");
        designarArbitro(torneo);
        long partido = partido(torneo, 1);
        long inscripcion = inscribirUsuario(torneo, jugador);
        jugar(partido, inscripcion, "LOCAL");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO objecion (partido_id, inscripcion_id, motivo, puntaje_local, puntaje_visitante, decision)
                VALUES (?, ?, 'Mal cargado', 2, 1, 'CORREGIDO')
                """, partido, inscripcion))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_objecion_laudo");
    }

    @Test
    void soloJueganElPartidoLosIntegrantesDelEquipo() {
        long torneo = torneo("INDIVIDUAL");
        designarArbitro(torneo);
        long partido = partido(torneo, 1);
        long equipo = equipoCon(jugador);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO usuario_equipo_partido (partido_id, usuario_id, equipo_id) VALUES (?, ?, ?)",
                partido, otroJugador, equipo))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_usuario_equipo_partido_integrante");
    }

    @Test
    void unPagoDeInscripcionLlevaSuInscripcion() {
        long billetera = id("INSERT INTO billetera (usuario_id) VALUES (?) RETURNING id", jugador);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO movimiento (billetera_id, tipo, monto, saldo_resultante) VALUES (?, 'INSCRIPCION', 100, 0)",
                billetera))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_movimiento_inscripcion");
    }

    @Test
    void unTorneoTieneComoMaximoUnaRifa() {
        long torneo = torneo("INDIVIDUAL");
        rifa(torneo);

        assertThatThrownBy(() -> rifa(torneo))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_rifa_torneo_id");
    }

    @Test
    void elNumeroTieneQueEstarEnElRangoDeLaRifa() {
        long rifa = rifa(null);
        jdbc.update("INSERT INTO numero (rifa_id, numero) VALUES (?, 1), (?, 100)", rifa, rifa);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO numero (rifa_id, numero) VALUES (?, 101)", rifa))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fuera del rango");
    }

    @Test
    void unNumeroVendidoTieneTicket() {
        long rifa = rifa(null);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO numero (rifa_id, numero, estado) VALUES (?, 7, 'VENDIDO')", rifa))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_numero_ticket");
    }

    @Test
    void laAuditoriaNoSeModifica() {
        jdbc.update("INSERT INTO auditoria (usuario_id, accion) VALUES (?, 'LOGIN')", jugador);

        assertThatThrownBy(() -> jdbc.update("UPDATE auditoria SET ip = '10.0.0.1'"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");
    }

    @Test
    void laAuditoriaNoSeBorra() {
        jdbc.update("INSERT INTO auditoria (usuario_id, accion) VALUES (?, 'LOGIN')", jugador);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM auditoria"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long usuario(String nombre) {
        long persona = id("INSERT INTO persona (nombres, apellidos) VALUES (?, 'Test') RETURNING id", nombre);
        return id("INSERT INTO usuario (username, email, persona_id) VALUES (?, ?, ?) RETURNING id",
                "v9_" + nombre, "v9_" + nombre + "@example.com", persona);
    }

    private long torneo(String tipoParticipante) {
        return id("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', ?, 8, 2, ?, 1000, 50, 10,
                        now(), now() + interval '1 day')
                RETURNING id
                """, organizador, tipoParticipante, "EQUIPO".equals(tipoParticipante) ? 5 : null);
    }

    private long equipoCon(long capitan) {
        long equipo = id("INSERT INTO equipo (capitan_id, nombre) VALUES (?, 'Equipo') RETURNING id", capitan);
        jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?)", capitan, equipo);
        return equipo;
    }

    private long inscribirUsuario(long torneo, long usuario) {
        return id("INSERT INTO inscripcion (torneo_id, usuario_id, reglamento_aceptado_en) VALUES (?, ?, now()) RETURNING id",
                torneo, usuario);
    }

    private void designarArbitro(long torneo) {
        jdbc.update("INSERT INTO torneo_arbitro (torneo_id, usuario_id) VALUES (?, ?)", torneo, arbitro);
    }

    private long partido(long torneo, int posicion) {
        return id("INSERT INTO partido (torneo_id, arbitro_id, ronda, posicion) VALUES (?, ?, 1, ?) RETURNING id",
                torneo, arbitro, posicion);
    }

    private void jugar(long partido, long inscripcion, String lado) {
        jdbc.update("INSERT INTO juega (partido_id, inscripcion_id, lado) VALUES (?, ?, ?)", partido, inscripcion, lado);
    }

    private long rifa(Long torneo) {
        return id("""
                INSERT INTO rifa (organizador_id, torneo_id, titulo, numero_desde, numero_hasta, precio_numero,
                                  venta_desde, venta_hasta, terminos_aceptados_en)
                VALUES (?, ?, 'Rifa', 1, 100, 500, now(), now() + interval '7 days', now())
                RETURNING id
                """, organizador, torneo);
    }
}
