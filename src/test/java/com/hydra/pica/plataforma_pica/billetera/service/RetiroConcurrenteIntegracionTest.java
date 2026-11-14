package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Concurrencia en retiro de fondos (HU-607, PICA-337): retiros simultáneos de la misma billetera,
 * cada uno en su transacción independiente. Demuestra que dos o más retiros concurrentes nunca
 * dejan la billetera en saldo negativo.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class RetiroConcurrenteIntegracionTest {

    private static final BigDecimal MIL = new BigDecimal("1000.00");

    @Autowired private RetiroService retiroService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long usuarioId;
    private Long cuentaId;

    @BeforeEach
    void usuarioConTresMil() {
        Persona p = new Persona();
        p.setNombres("Usuario");
        p.setApellidos("Retiro");
        p.setEstado(EstadoGeneral.ACTIVO);
        p.setNroDoc("35123456");
        Long persona = personaRepository.save(p).getId();

        Usuario u = new Usuario();
        u.setUsername("retiro607_" + System.nanoTime());
        u.setEmail(u.getUsername() + "@example.com");
        u.setEstado(EstadoUsuario.ACTIVO);
        u.setEmailVerificado(true);
        u.setPersona(personaRepository.getReferenceById(persona));
        usuarioId = usuarioRepository.save(u).getId();

        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible, saldo_retenido, ganancia_acumulada)
                VALUES (?, 3000.00, 0.00, 0.00)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = 3000.00, saldo_retenido = 0.00
                """, usuarioId);

        cuentaId = jdbc.queryForObject("""
                INSERT INTO cuenta_bancaria (usuario_id, tipo, valor, titular, cuit_cuil, estado_validacion, validada_en)
                VALUES (?, 'CBU', '2850590940090418135201', 'Juan Perez', '20351234569', 'VALIDADA', now())
                RETURNING id
                """, Long.class, usuarioId);
    }

    @RepeatedTest(3)
    void retirosSimultaneosNuncaDejanLaBilleteraEnNegativo() throws Exception {
        int pedidos = 5;
        CyclicBarrier salida = new CyclicBarrier(pedidos);
        List<Callable<Movimiento>> retiros = new ArrayList<>();
        for (int i = 0; i < pedidos; i++) {
            retiros.add(retiro(salida));
        }

        List<Movimiento> confirmados = new ArrayList<>();
        List<CodigoError> rechazos = new ArrayList<>();
        correr(retiros, confirmados, rechazos);

        assertThat(confirmados).hasSize(3);
        assertThat(rechazos).hasSize(2).containsOnly(CodigoError.SALDO_INSUFICIENTE);

        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido FROM billetera WHERE usuario_id = ?", usuarioId);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(confirmados).allSatisfy(mov -> {
            assertThat(mov.getTipo()).isEqualTo(TipoMovimiento.RETIRO);
            assertThat(mov.getEstado()).isEqualTo(EstadoMovimiento.PENDIENTE);
            assertThat(mov.getMonto()).isEqualByComparingTo(MIL);
        });

        assertThat(jdbc.queryForList("""
                SELECT m.saldo_resultante FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = ? AND m.tipo = 'RETIRO' ORDER BY m.saldo_resultante DESC
                """, BigDecimal.class, usuarioId))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO);
    }

    private Callable<Movimiento> retiro(CyclicBarrier salida) {
        return () -> {
            salida.await(10, TimeUnit.SECONDS);
            return retiroService.solicitar(usuarioId, cuentaId, MIL);
        };
    }

    private static void correr(List<Callable<Movimiento>> retiros, List<Movimiento> confirmados, List<CodigoError> rechazos)
            throws InterruptedException {
        ExecutorService hilos = Executors.newFixedThreadPool(retiros.size());
        try {
            List<Future<Movimiento>> resultados = new ArrayList<>();
            for (Callable<Movimiento> retiro : retiros) {
                resultados.add(hilos.submit(retiro));
            }
            for (Future<Movimiento> resultado : resultados) {
                try {
                    confirmados.add(resultado.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("Un retiro no terminó", e);
                }
            }
        } finally {
            hilos.shutdownNow();
        }
    }
}

