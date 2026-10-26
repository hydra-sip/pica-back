package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.MapAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
 * Editar un torneo publicado (HU-202, PICA-206) contra Postgres: una prueba por criterio de aceptación, más los bordes
 * de comparar lo que cambia. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class EdicionTorneoIntegracionTest {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    private static final Instant EN_TRES_SEMANAS = Instant.now().plus(21, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    private static final Instant EN_UN_MES = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @Autowired private TorneoService torneoService;
    @Autowired private EconomiaTorneoService economiaTorneoService;
    @Autowired private TorneoPublicoService torneoPublicoService;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private InscripcionRepository inscripcionRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long otro;
    private Long torneo;

    @BeforeEach
    void publicado() {
        organizador = usuario("org_edicion").getId();
        otro = usuario("otro_edicion").getId();
        torneo = torneoService.crear(organizador, publicado(b -> { })).id();
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_ABIERTA', slug = 'apertura-edicion', publicado_en = now()
                WHERE id = ?""", torneo);
        entityManager.clear();
    }

    // --- criterios de aceptación ----------------------------------------------

    @Test
    void unUsuarioQueNoEsElOrganizadorNoLoEdita() {
        assertThatThrownBy(() -> torneoService.actualizarInformacion(otro, torneo, publicado(b -> b.nombre = "Mío")))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void laParteEconomicaNoSeCambiaDesdeEstaEdicionNiDespuesDePublicar() {
        InformacionTorneoRequest conValor = publicado(b -> b.otros = Map.of("valorInscripcion", 5000));
        assertThatValidacion(() -> torneoService.actualizarInformacion(organizador, torneo, conValor))
                .containsEntry("valorInscripcion", ErrorCampo.Codigo.VALOR_INVALIDO);

        EconomiaTorneoRequest economia = new EconomiaTorneoRequest(new BigDecimal("5000"), new BigDecimal("90"),
                BigDecimal.ZERO, new BigDecimal("10"), List.of(new BigDecimal("100")));
        assertThatThrownBy(() -> economiaTorneoService.actualizarEconomia(organizador, torneo, economia))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void conUnaInscripcionPagaNoSeCambianSedeFechasNiFormato() {
        inscribir(EstadoInscripcion.CONFIRMADA);

        Throwable error = catchThrowable(() -> torneoService.actualizarInformacion(organizador, torneo, publicado(b -> {
            b.sede = "Club Luján";
            b.canchas = List.of("Cancha A");
            b.fechaInicio = EN_TRES_SEMANAS;
        })));

        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getCodigo()).isEqualTo(CodigoError.CAMPOS_BLOQUEADOS);
        assertThat(api.getPropiedades().get("campos")).isEqualTo(List.of("sede", "canchas", "fechaInicio"));
    }

    @Test
    void elCupoNoBajaDeLosEquiposYaInscriptos() {
        inscribir(EstadoInscripcion.PENDIENTE_PAGO);
        inscribir(EstadoInscripcion.CONFIRMADA);
        inscribir(EstadoInscripcion.CONFIRMADA);
        inscribir(EstadoInscripcion.CANCELADA);

        assertThatValidacion(() -> torneoService.actualizarInformacion(organizador, torneo,
                publicado(b -> b.cupoMaximo = 2)))
                .containsEntry("cupoMaximo", ErrorCampo.Codigo.RANGO_INVALIDO);

        TorneoInformacion conTres = torneoService.actualizarInformacion(organizador, torneo,
                publicado(b -> b.cupoMaximo = 3));
        assertThat(conTres.cupoMaximo()).isEqualTo(3);
    }

    @Test
    void sinInscripcionesPagasCambiaSedeFechasYFormatoYSeVeEnLaPublicacion() {
        inscribir(EstadoInscripcion.PENDIENTE_PAGO);

        TorneoInformacion editado = torneoService.actualizarInformacion(organizador, torneo, publicado(b -> {
            b.sede = "Club Luján";
            b.canchas = List.of("Cancha A");
            b.fechaInicio = EN_TRES_SEMANAS;
            b.formato = Formato.ELIMINACION_DIRECTA;
        }));

        assertThat(editado.sede()).isEqualTo("Club Luján");
        assertThat(editado.canchas()).containsExactly("Cancha A");
        entityManager.flush();
        entityManager.clear();
        var publica = torneoPublicoService.detallePublico("apertura-edicion");
        assertThat(publica.sede()).isEqualTo("Club Luján");
        assertThat(publica.fechaInicio()).isEqualTo(EN_TRES_SEMANAS);
    }

    // --- bordes ---------------------------------------------------------------

    @Test
    void conInscripcionesPagasSiguenElNombreLaDescripcionYElCupoMaximo() {
        inscribir(EstadoInscripcion.CONFIRMADA);

        TorneoInformacion editado = torneoService.actualizarInformacion(organizador, torneo, publicado(b -> {
            b.nombre = "Apertura F5 2026";
            b.descripcion = "Con premios para el podio";
            b.cupoMaximo = 20;
        }));

        assertThat(editado.nombre()).isEqualTo("Apertura F5 2026");
        assertThat(editado.cupoMaximo()).isEqualTo(20);
        // el enlace público no cambia con el nombre
        assertThat(editado.slug()).isEqualTo("apertura-edicion");
    }

    @Test
    void publicadoNuncaCambiaLaDisciplinaNiElReglamento() {
        Throwable error = catchThrowable(() -> torneoService.actualizarInformacion(organizador, torneo, publicado(b -> {
            b.disciplina = "Pádel";
            b.reglamento = "Otro reglamento";
        })));

        assertThat(((ApiException) error).getPropiedades().get("campos"))
                .isEqualTo(List.of("disciplina", "reglamentoTexto"));
    }

    @Test
    void dicenQueEstaBloqueadoSegunLasInscripciones() {
        assertThat(torneoService.detalle(organizador, torneo).camposBloqueados())
                .containsExactly("disciplina", "tipoParticipante", "reglamentoTexto");

        inscribir(EstadoInscripcion.CONFIRMADA);

        assertThat(torneoService.detalle(organizador, torneo).camposBloqueados())
                .contains("sede", "fechaInicio", "formato", "cupoMinimo")
                .doesNotContain("nombre", "descripcion", "cupoMaximo");
    }

    @Test
    void conElCierreVencidoSeGuardaSiNoLoCambia() {
        Instant ayer = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("UPDATE torneo SET cierre_inscripcion_en = ?::timestamptz WHERE id = ?", ayer.toString(), torneo);
        entityManager.clear();

        TorneoInformacion editado = torneoService.actualizarInformacion(organizador, torneo, publicado(b -> {
            b.cierre = ayer;
            b.nombre = "Apertura F5 2026";
        }));

        assertThat(editado.nombre()).isEqualTo("Apertura F5 2026");
    }

    @Test
    void publicadoNoSeVaciaLoQuePediaLaPublicacion() {
        assertThatValidacion(() -> torneoService.actualizarInformacion(organizador, torneo, publicado(b -> {
            b.fechaInicio = null;
            b.cupoMinimo = null;
        })))
                .containsEntry("fechaInicio", ErrorCampo.Codigo.REQUERIDO)
                .containsEntry("cupoMinimo", ErrorCampo.Codigo.REQUERIDO);
    }

    @Test
    void enCursoYaNoSeModifica() {
        jdbc.update("UPDATE torneo SET estado = 'EN_CURSO' WHERE id = ?", torneo);
        entityManager.clear();

        assertThatThrownBy(() -> torneoService.actualizarInformacion(organizador, torneo, publicado(b -> { })))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
        assertThat(torneoService.detalle(organizador, torneo).camposBloqueados())
                .containsExactlyElementsOf(EdicionTorneo.CAMPOS);
    }

    // --- helpers --------------------------------------------------------------

    /** Lo que se cargó al crearlo (individual: inscribir equipos pide armar el equipo), con {@code cambios}. */
    private static InformacionTorneoRequest publicado(Consumer<Datos> cambios) {
        Datos d = new Datos();
        cambios.accept(d);
        return new InformacionTorneoRequest(d.nombre, d.disciplina, d.descripcion, d.formato, Modalidad.PRESENCIAL,
                TipoParticipante.INDIVIDUAL, d.sede, d.canchas, d.fechaInicio, EN_UN_MES, d.cierre, d.reglamento,
                d.cupoMaximo, d.cupoMinimo, null, null, d.otros);
    }

    private static final class Datos {
        String nombre = "Apertura F5";
        String disciplina = "Fútbol 5";
        String descripcion;
        Formato formato;
        String sede = "Club Mercedes";
        List<String> canchas = List.of("Cancha 1", "Cancha 2");
        Instant fechaInicio = EN_DOS_SEMANAS;
        Instant cierre = EN_UNA_SEMANA;
        String reglamento = "Dos tiempos de 20 minutos";
        Integer cupoMaximo = 16;
        Integer cupoMinimo = 2;
        Map<String, Object> otros = Map.of();
    }

    private void inscribir(EstadoInscripcion estado) {
        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneoRepository.findById(torneo).orElseThrow());
        inscripcion.setUsuario(usuario("jugador_" + inscripcionRepository.count()));
        inscripcion.setEstado(estado);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        inscripcionRepository.saveAndFlush(inscripcion);
    }

    /** Los errores del 400 como campo → código. */
    @SuppressWarnings("unchecked")
    private static MapAssert<String, ErrorCampo.Codigo> assertThatValidacion(ThrowingCallable llamada) {
        Throwable error = catchThrowable(llamada);
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
        List<ErrorCampo> errores = (List<ErrorCampo>) api.getPropiedades().get("errores");
        Map<String, ErrorCampo.Codigo> porCampo = new LinkedHashMap<>();
        errores.forEach(e -> porCampo.putIfAbsent(e.campo(), e.codigo()));
        return assertThat(porCampo);
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
