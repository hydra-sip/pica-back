package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.MiTorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.MisTorneos;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mis torneos (HU-201, PICA-201) contra Postgres: los borradores salen del asistente y los publicados se insertan
 * a mano, porque publicar es otra subtarea. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class MisTorneosServiceIntegracionTest {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);

    @Autowired private MisTorneosService misTorneosService;
    @Autowired private TorneoService torneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long otro;

    @BeforeEach
    void usuarios() {
        organizador = usuario("org_mis").getId();
        otro = usuario("otro_mis").getId();
    }

    @Test
    void listaLosPropiosConBorradoresSinLosDeOtroNiLosDadosDeBaja() {
        borrador(organizador, "Apertura F5", "Fútbol 5");
        publicado(organizador, "Copa abierta", "Fútbol 5", "INSCRIPCION_ABIERTA");
        borrador(otro, "Ajeno", "Fútbol 5");
        long baja = borrador(organizador, "Dado de baja", "Fútbol 5");
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", baja);

        assertThat(listar(null, null, null).content()).extracting(MiTorneoResumen::nombre)
                .containsExactlyInAnyOrder("Apertura F5", "Copa abierta");
    }

    @Test
    void elUltimoQueSeTocoVaPrimero() {
        long primero = borrador(organizador, "Primero", "Fútbol 5");
        borrador(organizador, "Segundo", "Fútbol 5");
        torneoService.actualizarInformacion(organizador, primero, request("Primero", "Fútbol 5"));

        assertThat(listar(null, null, null).content()).extracting(MiTorneoResumen::nombre)
                .containsExactly("Primero", "Segundo");
    }

    @Test
    void buscaPorNombreSinMayusculasNiTildesYConTodasLasPalabras() {
        borrador(organizador, "Copa Fútbol Otoño", "Fútbol 5");
        borrador(organizador, "Copa Ajedrez", "Ajedrez");

        assertThat(listar("futbol OTONO", null, null).content()).extracting(MiTorneoResumen::nombre)
                .containsExactly("Copa Fútbol Otoño");
        assertThat(listar("copa", null, null).content()).hasSize(2);
        assertThat(listar("100%", null, null).content()).isEmpty();
    }

    @Test
    void lasCantidadesPorEstadoAplicanLaDisciplinaPeroNoElEstado() {
        borrador(organizador, "Apertura F5", "Fútbol 5");
        publicado(organizador, "Copa F5", "Fútbol 5", "INSCRIPCION_ABIERTA");
        publicado(organizador, "Abierto", "Ajedrez", "INSCRIPCION_ABIERTA");

        MisTorneos mis = listar(null, EstadoTorneo.INSCRIPCION_ABIERTA, "FÚTBOL 5");

        assertThat(mis.content()).extracting(MiTorneoResumen::nombre).containsExactly("Copa F5");
        assertThat(mis.porEstado()).hasSize(EstadoTorneo.values().length)
                .containsEntry(EstadoTorneo.BORRADOR, 1L)
                .containsEntry(EstadoTorneo.INSCRIPCION_ABIERTA, 1L)
                .containsEntry(EstadoTorneo.FINALIZADO, 0L);
        assertThat(mis.disciplinas()).containsExactly("Ajedrez", "Fútbol 5");
    }

    @Test
    void publicadoCuentaSoloLasConfirmadasYElPozoEsReal() {
        long torneo = publicado(organizador, "Copa abierta", "Ajedrez", "INSCRIPCION_ABIERTA");
        inscribir(torneo, "CONFIRMADA");
        inscribir(torneo, "CONFIRMADA");
        inscribir(torneo, "PENDIENTE_PAGO");
        inscribir(torneo, "CANCELADA");

        MiTorneoResumen fila = listar(null, null, null).content().getFirst();

        assertThat(fila.inscriptos()).isEqualTo(2);
        assertThat(fila.cupoMaximo()).isEqualTo(8);
        // 1000 × 2 confirmadas: la inscripción va entera al pozo
        assertThat(fila.pozo()).isEqualByComparingTo("2000");
        assertThat(fila.pozoEstimado()).isFalse();
        assertThat(fila.pasoPendiente()).isNull();
    }

    @Test
    void enBorradorElPozoSeEstimaConElCupoYSinParteEconomicaNoHay() {
        long conEconomia = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5", "Fútbol 5",
                null, null, null, null, null, null, null, null, null, null, 16, 12, null, null)).id();
        jdbc.update("UPDATE torneo SET valor_inscripcion = 5000 WHERE id = ?", conEconomia);
        borrador(organizador, "Sin economía", "Fútbol 5");

        MisTorneos mis = listar(null, null, null);

        MiTorneoResumen estimado = fila(mis, "Apertura F5");
        // 5000 × 16 de cupo
        assertThat(estimado.pozo()).isEqualByComparingTo("80000");
        assertThat(estimado.pozoEstimado()).isTrue();
        assertThat(fila(mis, "Sin economía").pozo()).isNull();
    }

    @Test
    void elPasoPendienteDiceDondeReabrirElAsistente() {
        borrador(organizador, "Solo nombre", "Fútbol 5");
        torneoService.crear(organizador, pasoUno("Paso uno listo", null, null));
        torneoService.crear(organizador, pasoUno("Pasos uno y dos listos", 16, 12));

        MisTorneos mis = listar(null, null, null);

        assertThat(fila(mis, "Solo nombre").pasoPendiente()).isEqualTo(1);
        assertThat(fila(mis, "Paso uno listo").pasoPendiente()).isEqualTo(2);
        assertThat(fila(mis, "Pasos uno y dos listos").pasoPendiente()).isEqualTo(3);
    }

    @Test
    void paginaConLaMismaFormaQueLasDemas() {
        borrador(organizador, "Uno", "Fútbol 5");
        borrador(organizador, "Dos", "Fútbol 5");
        borrador(organizador, "Tres", "Fútbol 5");

        entityManager.flush();
        entityManager.clear();
        MisTorneos mis = misTorneosService.listar(organizador, null, null, null, PageRequest.of(0, 2));

        assertThat(mis.content()).hasSize(2);
        assertThat(mis.page().totalElements()).isEqualTo(3);
        assertThat(mis.page().totalPages()).isEqualTo(2);
        assertThat(mis.porEstado()).containsEntry(EstadoTorneo.BORRADOR, 3L);
    }

    // --- helpers --------------------------------------------------------------

    /** Lo insertado por JDBC no pasa por JPA: se limpia la memoria para leer de la base. */
    private MisTorneos listar(String q, EstadoTorneo estado, String disciplina) {
        entityManager.flush();
        entityManager.clear();
        return misTorneosService.listar(organizador, q, estado, disciplina, PageRequest.of(0, 20));
    }

    private static MiTorneoResumen fila(MisTorneos mis, String nombre) {
        return mis.content().stream().filter(f -> f.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private long borrador(Long organizadorId, String nombre, String disciplina) {
        return torneoService.crear(organizadorId, request(nombre, disciplina)).id();
    }

    private static InformacionTorneoRequest request(String nombre, String disciplina) {
        return new InformacionTorneoRequest(nombre, disciplina, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static InformacionTorneoRequest pasoUno(String nombre, Integer cupoMaximo, Integer cupoMinimo) {
        return new InformacionTorneoRequest(nombre, "Ajedrez", null, null, Modalidad.ONLINE,
                TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null, EN_UNA_SEMANA, "Partidas de 10 minutos",
                cupoMaximo, cupoMinimo, null, null);
    }

    /** Individual y completo, con 1000 de inscripción, 50 % al pozo y cupo de 8. */
    private long publicado(Long organizadorId, String nombre, String disciplina, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_texto, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, ?, 'Reglas', ?, 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2, 1000, 50, 10,
                        now() + interval '1 day', now() + interval '7 days', ?)
                RETURNING id
                """, Long.class, organizadorId, nombre, disciplina, estado);
    }

    private void inscribir(long torneo, String estado) {
        Long jugador = usuario("jugador_" + estado.toLowerCase() + "_" + System.nanoTime()).getId();
        jdbc.update("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, now())""", torneo, jugador, estado);
    }

    private Usuario usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres("Test");
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre.length() > 30 ? nombre.substring(0, 30) : nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario);
    }
}
