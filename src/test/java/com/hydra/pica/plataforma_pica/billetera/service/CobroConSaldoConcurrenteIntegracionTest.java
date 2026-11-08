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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RNF-CON-01 en el cobro con saldo (HU-603): pagos simultáneos de la misma billetera, cada uno en su transacción y
 * sin la del test. Como lo que se escribe queda confirmado, el escenario se borra al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class CobroConSaldoConcurrenteIntegracionTest {

    private static final BigDecimal MIL = new BigDecimal("1000.00");

    @Autowired private CobroService cobroService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long persona;
    private Long jugador;

    @BeforeEach
    void jugadorConTresMil() {
        Persona p = new Persona();
        p.setNombres("Concurrente");
        p.setApellidos("Test");
        p.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(p).getId();

        Usuario u = new Usuario();
        u.setUsername("conc603_" + System.nanoTime());
        u.setEmail(u.getUsername() + "@example.com");
        u.setEstado(EstadoUsuario.ACTIVO);
        u.setEmailVerificado(true);
        u.setPersona(personaRepository.getReferenceById(persona));
        jugador = usuarioRepository.save(u).getId();
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, 3000.00)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = 3000.00
                """, jugador);
    }

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM movimiento WHERE billetera_id IN (SELECT id FROM billetera WHERE usuario_id = ?)", jugador);
        jdbc.update("DELETE FROM billetera WHERE usuario_id = ?", jugador);
        jdbc.update("DELETE FROM usuario WHERE id = ?", jugador);
        jdbc.update("DELETE FROM persona WHERE id = ?", persona);
    }

    // diez pagos de 1000 a la vez con 3000 de saldo: pasan tres y la billetera nunca queda en negativo
    @RepeatedTest(3)
    void pagosSimultaneosNuncaDejanLaBilleteraEnNegativo() throws Exception {
        int pedidos = 10;
        CyclicBarrier salida = new CyclicBarrier(pedidos);
        List<Callable<Movimiento>> pagos = new ArrayList<>();
        for (int i = 0; i < pedidos; i++) {
            pagos.add(pago(salida, "conc-" + i));
        }

        List<Movimiento> cobrados = new ArrayList<>();
        List<CodigoError> rechazos = new ArrayList<>();
        correr(pagos, cobrados, rechazos);

        assertThat(cobrados).hasSize(3);
        assertThat(rechazos).hasSize(7).containsOnly(CodigoError.SALDO_INSUFICIENTE);
        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido FROM billetera WHERE usuario_id = ?", jugador);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo("3000.00");
        // cada movimiento deja el saldo que quedó después de él: 2000, 1000 y 0, sin repetir
        assertThat(jdbc.queryForList("""
                SELECT m.saldo_resultante FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = ? ORDER BY m.saldo_resultante DESC
                """, BigDecimal.class, jugador))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2000"), new BigDecimal("1000"), BigDecimal.ZERO);
    }

    @Test
    void reintentosSimultaneosConLaMismaClaveCobranUnaSolaVez() throws Exception {
        int pedidos = 5;
        CyclicBarrier salida = new CyclicBarrier(pedidos);
        List<Callable<Movimiento>> pagos = new ArrayList<>();
        for (int i = 0; i < pedidos; i++) {
            pagos.add(pago(salida, "misma-clave"));
        }

        List<Movimiento> cobrados = new ArrayList<>();
        correr(pagos, cobrados, new ArrayList<>());

        assertThat(cobrados).hasSize(pedidos).extracting(Movimiento::getId).containsOnly(cobrados.get(0).getId());
        assertThat(jdbc.queryForObject("SELECT saldo_disponible FROM billetera WHERE usuario_id = ?",
                BigDecimal.class, jugador)).isEqualByComparingTo("2000.00");
    }

    private Callable<Movimiento> pago(CyclicBarrier salida, String clave) {
        return () -> {
            salida.await(10, TimeUnit.SECONDS);
            return cobroService.cobrar(new SolicitudCobro(jugador, TipoMovimiento.COMPRA_NUMERO, MIL, null, null,
                    clave, "Compra simultánea"));
        };
    }

    private static void correr(List<Callable<Movimiento>> pagos, List<Movimiento> cobrados, List<CodigoError> rechazos)
            throws InterruptedException {
        ExecutorService hilos = Executors.newFixedThreadPool(pagos.size());
        try {
            List<Future<Movimiento>> resultados = new ArrayList<>();
            for (Callable<Movimiento> pago : pagos) {
                resultados.add(hilos.submit(pago));
            }
            for (Future<Movimiento> resultado : resultados) {
                try {
                    cobrados.add(resultado.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    rechazos.add(((ApiException) e.getCause()).getCodigo());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("Un pago no terminó", e);
                }
            }
        } finally {
            hilos.shutdownNow();
        }
    }
}
