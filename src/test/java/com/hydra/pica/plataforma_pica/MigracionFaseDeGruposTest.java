package com.hydra.pica.plataforma_pica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V54 (CR-02, PICA-396): las reglas de la fase de grupos que quedan en la base. Como en
 * {@link MigracionModeloCorregidoTest}, cada test termina con la sentencia que la base tiene que rechazar.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class MigracionFaseDeGruposTest {

    @Autowired private JdbcTemplate jdbc;

    private long organizador;
    private long arbitro;

    @BeforeEach
    void usuarios() {
        organizador = usuario("organizador");
        arbitro = usuario("arbitro");
    }

    @Test
    void unPartidoSinFaseEsDeLaLlaveYUnTorneoSinReglasEsDeEliminacionDirecta() {
        long torneo = torneo("ELIMINACION_DIRECTA", null, null, null, null);
        long partido = partido(torneo, "ELIMINACION", null, 1, 1);

        assertThat(jdbc.queryForObject("SELECT fase FROM partido WHERE id = ?", String.class, partido))
                .isEqualTo("ELIMINACION");
        assertThat(jdbc.queryForObject("SELECT tamanio_grupo FROM torneo WHERE id = ?", Integer.class, torneo))
                .isNull();
    }

    @Test
    void conGruposLasReglasSonObligatorias() {
        assertThatThrownBy(() -> torneo("GRUPOS_Y_ELIMINACION", null, null, null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_torneo_reglas_grupos");
    }

    @Test
    void enEliminacionDirectaNoHayReglasDeGrupos() {
        assertThatThrownBy(() -> torneo("ELIMINACION_DIRECTA", 4, 2, 3, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_torneo_reglas_grupos");
    }

    @Test
    void empatarDaMenosQueGanar() {
        assertThatThrownBy(() -> torneo("GRUPOS_Y_ELIMINACION", 4, 2, 3, 3))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_torneo_reglas_grupos");
    }

    @Test
    void pasanMenosQueLosDelGrupo() {
        assertThatThrownBy(() -> torneo("GRUPOS_Y_ELIMINACION", 4, 4, 3, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_torneo_reglas_grupos");
    }

    @Test
    void unPartidoDeGruposNecesitaSuGrupo() {
        long torneo = conGrupos();

        assertThatThrownBy(() -> partido(torneo, "GRUPOS", null, 1, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_partido_fase_grupo");
    }

    @Test
    void unPartidoDeLaLlaveNoEsDeUnGrupo() {
        long torneo = conGrupos();
        long grupo = grupo(torneo, "A");

        assertThatThrownBy(() -> partido(torneo, "ELIMINACION", grupo, 1, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_partido_fase_grupo");
    }

    @Test
    void elGrupoDelPartidoEsDelMismoTorneo() {
        long torneo = conGrupos();
        long otro = conGrupos();
        long grupoDelOtro = grupo(otro, "A");

        assertThatThrownBy(() -> partido(torneo, "GRUPOS", grupoDelOtro, 1, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_partido_grupo");
    }

    @Test
    void cadaGrupoTieneSusFechasYLaLlaveSigueSiendoUna() {
        long torneo = conGrupos();
        long a = grupo(torneo, "A");
        long b = grupo(torneo, "B");

        assertThatCode(() -> {
            partido(torneo, "GRUPOS", a, 1, 1);
            partido(torneo, "GRUPOS", b, 1, 1);
            partido(torneo, "DESEMPATE", a, 1, 1);
            partido(torneo, "ELIMINACION", null, 1, 1);
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> partido(torneo, "ELIMINACION", null, 1, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_partido_llave");
    }

    @Test
    void unaFechaDeUnGrupoNoRepitePosicion() {
        long torneo = conGrupos();
        long a = grupo(torneo, "A");
        partido(torneo, "GRUPOS", a, 1, 1);

        assertThatThrownBy(() -> partido(torneo, "GRUPOS", a, 1, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_partido_grupo");
    }

    @Test
    void unaLetraPorTorneo() {
        long torneo = conGrupos();
        grupo(torneo, "A");

        assertThatThrownBy(() -> grupo(torneo, "A"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_grupo_torneo_letra");
    }

    @Test
    void unaInscripcionVaEnUnSoloGrupo() {
        long torneo = conGrupos();
        long a = grupo(torneo, "A");
        long b = grupo(torneo, "B");
        long inscripcion = inscribir(torneo, usuario("jugador"));
        integrar(a, inscripcion, torneo);

        assertThatThrownBy(() -> integrar(b, inscripcion, torneo))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_grupo_inscripcion_inscripcion");
    }

    @Test
    void laInscripcionDelGrupoEsDelMismoTorneo() {
        long torneo = conGrupos();
        long otro = conGrupos();
        long a = grupo(torneo, "A");
        long deOtro = inscribir(otro, usuario("jugador"));

        assertThatThrownBy(() -> integrar(a, deOtro, torneo))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_grupo_inscripcion_inscripcion");
    }

    // --- datos ----------------------------------------------------------------

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long usuario(String nombre) {
        long persona = id("INSERT INTO persona (nombres, apellidos) VALUES (?, 'Test') RETURNING id", nombre);
        return id("INSERT INTO usuario (username, email, persona_id) VALUES (?, ?, ?) RETURNING id",
                "v54_" + nombre + persona, "v54_" + nombre + persona + "@example.com", persona);
    }

    private long conGrupos() {
        return torneo("GRUPOS_Y_ELIMINACION", 4, 2, 3, 1);
    }

    private long torneo(String formato, Integer tamanio, Integer clasifican, Integer victoria, Integer empate) {
        long torneo = id("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio,
                                    tamanio_grupo, clasifican_por_grupo, puntos_victoria, puntos_empate)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', ?, 'ONLINE', 'INDIVIDUAL', 8, 6, 1000, 50, 10, now(),
                        now() + interval '1 day', ?, ?, ?, ?)
                RETURNING id
                """, organizador, formato, tamanio, clasifican, victoria, empate);
        jdbc.update("INSERT INTO torneo_arbitro (torneo_id, usuario_id) VALUES (?, ?)", torneo, arbitro);
        return torneo;
    }

    private long grupo(long torneo, String letra) {
        return id("INSERT INTO grupo (torneo_id, letra) VALUES (?, ?) RETURNING id", torneo, letra);
    }

    private long inscribir(long torneo, long usuario) {
        return id("INSERT INTO inscripcion (torneo_id, usuario_id, reglamento_aceptado_en) VALUES (?, ?, now()) RETURNING id",
                torneo, usuario);
    }

    private void integrar(long grupo, long inscripcion, long torneo) {
        jdbc.update("INSERT INTO grupo_inscripcion (grupo_id, inscripcion_id, torneo_id) VALUES (?, ?, ?)",
                grupo, inscripcion, torneo);
    }

    private long partido(long torneo, String fase, Long grupo, int ronda, int posicion) {
        return id("""
                INSERT INTO partido (torneo_id, arbitro_id, fase, grupo_id, ronda, posicion)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, torneo, arbitro, fase, grupo, ronda, posicion);
    }
}
