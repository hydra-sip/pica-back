package com.hydra.pica.plataforma_pica.control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.ParametroPlataforma;
import com.hydra.pica.plataforma_pica.control.dto.ParametroDetalle;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.control.repository.ParametroPlataformaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ParametrosAdminServiceTest {

    @Mock private ParametroPlataformaRepository parametroPlataformaRepository;
    @Mock private AuditoriaRepository auditoriaRepository;
    @Mock private UsuarioRepository usuarioRepository;

    @InjectMocks private ParametrosAdminService service;

    @Test
    @DisplayName("ClaveParametro valida cada unidad y la regla de cero en MARGEN_CIERRE_INICIO_MIN")
    void validaUnidadesDeClaveParametro() {
        assertThat(ClaveParametro.GASTOS_ADMINISTRATIVOS.validar(BigDecimal.ZERO)).isEmpty();
        assertThat(ClaveParametro.GASTOS_ADMINISTRATIVOS.validar(new BigDecimal("12.55"))).isEmpty();
        assertThat(ClaveParametro.GASTOS_ADMINISTRATIVOS.validar(new BigDecimal("-0.01")))
                .contains(new ErrorCampo("GASTOS_ADMINISTRATIVOS", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Tiene que estar entre 0 y 100"));
        assertThat(ClaveParametro.GASTOS_ADMINISTRATIVOS.validar(new BigDecimal("100.01")))
                .contains(new ErrorCampo("GASTOS_ADMINISTRATIVOS", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Tiene que estar entre 0 y 100"));
        assertThat(ClaveParametro.GASTOS_ADMINISTRATIVOS.validar(new BigDecimal("10.123")))
                .contains(new ErrorCampo("GASTOS_ADMINISTRATIVOS", ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "Hasta dos decimales"));

        assertThat(ClaveParametro.MARGEN_CIERRE_INICIO_MIN.validar(BigDecimal.ZERO)).isEmpty();
        assertThat(ClaveParametro.MARGEN_CIERRE_INICIO_MIN.validar(BigDecimal.valueOf(-1)))
                .contains(new ErrorCampo("MARGEN_CIERRE_INICIO_MIN", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "No puede ser negativo"));
        assertThat(ClaveParametro.CHECKIN_DURACION_MAX_MIN.validar(BigDecimal.ZERO))
                .contains(new ErrorCampo("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Tiene que ser mayor que 0"));
        assertThat(ClaveParametro.CHECKIN_DURACION_MAX_MIN.validar(new BigDecimal("12.5")))
                .contains(new ErrorCampo("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "Son minutos enteros"));
        assertThat(ClaveParametro.CHECKIN_DURACION_MAX_MIN.validar(new BigDecimal("10000000000")))
                .contains(new ErrorCampo("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Es demasiado grande"));

        assertThat(ClaveParametro.RETIRO_MINIMO.validar(new BigDecimal("100.50"))).isEmpty();
        assertThat(ClaveParametro.RETIRO_MINIMO.validar(BigDecimal.ZERO))
                .contains(new ErrorCampo("RETIRO_MINIMO", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Tiene que ser mayor que 0"));
        assertThat(ClaveParametro.RETIRO_MINIMO.validar(new BigDecimal("0.001")))
                .contains(new ErrorCampo("RETIRO_MINIMO", ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "Hasta dos decimales"));

        assertThat(ClaveParametro.FAIR_PLAY_INICIAL.validar(BigDecimal.valueOf(1000))).isEmpty();
        assertThat(ClaveParametro.FAIR_PLAY_INICIAL.validar(new BigDecimal("1.5")))
                .contains(new ErrorCampo("FAIR_PLAY_INICIAL", ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "Son puntos enteros"));
        assertThat(ClaveParametro.FAIR_PLAY_INICIAL.validar(BigDecimal.valueOf(1001)))
                .contains(new ErrorCampo("FAIR_PLAY_INICIAL", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Tiene que estar entre 0 y 1000"));

        assertThat(ClaveParametro.FRAUDE_RIFA_NUMEROS_MAX.validar(BigDecimal.ONE)).isEmpty();
        assertThat(ClaveParametro.FRAUDE_RIFA_NUMEROS_MAX.validar(BigDecimal.ZERO))
                .contains(new ErrorCampo("FRAUDE_RIFA_NUMEROS_MAX", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Tiene que ser mayor que 0"));
        assertThat(ClaveParametro.FRAUDE_RIFA_NUMEROS_MAX.validar(new BigDecimal("2.5")))
                .contains(new ErrorCampo("FRAUDE_RIFA_NUMEROS_MAX", ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "Tiene que ser una cantidad entera"));
    }

    @Test
    @DisplayName("actualizar informa claves desconocidas, valores nulos y errores individuales sin guardar")
    void rechazaClavesYValoresInvalidos() {
        when(parametroPlataformaRepository.bloquearTodos()).thenReturn(List.of());

        Map<String, BigDecimal> valores = new LinkedHashMap<>();
        valores.put("INEXISTENTE", BigDecimal.ONE);
        valores.put("RETIRO_MINIMO", null);
        valores.put("CHECKIN_DURACION_MAX_MIN", BigDecimal.ZERO);

        assertThatThrownBy(() -> service.actualizar(1L, valores))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    assertThat(erroresDe(ex)).containsExactly(
                            new ErrorCampo("INEXISTENTE", ErrorCampo.Codigo.VALOR_INVALIDO,
                                    "No es un parámetro de la plataforma"),
                            new ErrorCampo("RETIRO_MINIMO", ErrorCampo.Codigo.REQUERIDO, "Falta el valor"),
                            new ErrorCampo("CHECKIN_DURACION_MAX_MIN", ErrorCampo.Codigo.RANGO_INVALIDO,
                                    "Tiene que ser mayor que 0"));
                });

        verify(auditoriaRepository, never()).save(any());
    }

    @Test
    @DisplayName("actualizar atribuye el error de orden al parámetro modificado")
    void atribuyeErrorDeOrdenAlParametroModificado() {
        List<ParametroPlataforma> base = List.of(
                parametro("FAIR_PLAY_INICIAL", "100"),
                parametro("FAIR_PLAY_MAXIMO", "100"),
                parametro("RETIRO_MINIMO", "1000"),
                parametro("RETIRO_MAXIMO", "500000"));
        when(parametroPlataformaRepository.bloquearTodos()).thenReturn(base);

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("FAIR_PLAY_INICIAL", BigDecimal.valueOf(120))))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(erroresDe(ex)).containsExactly(
                        new ErrorCampo("FAIR_PLAY_INICIAL", ErrorCampo.Codigo.RANGO_INVALIDO,
                                "No puede superar el índice máximo")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("RETIRO_MAXIMO", BigDecimal.valueOf(500))))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(erroresDe(ex)).containsExactly(
                        new ErrorCampo("RETIRO_MAXIMO", ErrorCampo.Codigo.RANGO_INVALIDO,
                                "No puede ser menor que el retiro mínimo")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of(
                "RETIRO_MINIMO", BigDecimal.valueOf(600),
                "RETIRO_MAXIMO", BigDecimal.valueOf(500))))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(erroresDe(ex)).containsExactly(
                        new ErrorCampo("RETIRO_MAXIMO", ErrorCampo.Codigo.RANGO_INVALIDO,
                                "No puede ser menor que el retiro mínimo")));
    }

    @Test
    @DisplayName("actualizar guarda y audita solo los parámetros cuyo valor cambió numéricamente")
    void actualizaYAuditaSoloCambiosReales() {
        ParametroPlataforma gastos = parametro("GASTOS_ADMINISTRATIVOS", "10.00");
        ParametroPlataforma tope = parametro("GASTOS_ADMINISTRATIVOS_TOPE", "20.00");
        ParametroPlataforma margen = parametro("MARGEN_CIERRE_INICIO_MIN", "60");
        when(parametroPlataformaRepository.bloquearTodos()).thenReturn(List.of(gastos, tope, margen));

        Usuario admin = new Usuario();
        ReflectionTestUtils.setField(admin, "id", 7L);
        admin.setUsername("admin_pica");
        when(usuarioRepository.getReferenceById(7L)).thenReturn(admin);
        when(usuarioRepository.findAllById(anySet())).thenReturn(List.of(admin));

        List<ParametroDetalle> resultado = service.actualizar(7L, Map.of(
                "GASTOS_ADMINISTRATIVOS", BigDecimal.valueOf(15),
                "GASTOS_ADMINISTRATIVOS_TOPE", BigDecimal.valueOf(20)));

        ArgumentCaptor<Auditoria> auditCaptor = ArgumentCaptor.forClass(Auditoria.class);
        verify(auditoriaRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEntidadId()).isEqualTo("GASTOS_ADMINISTRATIVOS");
        assertThat(auditCaptor.getValue().getDetalle()).containsEntry("nuevo", BigDecimal.valueOf(15));

        assertThat(gastos.getValor()).isEqualByComparingTo("15");
        assertThat(resultado).extracting(ParametroDetalle::clave, ParametroDetalle::modificadoPor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GASTOS_ADMINISTRATIVOS", "admin_pica"),
                        org.assertj.core.groups.Tuple.tuple("GASTOS_ADMINISTRATIVOS_TOPE", "SISTEMA"),
                        org.assertj.core.groups.Tuple.tuple("MARGEN_CIERRE_INICIO_MIN", "SISTEMA"));
    }

    private static ParametroPlataforma parametro(String clave, String valor) {
        ParametroPlataforma p = new ParametroPlataforma();
        ReflectionTestUtils.setField(p, "clave", clave);
        ReflectionTestUtils.setField(p, "valor", new BigDecimal(valor));
        ReflectionTestUtils.setField(p, "descripcion", "Descripción " + clave);
        ReflectionTestUtils.setField(p, "modificadoEn", Instant.parse("2026-11-01T00:00:00Z"));
        ReflectionTestUtils.setField(p, "modificadoPor", "SISTEMA");
        return p;
    }

    @SuppressWarnings("unchecked")
    private static List<ErrorCampo> erroresDe(ApiException ex) {
        return (List<ErrorCampo>) ex.getPropiedades().get("errores");
    }
}
