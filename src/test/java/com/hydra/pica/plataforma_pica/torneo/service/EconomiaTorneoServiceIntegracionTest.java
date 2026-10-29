package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.ResumenEconomico;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paso 3 del asistente (HU-601, PICA-203) contra Postgres, con V19: el reparto en tres que suma 100, el tope de la
 * comisión, los premios por puesto, los gastos previstos y el resumen. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class EconomiaTorneoServiceIntegracionTest {

    @Autowired private EconomiaTorneoService economiaService;
    @Autowired private TorneoService torneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long otro;
    private Long torneo;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_eco").getId();
        otro = usuario("otro_eco").getId();
        torneo = conCupo(16);
    }

    // --- esquema económico --------------------------------------------------

    @Test
    void guardaElEsquemaConLosGastosAdministrativosAparte() {
        EconomiaTorneo economia = economiaService.actualizarEconomia(organizador, torneo,
                request("20000", "60", "25", "15")).economia();

        assertThat(economia.valorInscripcion()).isEqualByComparingTo("20000");
        assertThat(economia.moneda()).isEqualTo("ARS");
        // el 10 % vigente de la plataforma, que paga aparte quien se inscribe
        assertThat(economia.pctGastosAdministrativos()).isEqualByComparingTo("10");
        assertThat(economia.gastosAdministrativos()).isEqualByComparingTo("2000");
        assertThat(economia.totalAPagar()).isEqualByComparingTo("22000");
        assertThat(economia.premios()).extracting(EconomiaTorneo.PremioPuesto::puesto).containsExactly(1, 2, 3);
        assertThat(torneoService.detalle(organizador, torneo).economia().premios()).hasSize(3);
    }

    @Test
    void enBorradorLosGastosAdministrativosSonLosVigentes() {
        economiaService.actualizarEconomia(organizador, torneo, request("20000", "100"));
        entityManager.flush();
        jdbc.update("UPDATE parametro_plataforma SET valor = 7.5 WHERE clave = 'GASTOS_ADMINISTRATIVOS'");
        entityManager.clear();

        EconomiaTorneo economia = torneoService.detalle(organizador, torneo).economia();

        assertThat(economia.pctGastosAdministrativos()).isEqualByComparingTo("7.5");
        assertThat(economia.totalAPagar()).isEqualByComparingTo("21500");
    }

    @Test
    void losPremiosNoPasanDeCienNiDelCupo() {
        assertThatValidacion(() -> economiaService.actualizarEconomia(organizador, torneo,
                request("20000", "60", "50")))
                .containsEntry("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS);

        Long chico = conCupo(2);
        assertThatValidacion(() -> economiaService.actualizarEconomia(organizador, chico,
                request("20000", "50", "30", "20")))
                .containsEntry("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS);
    }

    @Test
    void enBorradorLosPremiosPuedenSumarMenosYSeReemplazan() {
        economiaService.actualizarEconomia(organizador, torneo, request("20000", "60", "25", "15"));
        assertThat(premios(economiaService.actualizarEconomia(organizador, torneo,
                request("20000", "70")).economia())).containsExactly("70");
        assertThat(premios(economiaService.actualizarEconomia(organizador, torneo,
                request("20000", "50", "30", "10", "10")).economia()))
                .containsExactly("50", "30", "10", "10");
    }

    // --- gastos ---------------------------------------------------------------

    @Test
    void losGastosSeNumeranDentroDelTorneoYSeEditan() {
        GastoDetalle arbitros = economiaService.agregarGasto(organizador, torneo, gasto("Honorarios árbitros", "20000"));
        GastoDetalle alquiler = economiaService.agregarGasto(organizador, torneo, gasto("Alquiler", "30000"));
        assertThat(arbitros.gastoId()).isEqualTo(1);
        assertThat(alquiler.gastoId()).isEqualTo(2);

        economiaService.modificarGasto(organizador, torneo, 2, gasto(" Alquiler de canchas ", "35000"));
        economiaService.eliminarGasto(organizador, torneo, 1);
        GastoDetalle luz = economiaService.agregarGasto(organizador, torneo, gasto("Luz", "5000"));

        assertThat(economiaService.gastos(organizador, torneo))
                .extracting(GastoDetalle::gastoId, GastoDetalle::descripcion)
                .containsExactly(tuple(2, "Alquiler de canchas"),
                        tuple(3, "Luz"));
        assertThat(luz.gastoId()).isEqualTo(3);
    }

    @Test
    void unGastoQueNoEsDelTorneoNoExiste() {
        assertThatThrownBy(() -> economiaService.modificarGasto(organizador, torneo, 9, gasto("Alquiler", "1")))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.GASTO_NO_ENCONTRADO);
        assertThatThrownBy(() -> economiaService.gastos(otro, torneo))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void unGastoPagadoNecesitaBeneficiarioYComprobante() {
        economiaService.agregarGasto(organizador, torneo, gasto("Alquiler", "30000"));
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.update("UPDATE gasto SET monto_abonado = 30000 WHERE torneo_id = ?", torneo))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_gasto_pagado");
    }

    // --- resumen --------------------------------------------------------------

    @Test
    void elResumenDaLasCuentasDelWireframe() {
        economiaService.actualizarEconomia(organizador, torneo, request("20000", "60", "25", "15"));
        economiaService.agregarGasto(organizador, torneo, gasto("Honorarios árbitros", "20000"));
        economiaService.agregarGasto(organizador, torneo, gasto("Alquiler", "30000"));

        ResumenEconomico resumen = economiaService.resumen(organizador, torneo);

        // 16 × 20.000, entero al pozo, menos 50.000 de gastos; los gastos administrativos se pagan aparte
        assertThat(resumen.recaudacion()).isEqualByComparingTo("320000");
        assertThat(resumen.gastos()).isEqualByComparingTo("50000");
        assertThat(resumen.pozoNeto()).isEqualByComparingTo("270000");
        assertThat(resumen.premios()).extracting(p -> p.monto().stripTrailingZeros().toPlainString())
                .containsExactly("162000", "67500", "40500");
        assertThat(resumen.pctGastosAdministrativos()).isEqualByComparingTo("10");
        assertThat(resumen.gastosAdministrativos()).isEqualByComparingTo("2000");
        assertThat(resumen.totalAPagar()).isEqualByComparingTo("22000");
    }

    @Test
    void sinParteEconomicaElResumenVieneVacio() {
        ResumenEconomico resumen = economiaService.resumen(organizador, torneo);

        assertThat(resumen.participantes()).isEqualTo(16);
        assertThat(resumen.recaudacion()).isNull();
        assertThat(resumen.pozoNeto()).isNull();
        assertThat(resumen.gastos()).isEqualByComparingTo("0");
        assertThat(resumen.premios()).isEmpty();
    }

    // --- dueño y estado -------------------------------------------------------

    @Test
    void fueraDeBorradorNoSeTocaLaEconomiaNiLosGastos() {
        economiaService.actualizarEconomia(organizador, torneo, request("20000", "100"));
        torneoService.actualizarInformacion(organizador, torneo, completo());
        entityManager.flush();
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_ABIERTA' WHERE id = ?", torneo);
        entityManager.clear();

        assertThatThrownBy(() -> economiaService.actualizarEconomia(organizador, torneo,
                request("20000", "100")))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
        assertThatThrownBy(() -> economiaService.agregarGasto(organizador, torneo, gasto("Alquiler", "1")))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
        assertThat(economiaService.resumen(organizador, torneo).recaudacion()).isEqualByComparingTo("320000");
    }

    @Test
    void elTorneoDeOtroOrganizadorNoExiste() {
        assertThatThrownBy(() -> economiaService.actualizarEconomia(otro, torneo, request("1")))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- helpers --------------------------------------------------------------

    private Long conCupo(int cupo) {
        return torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null, null,
                null, null, null, null, null, null, null, cupo, 2, null, null)).id();
    }

    private static InformacionTorneoRequest completo() {
        Instant inicio = Instant.now().plus(14, ChronoUnit.DAYS);
        Instant cierre = Instant.now().plus(7, ChronoUnit.DAYS);
        return new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null,
                Modalidad.PRESENCIAL,
                TipoParticipante.EQUIPO, "Club Mercedes", List.of(),
                inicio, null, cierre, "Reglas", 16, 12, 5, 10);
    }

    private static EconomiaTorneoRequest request(String valor, String... premios) {
        return new EconomiaTorneoRequest(new BigDecimal(valor), Arrays.stream(premios).map(BigDecimal::new).toList());
    }

    private static GastoRequest gasto(String descripcion, String monto) {
        return new GastoRequest(descripcion, new BigDecimal(monto));
    }

    private static List<String> premios(EconomiaTorneo economia) {
        return economia.premios().stream().map(p -> p.pct().stripTrailingZeros().toPlainString()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ErrorCampo.Codigo> errores(Throwable error) {
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
        Map<String, ErrorCampo.Codigo> porCampo = new LinkedHashMap<>();
        ((List<ErrorCampo>) api.getPropiedades().get("errores")).forEach(e -> porCampo.putIfAbsent(e.campo(), e.codigo()));
        return porCampo;
    }

    private static MapAssert<String, ErrorCampo.Codigo> assertThatValidacion(ThrowingCallable llamada) {
        return assertThat(errores(catchThrowable(llamada)));
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
