package com.hydra.pica.plataforma_pica.inscripcion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.torneo.service.InscripcionTorneoService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operaciones simultáneas sobre la misma inscripción o el mismo cupo (PICA-210, PICA-208), contra Postgres y sin la transacción del test:
 * cada hilo corre su propia transacción, así que lo que se prueba es el bloqueo de la fila del torneo.
 * Como lo que se escribe queda confirmado, el escenario se borra al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class InscripcionPagoConcurrenteIntegracionTest {

    @Autowired private InscripcionService inscripcionService;
    @Autowired private InscripcionTorneoService inscripcionTorneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transacciones;

    private final List<Long> usuarios = new ArrayList<>();
    private final List<Long> personas = new ArrayList<>();
    private Long torneo;

    @AfterEach
    void limpiar() {
        if (torneo != null) {
            jdbc.update("DELETE FROM reemplazo WHERE inscripcion_id IN (SELECT id FROM inscripcion WHERE torneo_id = ?)", torneo);
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
    void dosPagosPorElUltimoLugarConfirmanUnoYElOtroLlegaConLaInscripcionCerrada() throws Exception {
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
            // el primer pago completó el cupo y cerró la inscripción, que canceló la otra pendiente (HU-206)
            assertThat(rechazos).containsExactly(CodigoError.INSCRIPCION_NO_ABIERTA);
        } finally {
            hilos.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_CERRADA");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'CONFIRMADA'", Integer.class, torneo)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'CANCELADA'", Integer.class, torneo)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM movimiento WHERE inscripcion_id IN (SELECT id FROM inscripcion WHERE torneo_id = ?)",
                Integer.class, torneo)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void unCierreAManoYUnPagoSimultaneosNoDejanUnaConfirmadaFueraDeLaListaSellada() throws Exception {
        Long organizador = organizadorQueQuedaEnLaAuditoria();
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long jugador = usuario("j_cierre_pago");
        long inscripcion = inscripcion(jugador, "PENDIENTE_PAGO");

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<EstadoInscripcion> pago = hilos.submit(pago(salida, jugador, inscripcion));
            Future<?> cierre = hilos.submit(() -> {
                salida.await();
                return inscripcionTorneoService.cerrar(organizador, torneo);
            });

            cierre.get(30, TimeUnit.SECONDS);
            try {
                assertThat(pago.get(30, TimeUnit.SECONDS)).isEqualTo(EstadoInscripcion.CONFIRMADA);
            } catch (ExecutionException e) {
                assertThat(((ApiException) e.getCause()).getCodigo()).isEqualTo(CodigoError.INSCRIPCION_NO_ABIERTA);
            }
        } finally {
            hilos.shutdownNow();
        }
        String estado = jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, inscripcion);
        String sello = jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, torneo);
        // o pagó antes del cierre y figura en el sello, o llegó después y quedó cancelada con la lista vacía
        if (estado.equals("CONFIRMADA")) {
            assertThat(sello).isEqualTo(sha256(inscripcion + ";U;" + jugador));
        } else {
            assertThat(estado).isEqualTo("CANCELADA");
            assertThat(sello).isEqualTo(sha256(""));
        }
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
    void dosCapitanesQueReemplazanAlMismoJugadorSoloDejanUnaNomina() throws Exception {
        Long organizador = usuario("org_reemplazo_conc");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima,
                                    valor_inscripcion, pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en,
                                    fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', 'EQUIPO', 8, 2, 2, 3,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long capitanA = usuario("cap_reemplazo_a");
        Long capitanB = usuario("cap_reemplazo_b");
        Long saleA = usuario("sale_reemplazo_a");
        Long saleB = usuario("sale_reemplazo_b");
        Long entra = usuario("entra_reemplazo_conc");
        long inscripcionA = inscripcionConfirmadaDeEquipo(capitanA, saleA, "A");
        long inscripcionB = inscripcionConfirmadaDeEquipo(capitanB, saleB, "B");

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<InscripcionDetalle> reemplazoA = hilos.submit(reemplazo(salida, capitanA, inscripcionA, saleA, entra));
            Future<InscripcionDetalle> reemplazoB = hilos.submit(reemplazo(salida, capitanB, inscripcionB, saleB, entra));

            List<InscripcionDetalle> exitos = new ArrayList<>();
            List<CodigoError> rechazos = new ArrayList<>();
            for (Future<InscripcionDetalle> reemplazo : List.of(reemplazoA, reemplazoB)) {
                try {
                    exitos.add(reemplazo.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                }
            }

            assertThat(exitos).hasSize(1);
            assertThat(rechazos).containsExactly(CodigoError.JUGADOR_YA_INSCRIPTO);
        } finally {
            hilos.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nomina WHERE usuario_id = ?", Integer.class, entra)).isEqualTo(1);
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

    // editar reemplaza la nómina por la del capitán solo, que no alcanza para confirmar: si la confirmación
    // ganara sin ver ese cambio, quedaría una pendiente con la nómina sin validar
    @RepeatedTest(5)
    void editarYConfirmarElMismoBorradorDeEquipoNoDejanUnaPendienteConLaNominaSinValidar() throws Exception {
        Long organizador = usuario("org_edit_conf");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima,
                                    valor_inscripcion, pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en,
                                    fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', 'EQUIPO', 8, 2, 2, 3,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long jugador = usuario("jug_edit_conf");
        Long capitan = usuario("cap_edit_conf");
        long inscripcion = borradorDeEquipo(capitan, jugador);
        Long equipo = jdbc.queryForObject("SELECT equipo_id FROM inscripcion WHERE id = ?", Long.class, inscripcion);

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        List<CodigoError> rechazos = new ArrayList<>();
        boolean confirmo;
        try {
            Future<EstadoInscripcion> edita = hilos.submit(() -> {
                salida.await();
                return inscripcionService.editar(capitan, inscripcion, new InscripcionEquipoRequest(equipo, null, List.of())).estado();
            });
            Future<EstadoInscripcion> confirma = hilos.submit(confirmacion(salida, capitan, inscripcion));

            confirmo = resultado(confirma, rechazos) != null;
            resultado(edita, rechazos);
        } finally {
            hilos.shutdownNow();
        }

        // pierde el que llega segundo, y por el estado o la nómina que dejó el primero
        assertThat(rechazos).isSubsetOf(CodigoError.INSCRIPCION_NO_EDITABLE, CodigoError.NOMINA_INVALIDA);
        String estado = jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, inscripcion);
        Integer jugadores = jdbc.queryForObject("SELECT count(*) FROM nomina WHERE inscripcion_id = ?", Integer.class, inscripcion);
        if (confirmo) {
            assertThat(estado).isEqualTo("PENDIENTE_PAGO");
        }
        if (estado.equals("PENDIENTE_PAGO")) {
            assertThat(jugadores).isGreaterThanOrEqualTo(2);
        }
    }

    // Cancelar es tan corto que casi siempre commitea antes de que pagar lea, y una carrera con barrera no falla
    // siempre sin el bloqueo. Así que acá el test tiene el bloqueo del torneo: cancelar espera, un pago
    // "comitea" CONFIRMADA mientras tanto, y al soltar cancelar tiene que ver ese estado y no pisarlo.
    @RepeatedTest(5)
    void cancelarQueEsperabaElBloqueoVeElPagoQueSeConfirmoMientrasTanto() throws Exception {
        Long organizador = usuario("org_canc_pago");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long jugador = usuario("j_canc_pago");
        long inscripcion = inscripcion(jugador, "PENDIENTE_PAGO");

        ExecutorService hilos = Executors.newSingleThreadExecutor();
        try {
            Future<?> cancela = new TransactionTemplate(transacciones).execute(tx -> {
                jdbc.queryForList("SELECT id FROM torneo WHERE id = ? FOR UPDATE", torneo);
                Future<?> cancelacion = hilos.submit(() -> inscripcionService.cancelar(jugador, inscripcion));
                esperarQueSeBloquee(cancelacion);
                jdbc.update("UPDATE inscripcion SET estado = 'CONFIRMADA' WHERE id = ?", inscripcion);
                return cancelacion;
            });

            assertThatThrownBy(() -> cancela.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.INSCRIPCION_NO_EDITABLE));
        } finally {
            hilos.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, inscripcion))
                .isEqualTo("CONFIRMADA");
    }

    // Sin el bloqueo, la operación termina sola enseguida y el test falla en la aserción siguiente; con él,
    // queda esperando y sale por tiempo.
    private void esperarQueSeBloquee(Future<?> operacion) {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!operacion.isDone() && System.nanoTime() < limite
                && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class) == 0) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // el estado con que terminó la operación, o nulo si la rechazaron (y entonces suma el código a rechazos)
    private EstadoInscripcion resultado(Future<EstadoInscripcion> operacion, List<CodigoError> rechazos) throws Exception {
        try {
            return operacion.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ApiException.class);
            rechazos.add(((ApiException) e.getCause()).getCodigo());
            return null;
        }
    }

    @RepeatedTest(5)
    void reabrirYPagarALaVezNuncaDejanUnaInscripcionCobradaYEnBorrador() throws Exception {
        Long organizador = usuario("org_reab");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        Long jugador = usuario("j_reab");
        long inscripcion = inscripcion(jugador, "PENDIENTE_PAGO");

        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<EstadoInscripcion> pago = hilos.submit(pago(salida, jugador, inscripcion));
            Future<EstadoInscripcion> reapertura = hilos.submit(reapertura(salida, jugador, inscripcion));

            List<EstadoInscripcion> exitos = new ArrayList<>();
            List<CodigoError> rechazos = new ArrayList<>();
            for (Future<EstadoInscripcion> operacion : List.of(pago, reapertura)) {
                try {
                    exitos.add(operacion.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                }
            }

            // uno gana y el otro ve lo que dejó el primero
            assertThat(exitos).hasSize(1);
            assertThat(rechazos).containsExactly(CodigoError.INSCRIPCION_NO_EDITABLE);
        } finally {
            hilos.shutdownNow();
        }
        String estado = jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, inscripcion);
        Integer cobros = jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, inscripcion);
        assertThat(estado.equals("BORRADOR") && cobros > 0).as("borrador con un cobro: estado %s, cobros %s", estado, cobros).isFalse();
        assertThat(estado.equals("CONFIRMADA")).isEqualTo(cobros == 1);
    }

    private Callable<EstadoInscripcion> reapertura(CyclicBarrier salida, Long usuario, long inscripcion) {
        return () -> {
            salida.await();
            return inscripcionService.reabrir(usuario, inscripcion).estado();
        };
    }

    private Callable<EstadoInscripcion> confirmacion(CyclicBarrier salida, Long capitan, long inscripcion) {
        return () -> {
            salida.await();
            return inscripcionService.confirmar(capitan, inscripcion, true).estado();
        };
    }

    private Callable<InscripcionDetalle> reemplazo(CyclicBarrier salida, Long capitan, long inscripcion,
            Long sale, Long entra) {
        return () -> {
            salida.await();
            return inscripcionService.reemplazar(capitan, inscripcion, new ReemplazoRequest(sale, entra));
        };
    }

    private long inscripcionConfirmadaDeEquipo(Long capitan, Long jugador, String nombre) {
        long equipo = jdbc.queryForObject("""
                WITH e AS (INSERT INTO equipo (capitan_id, nombre) VALUES (?, ?) RETURNING id),
                     i AS (INSERT INTO usuario_equipo (usuario_id, equipo_id)
                           SELECT u, e.id FROM e, (VALUES (CAST(? AS BIGINT)), (CAST(? AS BIGINT))) AS m (u))
                SELECT id FROM e
                """, Long.class, capitan, "Equipo reemplazo " + nombre, capitan, jugador);
        long inscripcion = jdbc.queryForObject(
                "INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en) VALUES (?, ?, 'CONFIRMADA', now()) RETURNING id",
                Long.class, torneo, equipo);
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?), (?, ?, ?)",
                inscripcion, capitan, equipo, inscripcion, jugador, equipo);
        return inscripcion;
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

    // la auditoría no se borra y guarda a quien cerró a mano: este organizador queda en la base con un nombre único
    private Long organizadorQueQuedaEnLaAuditoria() {
        String nombre = "org_cierre_" + UUID.randomUUID();
        return jdbc.queryForObject("""
                WITH p AS (INSERT INTO persona (nombres, apellidos) VALUES ('Org', 'Cierre') RETURNING id)
                INSERT INTO usuario (username, email, estado, email_verificado, persona_id)
                SELECT ?, ? || '@example.com', 'ACTIVO', true, id FROM p
                RETURNING id
                """, Long.class, nombre, nombre);
    }

    private static String sha256(String texto) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
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
