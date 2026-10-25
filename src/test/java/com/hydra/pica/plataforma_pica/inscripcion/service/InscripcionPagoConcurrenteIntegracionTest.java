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
            jdbc.update("DELETE FROM inscripcion WHERE torneo_id = ?", torneo);
            jdbc.update("DELETE FROM torneo WHERE id = ?", torneo);
        }
        for (Long usuario : usuarios) {
            jdbc.update("DELETE FROM billetera WHERE usuario_id = ?", usuario);
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
