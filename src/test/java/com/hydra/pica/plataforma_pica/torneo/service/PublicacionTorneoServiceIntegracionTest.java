package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.FaltanteTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publicar el torneo (HU-601, PICA-204) contra Postgres: lo que falta, el sello del esquema y del reglamento, el slug y
 * que después no se toca. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class PublicacionTorneoServiceIntegracionTest {

    @Autowired private PublicacionTorneoService publicacionService;
    @Autowired private EconomiaTorneoService economiaService;
    @Autowired private TorneoService torneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_pub").getId();
    }

    @Test
    void publicaSellaYAbreLaInscripcion() {
        Long id = listo("Apertura F5");

        TorneoInformacion publicado = publicacionService.publicar(organizador, id);

        assertThat(publicado.estado()).isEqualTo(EstadoTorneo.INSCRIPCION_ABIERTA);
        assertThat(publicado.pasoPendiente()).isNull();
        assertThat(publicado.slug()).isEqualTo("apertura-f5");
        assertThat(publicado.publicadoEn()).isNotNull();
        // el formato documentado en SelloTorneo, recalculado a mano
        assertThat(publicado.esquemaHash()).isEqualTo(SelloTorneo.sha256("""
                torneo:%d
                inscripcion:20000.00 ARS
                gastos-administrativos:10.00
                premio:1:60.00
                premio:2:25.00
                premio:3:15.00
                gasto:1:20000.00:Honorarios árbitros
                gasto:2:30000.00:Alquiler""".formatted(id)));
        assertThat(jdbc.queryForObject("SELECT reglamento_hash FROM torneo WHERE id = ?", String.class, id))
                .isEqualTo(SelloTorneo.sha256("Reglas"));
    }

    @Test
    void alPublicarQuedanFijosLosGastosAdministrativosVigentes() {
        Long id = listo("Apertura F5");
        entityManager.flush();
        jdbc.update("UPDATE parametro_plataforma SET valor = 7.5 WHERE clave = 'GASTOS_ADMINISTRATIVOS'");
        entityManager.clear();

        TorneoInformacion publicado = publicacionService.publicar(organizador, id);
        entityManager.flush();
        jdbc.update("UPDATE parametro_plataforma SET valor = 12 WHERE clave = 'GASTOS_ADMINISTRATIVOS'");
        entityManager.clear();

        assertThat(publicado.economia().pctGastosAdministrativos()).isEqualByComparingTo("7.5");
        assertThat(torneoService.detalle(organizador, id).economia().pctGastosAdministrativos())
                .isEqualByComparingTo("7.5");
        assertThat(torneoService.detalle(organizador, id).economia().totalAPagar()).isEqualByComparingTo("21500");
    }

    @Test
    void elSlugVaSinTildesYNoSeRepite() {
        Long primero = listo("Copa Ñandú: Apertura 2026!");
        Long segundo = listo("Copa Ñandú: Apertura 2026!");

        assertThat(publicacionService.publicar(organizador, primero).slug()).isEqualTo("copa-nandu-apertura-2026");
        assertThat(publicacionService.publicar(organizador, segundo).slug()).isEqualTo("copa-nandu-apertura-2026-2");
    }

    @Test
    void elSlugNoTomaPalabrasQueChocanConRutas() {
        assertThat(publicacionService.publicar(organizador, listo("Nuevo")).slug()).isEqualTo("nuevo-2");
    }

    @Test
    void unBorradorRecienCreadoDiceTodoLoQueLeFalta() {
        Long id = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null,
                null, null, null, null, null, null, null, null, 16, 2, null, null)).id();

        assertThat(faltantes(id)).containsExactly(FaltanteTorneo.INFORMACION, FaltanteTorneo.ECONOMIA,
                FaltanteTorneo.SIN_PREMIOS);
        assertThat(torneoService.detalle(organizador, id).estado()).isEqualTo(EstadoTorneo.BORRADOR);
    }

    @Test
    void losPremiosTienenQueSumarCien() {
        Long id = listo("Apertura F5");
        economiaService.actualizarEconomia(organizador, id, economia("60", "30"));

        ApiException error = (ApiException) catchThrowable(() -> publicacionService.publicar(organizador, id));

        assertThat(error.getPropiedades().get("faltantes")).isEqualTo(List.of(FaltanteTorneo.PREMIOS_NO_SUMAN_100));
        assertThat((BigDecimal) error.getPropiedades().get("sumaPremios")).isEqualByComparingTo("90");
    }

    @Test
    void conElCupoMinimoLosGastosNoPuedenComerseElPozo() {
        // mínimo 12 × 20.000 = 240.000, y ya hay 50.000 de gastos
        Long id = listo("Apertura F5");
        economiaService.agregarGasto(organizador, id, gasto("Premio extra", "190000"));

        assertThat(faltantes(id)).containsExactly(FaltanteTorneo.POZO_NETO_NO_POSITIVO);
    }

    @Test
    void conElCierreVencidoNoSePublica() {
        Long id = listo("Apertura F5");
        entityManager.flush();
        jdbc.update("UPDATE torneo SET cierre_inscripcion_en = now() - interval '1 hour' WHERE id = ?", id);
        entityManager.clear();

        assertThat(faltantes(id)).containsExactly(FaltanteTorneo.CIERRE_VENCIDO);
    }

    @Test
    void publicadoNoSeVuelveAPublicarNiSeTocaLaEconomia() {
        Long id = listo("Apertura F5");
        publicacionService.publicar(organizador, id);

        assertThatThrownBy(() -> publicacionService.publicar(organizador, id))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
        assertThatThrownBy(() -> economiaService.agregarGasto(organizador, id, gasto("Luz", "1000")))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void elTorneoDeOtroOrganizadorNoExiste() {
        Long id = listo("Apertura F5");
        Long otro = usuario("otro_pub").getId();

        assertThatThrownBy(() -> publicacionService.publicar(otro, id))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- helpers --------------------------------------------------------------

    /** Los tres pasos cargados como en el wireframe: 16 equipos (mínimo 12), $ 20.000, 90/0/10 y 60/25/15. */
    private Long listo(String nombre) {
        Instant inicio = Instant.now().plus(14, ChronoUnit.DAYS);
        Instant cierre = Instant.now().plus(7, ChronoUnit.DAYS);
        Long id = torneoService.crear(organizador, new InformacionTorneoRequest(nombre, "Fútbol 5", null, null,
                null, null, null, null, null, null, null, null, 16, 2, null, null)).id();
        torneoService.actualizarInformacion(organizador, id, new InformacionTorneoRequest(nombre, "Fútbol 5", null,
                null, Modalidad.PRESENCIAL, TipoParticipante.EQUIPO, "Club Mercedes", List.of(), inicio, null, cierre,
                "Reglas", 16, 12, 5, 10));
        economiaService.actualizarEconomia(organizador, id, economia("60", "25", "15"));
        economiaService.agregarGasto(organizador, id, gasto("Honorarios árbitros", "20000"));
        economiaService.agregarGasto(organizador, id, gasto("Alquiler", "30000"));
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<FaltanteTorneo> faltantes(Long id) {
        Throwable error = catchThrowable(() -> publicacionService.publicar(organizador, id));
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.TORNEO_INCOMPLETO);
        return (List<FaltanteTorneo>) api.getPropiedades().get("faltantes");
    }

    private static EconomiaTorneoRequest economia(String... premios) {
        return new EconomiaTorneoRequest(new BigDecimal("20000"), Arrays.stream(premios).map(BigDecimal::new).toList());
    }

    private static GastoRequest gasto(String descripcion, String monto) {
        return new GastoRequest(descripcion, new BigDecimal(monto));
    }

    private Usuario usuario(String nombre) {
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
        return usuarioRepository.save(usuario);
    }
}
