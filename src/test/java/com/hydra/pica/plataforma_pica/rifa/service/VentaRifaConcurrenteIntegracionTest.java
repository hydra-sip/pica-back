package com.hydra.pica.plataforma_pica.rifa.service;

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
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
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
 * Pedidos simultáneos sobre los mismos números (PICA-222, prueba de aceptación de HU-403, y RNF-CON-01), contra Postgres y sin la
 * transacción del test: cada hilo corre la suya, así que lo que se prueba es la PK del número al reservar y el
 * bloqueo de las filas al comprar. Como lo que se escribe queda confirmado, el escenario se borra al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class VentaRifaConcurrenteIntegracionTest {

    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> usuarios = new ArrayList<>();
    private Long rifa;

    @BeforeEach
    void rifaEnVenta() {
        Long organizador = usuario("org_venta_conc");
        rifa = jdbc.queryForObject("""
                INSERT INTO rifa (organizador_id, titulo, numero_desde, numero_hasta, precio_numero, venta_desde,
                                  venta_hasta, sorteada_en, terminos_aceptados_en, loteria_declarada_en,
                                  pct_gastos_administrativos, estado)
                VALUES (?, 'Rifa', 1, 100, 500, now() - interval '1 hour', now() + interval '7 days',
                        now() + interval '8 days', now(), now(), 10, 'EN_VENTA')
                RETURNING id
                """, Long.class, organizador);
    }

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM numero WHERE rifa_id = ?", rifa);
        jdbc.update("DELETE FROM ticket WHERE rifa_id = ?", rifa);
        jdbc.update("DELETE FROM rifa WHERE id = ?", rifa);
        for (Long usuario : usuarios) {
            jdbc.update("DELETE FROM movimiento WHERE billetera_id IN (SELECT id FROM billetera WHERE usuario_id = ?)", usuario);
            jdbc.update("DELETE FROM billetera WHERE usuario_id = ?", usuario);
            Long persona = jdbc.queryForObject("SELECT persona_id FROM usuario WHERE id = ?", Long.class, usuario);
            jdbc.update("DELETE FROM usuario WHERE id = ?", usuario);
            jdbc.update("DELETE FROM persona WHERE id = ?", persona);
        }
        usuarios.clear();
    }

    @RepeatedTest(5)
    void dosReservasDelMismoNumeroDejanUnaYLaOtraRecibeNumeroNoDisponible() throws Exception {
        Long a = usuario("a_venta_conc");
        Long b = usuario("b_venta_conc");

        List<Object> resultados = aLaVez(
                () -> ventaRifaService.reservar(a, rifa, List.of(50)),
                () -> ventaRifaService.reservar(b, rifa, List.of(50)));

        assertThat(resultados).filteredOn(r -> !(r instanceof CodigoError)).hasSize(1);
        assertThat(resultados).filteredOn(r -> r instanceof CodigoError).containsExactly(CodigoError.NUMERO_NO_DISPONIBLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM numero WHERE rifa_id = ?", Integer.class, rifa)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void dosComprasDelMismoNumeroConcretanUnaYLaOtraRecibeNumeroNoDisponible() throws Exception {
        Long a = usuario("a_venta_conc");
        ventaRifaService.reservar(a, rifa, List.of(60));

        // dos pedidos distintos sobre la misma reserva: el segundo espera el bloqueo y encuentra el número vendido
        List<Object> resultados = aLaVez(
                () -> ventaRifaService.comprar(a, rifa, List.of(60), "compra-a"),
                () -> ventaRifaService.comprar(a, rifa, List.of(60), "compra-b"));

        assertThat(resultados).filteredOn(r -> r instanceof VentaRifaResultado).hasSize(1);
        assertThat(resultados).filteredOn(r -> r instanceof CodigoError).containsExactly(CodigoError.NUMERO_NO_DISPONIBLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE rifa_id = ?", Integer.class, rifa)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void laMismaCompraEnviadaDosVecesALaVezCobraUnaSolaVez() throws Exception {
        Long a = usuario("a_venta_conc");
        ventaRifaService.reservar(a, rifa, List.of(70));

        List<Object> resultados = aLaVez(
                () -> ventaRifaService.comprar(a, rifa, List.of(70), "doble-clic"),
                () -> ventaRifaService.comprar(a, rifa, List.of(70), "doble-clic"));

        assertThat(resultados).allMatch(r -> r instanceof VentaRifaResultado);
        assertThat(resultados).extracting(r -> ((VentaRifaResultado) r).ticketId()).containsOnly(
                ((VentaRifaResultado) resultados.get(0)).ticketId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE referencia_externa LIKE '%doble-clic'",
                Integer.class)).isEqualTo(1);
    }

    // RNF-CON-01: cincuenta compradores van a la vez por el mismo número y solo uno se lo lleva
    @Test
    void cincuentaComprasDelMismoNumeroConcretanUnaSolaYLasDemasRecibenNumeroNoDisponible() throws Exception {
        List<Callable<?>> compras = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Long comprador = usuario("c_venta_conc");
            String clave = "conc50-" + i;
            compras.add(() -> {
                ventaRifaService.reservar(comprador, rifa, List.of(80));
                return ventaRifaService.comprar(comprador, rifa, List.of(80), clave);
            });
        }

        List<Object> resultados = aLaVez(compras.toArray(new Callable<?>[0]));

        assertThat(resultados).filteredOn(r -> r instanceof VentaRifaResultado).hasSize(1);
        assertThat(resultados).filteredOn(r -> r instanceof CodigoError).hasSize(49)
                .containsOnly(CodigoError.NUMERO_NO_DISPONIBLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE rifa_id = ?", Integer.class, rifa)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE referencia_externa LIKE '%conc50-%'",
                Integer.class)).isEqualTo(1);
    }

    /** Corre los pedidos a la vez y devuelve lo que respondió cada uno, o el código del error. */
    private static List<Object> aLaVez(Callable<?>... pedidos) throws Exception {
        CyclicBarrier salida = new CyclicBarrier(pedidos.length);
        ExecutorService hilos = Executors.newFixedThreadPool(pedidos.length);
        try {
            List<Future<?>> enviados = new ArrayList<>();
            for (Callable<?> pedido : pedidos) {
                enviados.add(hilos.submit(() -> {
                    salida.await();
                    return pedido.call();
                }));
            }
            List<Object> resultados = new ArrayList<>();
            for (Future<?> pedido : enviados) {
                try {
                    resultados.add(pedido.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    resultados.add(((ApiException) e.getCause()).getCodigo());
                }
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }

    private Long usuario(String nombre) {
        String username = nombre + "_" + usuarios.size();
        Long persona = jdbc.queryForObject(
                "INSERT INTO persona (nombres, apellidos, estado) VALUES (?, 'Test', 'ACTIVO') RETURNING id",
                Long.class, username);
        Long usuario = jdbc.queryForObject("""
                INSERT INTO usuario (username, email, estado, email_verificado, persona_id)
                VALUES (?, ?, 'ACTIVO', true, ?)
                RETURNING id
                """, Long.class, username, username + "@example.com", persona);
        usuarios.add(usuario);
        return usuario;
    }
}
