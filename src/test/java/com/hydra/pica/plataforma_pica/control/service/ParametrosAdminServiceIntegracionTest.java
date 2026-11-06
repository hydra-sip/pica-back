package com.hydra.pica.plataforma_pica.control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.dto.ParametroDetalle;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El admin edita los parámetros (HU-702, PICA-282) contra Postgres: las pruebas de aceptación de la historia, las
 * reglas entre parámetros y la auditoría. Cada test hace rollback.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ParametrosAdminServiceIntegracionTest {

    @Autowired private ParametrosAdminService parametrosAdminService;
    @Autowired private ParametrosPlataforma parametrosPlataforma;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Usuario admin;

    @BeforeEach
    void escenario() {
        Persona persona = new Persona();
        persona.setNombres("Ada");
        persona.setApellidos("Admin");
        persona.setEstado(EstadoGeneral.ACTIVO);
        Usuario usuario = new Usuario();
        usuario.setUsername("admin_param");
        usuario.setEmail("admin_param@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(personaRepository.save(persona));
        admin = usuarioRepository.save(usuario);
    }

    @Test
    void listaTodosEnElOrdenDelAdminConSuUnidadYLosValoresDeLasMigraciones() {
        assertThat(parametrosAdminService.listar())
                .extracting(ParametroDetalle::clave, ParametroDetalle::unidad, p -> p.valor().stripTrailingZeros().toPlainString())
                .containsExactly(
                        tuple(ClaveParametro.GASTOS_ADMINISTRATIVOS, "10"),
                        tuple(ClaveParametro.GASTOS_ADMINISTRATIVOS_TOPE, "20"),
                        tuple(ClaveParametro.MARGEN_CIERRE_INICIO_MIN, "60"),
                        tuple(ClaveParametro.CHECKIN_DURACION_MAX_MIN, "60"),
                        tuple(ClaveParametro.RETIRO_MINIMO, "1000"),
                        tuple(ClaveParametro.RETIRO_MAXIMO, "500000"));
        assertThat(parametrosAdminService.listar())
                .allSatisfy(p -> {
                    assertThat(p.descripcion()).isNotBlank();
                    assertThat(p.modificadoPor()).isEqualTo("SISTEMA");
                });
    }

    @Test
    void conValoresCorrectosSeGuardanYRigenDesdeAhora() {
        List<ParametroDetalle> guardados = parametrosAdminService.actualizar(admin.getId(), Map.of(
                "GASTOS_ADMINISTRATIVOS", new BigDecimal("12.5"),
                "CHECKIN_DURACION_MAX_MIN", BigDecimal.valueOf(30),
                "RETIRO_MAXIMO", BigDecimal.valueOf(250000)));

        assertThat(parametrosPlataforma.gastosAdministrativos()).isEqualByComparingTo("12.5");
        assertThat(parametrosPlataforma.checkinDuracionMaxima()).isEqualTo(Duration.ofMinutes(30));
        assertThat(parametrosPlataforma.retiroMaximo()).isEqualByComparingTo("250000");
        assertThat(guardados).filteredOn(p -> p.clave().equals("GASTOS_ADMINISTRATIVOS")).singleElement()
                .satisfies(p -> {
                    assertThat(p.modificadoPor()).isEqualTo("admin_param");
                    assertThat(p.modificadoEn()).isNotNull();
                });
        assertThat(guardados).filteredOn(p -> p.clave().equals("MARGEN_CIERRE_INICIO_MIN")).singleElement()
                .extracting(ParametroDetalle::modificadoPor).isEqualTo("SISTEMA");
    }

    @Test
    void cadaCambioQuedaEnLaAuditoriaConElValorAnteriorYElNuevo() {
        parametrosAdminService.actualizar(admin.getId(), Map.of(
                "GASTOS_ADMINISTRATIVOS", BigDecimal.valueOf(15),
                "MARGEN_CIERRE_INICIO_MIN", BigDecimal.valueOf(60)));

        assertThat(jdbc.queryForList("""
                SELECT usuario_id, accion, entidad_id, (detalle ->> 'anterior')::numeric AS anterior,
                       (detalle ->> 'nuevo')::numeric AS nuevo
                FROM auditoria WHERE entidad = 'PARAMETRO'"""))
                .singleElement()
                .satisfies(fila -> {
                    assertThat(fila.get("usuario_id")).isEqualTo(admin.getId());
                    assertThat(fila.get("accion")).isEqualTo("MODIFICACION");
                    assertThat(fila.get("entidad_id")).isEqualTo("GASTOS_ADMINISTRATIVOS");
                    assertThat((BigDecimal) fila.get("anterior")).isEqualByComparingTo("10");
                    assertThat((BigDecimal) fila.get("nuevo")).isEqualByComparingTo("15");
                });
    }

    @Test
    void unPorcentajeNegativoOMayorAlTopeEsError() {
        assertErrores(Map.of("GASTOS_ADMINISTRATIVOS", BigDecimal.valueOf(-1)),
                new ErrorCampo("GASTOS_ADMINISTRATIVOS", ErrorCampo.Codigo.RANGO_INVALIDO, "Tiene que estar entre 0 y 100"));
        assertErrores(Map.of("GASTOS_ADMINISTRATIVOS", BigDecimal.valueOf(25)),
                new ErrorCampo("GASTOS_ADMINISTRATIVOS", ErrorCampo.Codigo.RANGO_INVALIDO, "No puede superar el tope"));
        assertErrores(Map.of("GASTOS_ADMINISTRATIVOS_TOPE", BigDecimal.valueOf(101)),
                new ErrorCampo("GASTOS_ADMINISTRATIVOS_TOPE", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Tiene que estar entre 0 y 100"));
    }

    @Test
    void unTopeMenorALosGastosVigentesEsError() {
        assertErrores(Map.of("GASTOS_ADMINISTRATIVOS_TOPE", BigDecimal.valueOf(8)),
                new ErrorCampo("GASTOS_ADMINISTRATIVOS_TOPE", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "No puede ser menor que el % vigente de gastos administrativos"));

        parametrosAdminService.actualizar(admin.getId(), Map.of(
                "GASTOS_ADMINISTRATIVOS", BigDecimal.valueOf(8),
                "GASTOS_ADMINISTRATIVOS_TOPE", BigDecimal.valueOf(8)));
        assertThat(parametrosPlataforma.gastosAdministrativos()).isEqualByComparingTo("8");
    }

    @Test
    void unaDuracionIgualOMenorACeroEsError() {
        assertErrores(Map.of("CHECKIN_DURACION_MAX_MIN", BigDecimal.ZERO),
                new ErrorCampo("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.RANGO_INVALIDO, "Tiene que ser mayor que 0"));
        assertErrores(Map.of("CHECKIN_DURACION_MAX_MIN", BigDecimal.valueOf(-10)),
                new ErrorCampo("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.RANGO_INVALIDO, "Tiene que ser mayor que 0"));
        assertErrores(Map.of("CHECKIN_DURACION_MAX_MIN", new BigDecimal("12.5")),
                new ErrorCampo("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.FORMATO_INVALIDO, "Son minutos enteros"));
        assertErrores(Map.of("MARGEN_CIERRE_INICIO_MIN", BigDecimal.valueOf(-1)),
                new ErrorCampo("MARGEN_CIERRE_INICIO_MIN", ErrorCampo.Codigo.RANGO_INVALIDO, "No puede ser negativo"));
    }

    @Test
    void elRetiroMaximoNoPuedeSerMenorAlMinimo() {
        assertErrores(Map.of("RETIRO_MAXIMO", BigDecimal.valueOf(500)),
                new ErrorCampo("RETIRO_MAXIMO", ErrorCampo.Codigo.RANGO_INVALIDO, "No puede ser menor que el retiro mínimo"));
        assertErrores(Map.of("RETIRO_MINIMO", BigDecimal.valueOf(600000)),
                new ErrorCampo("RETIRO_MINIMO", ErrorCampo.Codigo.RANGO_INVALIDO, "No puede superar el retiro máximo"));
        assertErrores(Map.of("RETIRO_MINIMO", new BigDecimal("0.001")),
                new ErrorCampo("RETIRO_MINIMO", ErrorCampo.Codigo.FORMATO_INVALIDO, "Hasta dos decimales"));
    }

    @Test
    void conUnoInvalidoNoSeGuardaNingunoYSeInformanTodos() {
        Map<String, BigDecimal> valores = new LinkedHashMap<>();
        valores.put("GASTOS_ADMINISTRATIVOS", BigDecimal.valueOf(5));
        valores.put("CHECKIN_DURACION_MAX_MIN", BigDecimal.ZERO);
        valores.put("COMISION_INVENTADA", BigDecimal.ONE);
        valores.put("RETIRO_MINIMO", null);

        assertThatThrownBy(() -> parametrosAdminService.actualizar(admin.getId(), valores))
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    assertThat(errores(e)).extracting(ErrorCampo::campo, ErrorCampo::codigo).containsExactlyInAnyOrder(
                            Tuple.tuple("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.RANGO_INVALIDO),
                            Tuple.tuple("COMISION_INVENTADA", ErrorCampo.Codigo.VALOR_INVALIDO),
                            Tuple.tuple("RETIRO_MINIMO", ErrorCampo.Codigo.REQUERIDO));
                });
        assertThat(parametrosPlataforma.gastosAdministrativos()).isEqualByComparingTo("10");
    }

    @Test
    void guardarElMismoValorNoDejaRastroEnLaAuditoria() {
        parametrosAdminService.actualizar(admin.getId(), Map.of("GASTOS_ADMINISTRATIVOS", new BigDecimal("10.00")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidad = 'PARAMETRO'", Long.class))
                .isZero();
        assertThat(parametrosAdminService.listar()).first().extracting(ParametroDetalle::modificadoPor)
                .isEqualTo("SISTEMA");
    }

    private static Tuple tuple(ClaveParametro clave, String valor) {
        return Tuple.tuple(clave.name(), clave.unidad(), valor);
    }

    private void assertErrores(Map<String, BigDecimal> valores, ErrorCampo... esperados) {
        assertThatThrownBy(() -> parametrosAdminService.actualizar(admin.getId(), valores))
                .satisfies(e -> assertThat(errores(e)).containsExactly(esperados));
    }

    @SuppressWarnings("unchecked")
    private static List<ErrorCampo> errores(Throwable e) {
        return (List<ErrorCampo>) ((ApiException) e).getPropiedades().get("errores");
    }
}
