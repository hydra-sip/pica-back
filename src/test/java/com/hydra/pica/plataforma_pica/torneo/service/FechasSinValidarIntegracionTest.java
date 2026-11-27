package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.service.ProgramacionService;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El ambiente de prueba con {@code app.torneos.sin-validar-fechas=true} (PICA-448): se crea, edita y publica un torneo
 * con fechas pasadas y se programan partidos en cualquier fecha, pero el orden entre las fechas se sigue exigiendo.
 * Con el flag apagado lo cubren EdicionTorneoIntegracionTest, PublicacionTorneoServiceIntegracionTest,
 * CheckinIntegracionTest y ReprogramacionTorneoIntegracionTest. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.torneos.sin-validar-fechas=true")
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class FechasSinValidarIntegracionTest {

    private static final Instant HACE_UN_DIA = Instant.now().minus(1, ChronoUnit.DAYS);
    private static final Instant HACE_UNA_SEMANA = Instant.now().minus(7, ChronoUnit.DAYS);
    private static final Instant EN_CINCO_DIAS = Instant.now().plus(5, ChronoUnit.DAYS);

    @Autowired private TorneoService torneoService;
    @Autowired private EconomiaTorneoService economiaService;
    @Autowired private PublicacionTorneoService publicacionService;
    @Autowired private ProgramacionService programacionService;
    @Autowired private ReprogramacionTorneoService reprogramacionService;
    @Autowired private EscenarioTorneo escenario;

    private Long organizador;

    @BeforeEach
    void escenario() {
        organizador = escenario.usuario("org_sin_fechas", "ORGANIZADOR").getId();
    }

    @Test
    void lasOpcionesDelTorneoAvisanQueElFlagEstaPrendido() {
        assertThat(torneoService.opciones(null).sinValidarFechas()).isTrue();
    }

    @Test
    void seCreaYSeEditaUnTorneoConElInicioYElCierrePasados() {
        TorneoInformacion creado = torneoService.crear(organizador,
                informacion(HACE_UN_DIA, EN_CINCO_DIAS, HACE_UNA_SEMANA));

        assertThat(creado.fechaInicio()).isEqualTo(HACE_UN_DIA);
        assertThat(creado.cierreInscripcionEn()).isEqualTo(HACE_UNA_SEMANA);

        Instant otroInicio = HACE_UN_DIA.minus(1, ChronoUnit.HOURS);
        Instant otroCierre = HACE_UNA_SEMANA.minus(1, ChronoUnit.HOURS);
        TorneoInformacion editado = torneoService.actualizarInformacion(organizador, creado.id(),
                informacion(otroInicio, EN_CINCO_DIAS, otroCierre));

        assertThat(editado.fechaInicio()).isEqualTo(otroInicio);
        assertThat(editado.cierreInscripcionEn()).isEqualTo(otroCierre);
    }

    @Test
    void sePublicaConElCierreDeLaInscripcionPasado() {
        Long id = torneoService.crear(organizador, informacion(HACE_UN_DIA, EN_CINCO_DIAS, HACE_UNA_SEMANA)).id();
        economiaService.actualizarEconomia(organizador, id,
                new EconomiaTorneoRequest(new BigDecimal("20000"), List.of(new BigDecimal("100"))));

        TorneoInformacion publicado = publicacionService.publicar(organizador, id);

        assertThat(publicado.estado()).isEqualTo(EstadoTorneo.INSCRIPCION_ABIERTA);
        assertThat(publicado.cierreInscripcionEn()).isEqualTo(HACE_UNA_SEMANA);
    }

    @Test
    void elOrdenEntreLasFechasSeSigueExigiendo() {
        // el cierre después del inicio
        assertThatValidacion(() -> torneoService.crear(organizador,
                informacion(HACE_UNA_SEMANA, EN_CINCO_DIAS, HACE_UN_DIA)))
                .containsEntry("cierreInscripcionEn", ErrorCampo.Codigo.FECHAS_INVALIDAS);
        // el fin antes del inicio
        assertThatValidacion(() -> torneoService.crear(organizador,
                informacion(HACE_UN_DIA, HACE_UNA_SEMANA, HACE_UNA_SEMANA.minus(1, ChronoUnit.DAYS))))
                .containsEntry("fechaFin", ErrorCampo.Codigo.FECHAS_INVALIDAS);
        // el margen entre el cierre y el inicio (60 minutos por defecto)
        assertThatValidacion(() -> torneoService.crear(organizador,
                informacion(HACE_UN_DIA, EN_CINCO_DIAS, HACE_UN_DIA.minus(1, ChronoUnit.MINUTES))))
                .containsEntry("cierreInscripcionEn", ErrorCampo.Codigo.FECHAS_INVALIDAS);
    }

    @Test
    void unPartidoSeProgramaEnElPasadoOFueraDeLasFechasDelTorneo() {
        Long arbitro = escenario.usuario("juez_sin_fechas", "ARBITRO").getId();
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa sin fechas", "jug_sin_fechas_", 4);
        Long partido = escenario.partido(torneo, 1, 1);
        // antes del inicio del torneo (en dos semanas) y en el pasado
        Instant ayer = HACE_UN_DIA.truncatedTo(ChronoUnit.SECONDS);

        PartidoDetalle programado = programacionService.programar(organizador, partido, ayer);

        assertThat(programado.fechaProgramada()).isEqualTo(ayer);
    }

    // --- ayudas -------------------------------------------------------------

    @Test
    void seReprogramaConFechasPasadasPeroElOrdenSeSigueExigiendo() {
        Long arbitro = escenario.usuario("arb_sin_fechas", "ARBITRO").getId();
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Reprogramado", "jug_sin_fechas_", 2);

        assertThatValidacion(() -> reprogramacionService.reprogramar(organizador, torneo,
                new ReprogramacionRequest(HACE_UN_DIA, HACE_UNA_SEMANA, HACE_UNA_SEMANA, HACE_UNA_SEMANA, "Lluvia")))
                .containsEntry("fechaFin", ErrorCampo.Codigo.FECHAS_INVALIDAS);

        ReprogramacionDetalle hecha = reprogramacionService.reprogramar(organizador, torneo,
                new ReprogramacionRequest(HACE_UN_DIA, null, HACE_UNA_SEMANA, HACE_UNA_SEMANA, "Lluvia"));

        assertThat(hecha.fechaInicioNueva()).isEqualTo(HACE_UN_DIA);
        assertThat(hecha.rechazoHasta()).isEqualTo(HACE_UNA_SEMANA);
    }

    private static InformacionTorneoRequest informacion(Instant inicio, Instant fin, Instant cierre) {
        return new InformacionTorneoRequest("Torneo de prueba", "Fútbol 5", null, null, Modalidad.PRESENCIAL,
                TipoParticipante.EQUIPO, "Club Mercedes", List.of(), inicio, fin, cierre, "Reglas", 16, 2, 5, 10);
    }

    /** Los errores del 400 como campo → código. */
    @SuppressWarnings("unchecked")
    private static org.assertj.core.api.MapAssert<String, ErrorCampo.Codigo> assertThatValidacion(
            ThrowingCallable llamada) {
        Throwable error = catchThrowable(llamada);
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
        Map<String, ErrorCampo.Codigo> porCampo = new LinkedHashMap<>();
        ((List<ErrorCampo>) api.getPropiedades().get("errores")).forEach(e -> porCampo.putIfAbsent(e.campo(), e.codigo()));
        return assertThat(porCampo);
    }
}
