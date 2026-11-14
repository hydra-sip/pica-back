package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.service.ControlDeBalance.DiferenciaBilletera;
import com.hydra.pica.plataforma_pica.billetera.service.ControlDeBalance.DiferenciaCustodia;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador.Retencion;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El control de balance cero (RNF-CON-02). Dos jugadores cargan $5000 y pagan con saldo un torneo de $1000 más 10 %; el
 * 1.º se lleva todo el pozo. Lo que pasa por el sistema cierra, y una diferencia forzada a mano aparece. Cada test hace
 * rollback.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ControlDeBalanceIntegracionTest {

    @Autowired private ControlDeBalance control;
    @Autowired private CargaService cargaService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private LiquidacionService liquidacionService;
    @Autowired private Reintegrador reintegrador;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private final Long[] jugador = new Long[2];
    private final long[] inscripcion = new long[2];
    private long torneo;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_balance");
        torneo = torneo();
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 100)", torneo);
        for (int i = 0; i < 2; i++) {
            jugador[i] = usuario("jug_balance_" + i);
            cargaService.acreditar(jugador[i], new BigDecimal("5000"), "carga-balance-" + jugador[i], "Carga");
            inscripcion[i] = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                    VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                    """, Long.class, torneo, jugador[i]);
            inscripcionService.pagar(jugador[i], inscripcion[i]);
        }
        entityManager.flush();
    }

    // --- lo que hace el sistema cierra --------------------------------------------------------------

    @Test
    void conLaPlataEnCustodiaCierra() {
        assertThat(billeteras()).isEmpty();
        assertThat(custodias()).isEmpty();
    }

    @Test
    void despuesDeLiquidarCierra() {
        liquidar();

        assertThat(billeteras()).isEmpty();
        assertThat(custodias()).isEmpty();
    }

    @Test
    void despuesDeCancelarConGastosAprobadosCierra() {
        jdbc.update("UPDATE torneo SET estado = 'CANCELADO', motivo_cancelacion = 'Lluvia' WHERE id = ?", torneo);
        entityManager.clear();

        reintegrador.reintegrarTorneo(torneo, "Torneo del balance", new Retencion(organizador, new BigDecimal("300")));
        entityManager.flush();

        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo))
                .isEqualTo("REINTEGRADA");
        assertThat(billeteras()).isEmpty();
        assertThat(custodias()).isEmpty();
    }

    // --- una diferencia forzada aparece -------------------------------------------------------------

    @Test
    void unPesoDeMasEnElDisponibleSeDetecta() {
        jdbc.update("UPDATE billetera SET saldo_disponible = saldo_disponible + 1 WHERE usuario_id = ?", jugador[0]);

        assertThat(billeteras()).singleElement().satisfies(d -> {
            assertThat(d.billeteraId()).isEqualTo(billeteraDe(jugador[0]));
            assertThat(d.disponible()).isEqualByComparingTo("3901.00");
            assertThat(d.disponibleEsperado()).isEqualByComparingTo("3900.00");
        });
    }

    @Test
    void unRetenidoQueNoEsLoQueEstaEnCustodiaSeDetecta() {
        jdbc.update("UPDATE billetera SET saldo_retenido = saldo_retenido - 100 WHERE usuario_id = ?", jugador[1]);

        assertThat(billeteras()).singleElement().satisfies(d -> {
            assertThat(d.billeteraId()).isEqualTo(billeteraDe(jugador[1]));
            assertThat(d.retenido()).isEqualByComparingTo("1000.00");
            assertThat(d.retenidoEsperado()).isEqualByComparingTo("1100.00");
        });
    }

    @Test
    void unaLiquidacionQueNoRepartioTodoLoRetenidoSeDetecta() {
        liquidar();
        jdbc.update("UPDATE movimiento SET monto = monto - 1 WHERE referencia_externa = ?",
                "liq-t" + torneo + "-premio-" + inscripcion[0]);

        assertThat(custodias()).singleElement().satisfies(d -> {
            assertThat(d.torneoId()).isEqualTo(torneo);
            assertThat(d.estado()).isEqualTo("LIBERADA");
            assertThat(d.entrado()).isEqualByComparingTo("2200.00");
            assertThat(d.repartido()).isEqualByComparingTo("2199.00");
        });
        // el ganador tiene un peso que su movimiento ya no explica
        assertThat(billeteras()).extracting(DiferenciaBilletera::billeteraId).containsExactly(billeteraDe(jugador[0]));
    }

    // --- ayudas -------------------------------------------------------------------------------------

    /** El 1.º se lleva los $2000 del pozo y la plataforma los $200 de gastos. */
    private void liquidar() {
        jdbc.update("UPDATE inscripcion SET posicion = ? WHERE id = ?", 1, inscripcion[0]);
        jdbc.update("UPDATE inscripcion SET posicion = ? WHERE id = ?", 2, inscripcion[1]);
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
        liquidacionService.liquidarTorneo(torneo);
        entityManager.flush();
    }

    /** Las del organizador, los jugadores y la plataforma. */
    private List<DiferenciaBilletera> billeteras() {
        entityManager.flush();
        return control.billeteras(jdbc.queryForList("""
                SELECT id FROM billetera WHERE usuario_id IN (?, ?, ?) OR usuario_id IS NULL
                """, Long.class, organizador, jugador[0], jugador[1]));
    }

    private List<DiferenciaCustodia> custodias() {
        entityManager.flush();
        return control.custodias(jdbc.queryForList("SELECT id FROM custodia WHERE torneo_id = ?", Long.class, torneo));
    }

    private Long billeteraDe(Long usuario) {
        return jdbc.queryForObject("SELECT id FROM billetera WHERE usuario_id = ?", Long.class, usuario);
    }

    private long torneo() {
        long id = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado,
                                    publicado_en)
                VALUES (?, 'Torneo del balance', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA',
                        now())
                RETURNING id
                """, Long.class, organizador);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "balance-" + id, id);
        return id;
    }

    private Long usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario).getId();
    }
}
