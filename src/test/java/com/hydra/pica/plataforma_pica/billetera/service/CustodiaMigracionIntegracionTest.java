package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * V39 pasa a la custodia los pagos de antes. Acá se arman como los dejaba el cobro simulado (sin custodia: es el modo
 * de los tests) y se les corre la parte de V39 que los completa. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class CustodiaMigracionIntegracionTest {

    @Autowired private InscripcionService inscripcionService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void lasInscripcionesYLasComprasDeAntesEntranALaCustodiaDeSuEvento() throws IOException {
        Long organizador = usuario("org_v39");
        Long jugador = usuario("jug_v39");
        long torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo V39', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        long inscripcion = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, torneo, jugador);
        inscripcionService.pagar(jugador, inscripcion);
        long rifa = rifaDelTorneo(organizador, torneo);
        ventaRifaService.reservar(jugador, rifa, List.of(3));
        ventaRifaService.comprar(jugador, rifa, List.of(3), "compra-v39");
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE custodia_id IS NOT NULL", Integer.class))
                .isZero();

        jdbc.execute(backfillDeV39());

        assertThat(jdbc.queryForList("""
                SELECT m.tipo || ':' || m.monto FROM movimiento m JOIN custodia c ON c.id = m.custodia_id
                WHERE c.torneo_id = ? AND c.estado = 'RETENIDA' ORDER BY m.id
                """, String.class, torneo)).containsExactly("INSCRIPCION:1000.00", "COMISION:100.00");
        // la compra va a la custodia de la rifa, no a la del torneo, aunque el movimiento tenga el torneo
        assertThat(jdbc.queryForList("""
                SELECT m.tipo || ':' || m.monto FROM movimiento m JOIN custodia c ON c.id = m.custodia_id
                WHERE c.rifa_id = ? ORDER BY m.id
                """, String.class, rifa)).containsExactly("COMPRA_NUMERO:500.00", "COMISION:50.00");
    }

    /** Los INSERT y UPDATE de V39 que completan los pagos de antes, sin crear la tabla. */
    private static String backfillDeV39() throws IOException {
        String v39 = new ClassPathResource("db/migration/V39__custodia.sql").getContentAsString(StandardCharsets.UTF_8);
        return v39.substring(v39.indexOf("-- Los pagos de antes"));
    }

    private long rifaDelTorneo(Long organizador, long torneo) {
        Instant maniana = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant enUnaSemana = Instant.now().plus(7, ChronoUnit.DAYS);
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa V39", null, 1, 100,
                new BigDecimal("500"), maniana, enUnaSemana, enUnaSemana.plus(1, ChronoUnit.DAYS), null, null));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(new PremioRifaRequest(new BigDecimal("100"), null)));
        rifaService.publicar(organizador, creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour', torneo_id = ? WHERE id = ?", torneo,
                creada.id());
        entityManager.clear();
        return creada.id();
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
