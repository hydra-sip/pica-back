package com.hydra.pica.plataforma_pica;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * B1-3: con el perfil seed, Flyway corre el seed de desarrollo además de las migraciones. Al tener otro
 * perfil, Spring arma otro contexto con su propio Postgres, así el seed no ensucia los demás tests.
 */
@DataJpaTest
@ActiveProfiles("seed")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SeedDesarrolloTest {

    private static final String CONTEOS = """
            SELECT (SELECT count(*) FROM usuario) AS usuarios,
                   (SELECT count(*) FROM usuario_rol) AS roles,
                   (SELECT count(*) FROM equipo) AS equipos,
                   (SELECT count(*) FROM usuario_equipo) AS integrantes,
                   (SELECT count(*) FROM torneo) AS torneos,
                   (SELECT count(*) FROM inscripcion) AS inscripciones,
                   (SELECT count(*) FROM movimiento) AS movimientos,
                   (SELECT count(*) FROM partido) AS partidos,
                   (SELECT count(*) FROM juega) AS lados,
                   (SELECT count(*) FROM numero) AS numeros,
                   (SELECT count(*) FROM ticket) AS tickets
            """;

    @Autowired private JdbcTemplate jdbc;

    @Test
    void creaLosUsuariosDePruebaConSuRolYLaContrasenaDelReadme() {
        List<Map<String, Object>> usuarios = jdbc.queryForList("""
                SELECT u.username, r.nombre AS rol, u.password_hash, u.estado, u.email_verificado
                FROM usuario u
                JOIN usuario_rol ur ON ur.usuario_id = u.id
                JOIN rol r ON r.id = ur.rol_id
                WHERE u.username LIKE 'dev\\_%'
                ORDER BY u.username
                """);

        assertThat(usuarios).extracting(u -> u.get("username") + ":" + u.get("rol")).containsExactly(
                "dev_arbitro:ARBITRO",
                "dev_jugador1:PARTICIPANTE", "dev_jugador2:PARTICIPANTE", "dev_jugador3:PARTICIPANTE",
                "dev_jugador4:PARTICIPANTE", "dev_jugador5:PARTICIPANTE", "dev_jugador6:PARTICIPANTE",
                "dev_organizador:ORGANIZADOR");
        assertThat(usuarios).allSatisfy(u -> {
            assertThat(u.get("estado")).isEqualTo("ACTIVO");
            assertThat(u.get("email_verificado")).isEqualTo(true);
            assertThat(new BCryptPasswordEncoder().matches("PicaDev2026", (String) u.get("password_hash"))).isTrue();
        });
    }

    @Test
    void creaUnTorneoPublicadoConSuRifaEnVentaYUnoCerradoConPartidos() {
        assertThat(jdbc.queryForList("SELECT nombre FROM equipo ORDER BY nombre", String.class))
                .containsExactly("Las Panteras", "Los Halcones");

        assertThat(jdbc.queryForMap("""
                SELECT t.estado, t.tipo_participante, count(i.id) AS inscriptos
                FROM torneo t JOIN inscripcion i ON i.torneo_id = t.id AND i.estado = 'CONFIRMADA'
                WHERE t.slug = 'dev-copa-abierta' GROUP BY t.id
                """)).containsEntry("estado", "INSCRIPCION_ABIERTA").containsEntry("tipo_participante", "EQUIPO")
                .containsEntry("inscriptos", 1L);
        assertThat(jdbc.queryForMap("""
                SELECT r.estado, count(n.numero) FILTER (WHERE n.estado = 'VENDIDO') AS vendidos, count(n.numero) AS total
                FROM rifa r JOIN torneo t ON t.id = r.torneo_id JOIN numero n ON n.rifa_id = r.id
                WHERE t.slug = 'dev-copa-abierta' GROUP BY r.id
                """)).containsEntry("estado", "EN_VENTA").containsEntry("vendidos", 3L).containsEntry("total", 100L);

        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE slug = 'dev-torneo-cerrado'", String.class))
                .isEqualTo("INSCRIPCION_CERRADA");
        assertThat(jdbc.queryForList("""
                SELECT p.ronda || '-' || p.posicion || ':' || count(j.inscripcion_id)
                FROM partido p JOIN torneo t ON t.id = p.torneo_id LEFT JOIN juega j ON j.partido_id = p.id
                WHERE t.slug = 'dev-torneo-cerrado' GROUP BY p.id ORDER BY p.ronda, p.posicion
                """, String.class)).containsExactly("1-1:2", "1-2:2", "2-1:0");
    }

    @Test
    void cadaInscripcionPagadaTieneSuMovimientoConfirmado() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inscripcion i
                WHERE i.estado = 'CONFIRMADA'
                  AND NOT EXISTS (SELECT 1 FROM movimiento m
                                  WHERE m.inscripcion_id = i.id AND m.tipo = 'INSCRIPCION' AND m.estado = 'CONFIRMADO')
                """, Integer.class)).isZero();
    }

    @Test
    void sePuedeCorrerDosVecesSinDuplicarNada() {
        Map<String, Object> antes = jdbc.queryForMap(CONTEOS);

        new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_desarrollo.sql")).execute(jdbc.getDataSource());
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThat(jdbc.queryForMap(CONTEOS)).isEqualTo(antes);
    }
}
