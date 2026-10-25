package com.hydra.pica.plataforma_pica.inscripcion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Dos pagos simultáneos por el último lugar (PICA-210), contra Postgres y sin la transacción del test:
 * cada hilo corre su propia transacción, así que lo que se prueba es el bloqueo de la fila del torneo.
 * Como lo que se escribe queda confirmado, el escenario se borra al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class InscripcionPagoConcurrenteIntegracionTest {

    @Autowired private InscripcionService inscripcionService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> usuarios = new ArrayList<>();
    private final List<Long> personas = new ArrayList<>();
    private Long torneo;

    @AfterEach
    void limpiar() {
        if (torneo != null) {
            jdbc.update("DELETE FROM movimiento WHERE inscripcion_id IN (SELECT id FROM inscripcion WHERE torneo_id = ?)", torneo);
            jdbc.update("DELETE FROM nomina WHERE inscripcion_id IN (SELECT id FROM inscripcion WHERE torneo_id = ?)", torneo);
            jdbc.update("DELETE FROM inscripcion WHERE torneo_id = ?", torneo);
            jdbc.update("DELETE FROM torneo WHERE id = ?", torneo);
        }
        for (Long usuario : usuarios) {
            jdbc.update("DELETE FROM billetera WHERE usuario_id = ?", usuario);
            // en un solo statement: un equipo vivo no puede quedar sin su capitán como integrante
            jdbc.update("""
                    WITH propios AS (SELECT id FROM equipo WHERE capitan_id = ?),
                         integrantes AS (DELETE FROM usuario_equipo
                                         WHERE usuario_id = ? OR equipo_id IN (SELECT id FROM propios) RETURNING 1)
                    DELETE FROM equipo WHERE id IN (SELECT id FROM propios)
                    """, usuario, usuario);
            jdbc.update("DELETE FROM usuario WHERE id = ?", usuario);
        }
        for (Long persona : personas) {
            jdbc.update("DELETE FROM persona WHERE id = ?", persona);
        }
    }

    @RepeatedTest(5)
    void dosPagosPorElUltimoLugarConfirmanUnoYElOtroRecibeCupoCompleto() throws Exception {
        Long organizador = usuario("org_conc");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 2, 2,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        inscripcion(usuario("ya_conc"), "CONFIRMADA");
        Long jugadorA = usuario("a_conc");
        Long jugadorB = usuario("b_conc");
        long inscripcionA = inscripcion(jugadorA, "PENDIENTE_PAGO");
        long inscripcionB = inscripcion(jugadorB, "PENDIENTE_PAGO");

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<EstadoInscripcion> pagoA = hilos.submit(pago(salida, jugadorA, inscripcionA));
            Future<EstadoInscripcion> pagoB = hilos.submit(pago(salida, jugadorB, inscripcionB));

            List<EstadoInscripcion> confirmados = new ArrayList<>();
            List<CodigoError> rechazos = new ArrayList<>();
            for (Future<EstadoInscripcion> pago : List.of(pagoA, pagoB)) {
                try {
                    confirmados.add(pago.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                }
            }

            assertThat(confirmados).containsExactly(EstadoInscripcion.CONFIRMADA);
            assertThat(rechazos).containsExactly(CodigoError.CUPO_COMPLETO);
        } finally {
            hilos.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'CONFIRMADA'", Integer.class, torneo)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'PENDIENTE_PAGO'", Integer.class, torneo)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM movimiento WHERE inscripcion_id IN (SELECT id FROM inscripcion WHERE torneo_id = ?)",
                Integer.class, torneo)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void dosPagosSimultaneosDeLaMismaInscripcionCobranUnaSolaVez() throws Exception {
        Long organizador = usuario("org_misma");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long jugador = usuario("j_misma");
        long inscripcion = inscripcion(jugador, "PENDIENTE_PAGO");

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<EstadoInscripcion> primero = hilos.submit(pago(salida, jugador, inscripcion));
            Future<EstadoInscripcion> segundo = hilos.submit(pago(salida, jugador, inscripcion));

            assertThat(primero.get(30, TimeUnit.SECONDS)).isEqualTo(EstadoInscripcion.CONFIRMADA);
            assertThat(segundo.get(30, TimeUnit.SECONDS)).isEqualTo(EstadoInscripcion.CONFIRMADA);
        } finally {
            hilos.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, inscripcion))
                .isEqualTo(1);
    }

    @RepeatedTest(5)
    void dosBorradoresSimultaneosDelMismoUsuarioCreanUnoSoloYElOtroRecibeDuplicada() throws Exception {
        Long organizador = usuario("org_borr");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long jugador = usuario("j_borr");

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Callable<EstadoInscripcion> creacion = () -> {
                salida.await();
                return inscripcionService.crear(jugador, torneo, null).estado();
            };
            Future<EstadoInscripcion> primera = hilos.submit(creacion);
            Future<EstadoInscripcion> segunda = hilos.submit(creacion);

            List<EstadoInscripcion> creados = new ArrayList<>();
            List<CodigoError> rechazos = new ArrayList<>();
            for (Future<EstadoInscripcion> pedido : List.of(primera, segunda)) {
                try {
                    creados.add(pedido.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                }
            }

            assertThat(creados).containsExactly(EstadoInscripcion.BORRADOR);
            assertThat(rechazos).containsExactly(CodigoError.INSCRIPCION_DUPLICADA);
        } finally {
            hilos.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'BORRADOR'", Integer.class, torneo)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void dosEquiposQueComparteUnJugadorConfirmanALaVezYSoloUnoConfirma() throws Exception {
        Long organizador = usuario("org_comp");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima,
                                    valor_inscripcion, pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en,
                                    fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', 'EQUIPO', 8, 2, 2, 3,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long compartido = usuario("compartido_comp");
        Long capitanA = usuario("cap_a_comp");
        Long capitanB = usuario("cap_b_comp");
        long inscripcionA = borradorDeEquipo(capitanA, compartido);
        long inscripcionB = borradorDeEquipo(capitanB, compartido);

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<EstadoInscripcion> confirmaA = hilos.submit(confirmacion(salida, capitanA, inscripcionA));
            Future<EstadoInscripcion> confirmaB = hilos.submit(confirmacion(salida, capitanB, inscripcionB));

            List<EstadoInscripcion> confirmadas = new ArrayList<>();
            List<CodigoError> rechazos = new ArrayList<>();
            for (Future<EstadoInscripcion> confirma : List.of(confirmaA, confirmaB)) {
                try {
                    confirmadas.add(confirma.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                }
            }

            assertThat(confirmadas).containsExactly(EstadoInscripcion.PENDIENTE_PAGO);
            assertThat(rechazos).containsExactly(CodigoError.JUGADOR_YA_INSCRIPTO);
        } finally {
            hilos.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'PENDIENTE_PAGO'", Integer.class, torneo)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void dosConfirmacionesSimultaneasDelMismoBorradorDeEquipoConfirmanUnaSolaVez() throws Exception {
        Long organizador = usuario("org_misma_conf");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima,
                                    valor_inscripcion, pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en,
                                    fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', 'EQUIPO', 8, 2, 2, 3,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long jugador = usuario("jug_misma_conf");
        Long capitan = usuario("cap_misma_conf");
        long inscripcion = borradorDeEquipo(capitan, jugador);

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<EstadoInscripcion> primera = hilos.submit(confirmacion(salida, capitan, inscripcion));
            Future<EstadoInscripcion> segunda = hilos.submit(confirmacion(salida, capitan, inscripcion));

            List<EstadoInscripcion> confirmadas = new ArrayList<>();
            List<CodigoError> rechazos = new ArrayList<>();
            for (Future<EstadoInscripcion> confirma : List.of(primera, segunda)) {
                try {
                    confirmadas.add(confirma.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                }
            }

            // la segunda ve el estado que dejó la primera: ya no es un borrador
            assertThat(confirmadas).containsExactly(EstadoInscripcion.PENDIENTE_PAGO);
            assertThat(rechazos).containsExactly(CodigoError.INSCRIPCION_NO_EDITABLE);
        } finally {
            hilos.shutdownNow();
        }
    }

    private Callable<EstadoInscripcion> confirmacion(CyclicBarrier salida, Long capitan, long inscripcion) {
        return () -> {
            salida.await();
            return inscripcionService.confirmar(capitan, inscripcion, true).estado();
        };
    }

    // un equipo del capitán con otro jugador, y su borrador con los dos en la nómina; si se le pasa el mismo
    // jugador a dos capitanes, comparten un jugador
    private long borradorDeEquipo(Long capitan, Long jugador) {
        // en un solo statement: el capitán tiene que ser integrante cuando termina la transacción, y acá cada
        // statement hace commit
        long equipo = jdbc.queryForObject("""
                WITH e AS (INSERT INTO equipo (capitan_id, nombre) VALUES (?, ?) RETURNING id),
                     i AS (INSERT INTO usuario_equipo (usuario_id, equipo_id)
                           SELECT u, e.id FROM e, (VALUES (CAST(? AS BIGINT)), (CAST(? AS BIGINT))) AS m (u))
                SELECT id FROM e
                """, Long.class, capitan, "Equipo " + capitan, capitan, jugador);
        long inscripcion = jdbc.queryForObject(
                "INSERT INTO inscripcion (torneo_id, equipo_id, estado) VALUES (?, ?, 'BORRADOR') RETURNING id", Long.class, torneo, equipo);
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?), (?, ?, ?)",
                inscripcion, capitan, equipo, inscripcion, jugador, equipo);
        return inscripcion;
    }

    private Callable<EstadoInscripcion> pago(CyclicBarrier salida, Long usuario, long inscripcion) {
        return () -> {
            salida.await();
            return inscripcionService.pagar(usuario, inscripcion).estado();
        };
    }

    private long inscripcion(Long usuario, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, now())
                RETURNING id
                """, Long.class, torneo, usuario, estado);
    }

    private Long usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);
        personas.add(persona.getId());

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        usuario = usuarioRepository.save(usuario);
        usuarios.add(usuario.getId());
        return usuario.getId();
    }
}
