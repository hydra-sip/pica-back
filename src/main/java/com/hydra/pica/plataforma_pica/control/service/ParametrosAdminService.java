package com.hydra.pica.plataforma_pica.control.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.ParametroPlataforma;
import com.hydra.pica.plataforma_pica.control.dto.ParametroDetalle;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.control.repository.ParametroPlataformaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El admin ve y cambia los parámetros de la plataforma (HU-702, PICA-282). Se cambian todos o ninguno: con un valor
 * inválido responde 400 {@code VALIDACION} con el error de cada clave. Rigen desde que se guardan; lo ya publicado
 * conserva el valor que copió. Cada cambio queda en la auditoría con el valor anterior y el nuevo.
 */
@Service
@RequiredArgsConstructor
public class ParametrosAdminService {

    static final String ENTIDAD = "PARAMETRO";

    /** El máximo de NUMERIC(12,2). */
    private static final BigDecimal MAXIMO = new BigDecimal("9999999999.99");
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final ParametroPlataformaRepository parametroPlataformaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional(readOnly = true)
    public List<ParametroDetalle> listar() {
        return detalles(parametroPlataformaRepository.findAll());
    }

    @Transactional
    public List<ParametroDetalle> actualizar(Long usuarioId, Map<String, BigDecimal> valores) {
        List<ErrorCampo> errores = new ArrayList<>();
        Map<ClaveParametro, BigDecimal> pedidos = new EnumMap<>(ClaveParametro.class);
        valores.forEach((clave, valor) -> {
            ClaveParametro c = ClaveParametro.de(clave);
            if (c == null) {
                errores.add(new ErrorCampo(clave, ErrorCampo.Codigo.VALOR_INVALIDO, "No es un parámetro de la plataforma"));
            } else if (valor == null) {
                errores.add(new ErrorCampo(clave, ErrorCampo.Codigo.REQUERIDO, "Falta el valor"));
            } else {
                validarValor(c, valor, errores);
                pedidos.put(c, valor);
            }
        });

        Map<ClaveParametro, ParametroPlataforma> actuales = new EnumMap<>(ClaveParametro.class);
        for (ParametroPlataforma p : parametroPlataformaRepository.bloquearTodos()) {
            ClaveParametro c = ClaveParametro.de(p.getClave());
            if (c != null) {
                actuales.put(c, p);
            }
        }
        if (errores.isEmpty()) {
            Map<ClaveParametro, BigDecimal> resultado = new EnumMap<>(ClaveParametro.class);
            actuales.forEach((c, p) -> resultado.put(c, p.getValor()));
            resultado.putAll(pedidos);
            noSupera(ClaveParametro.GASTOS_ADMINISTRATIVOS, ClaveParametro.GASTOS_ADMINISTRATIVOS_TOPE,
                    "No puede superar el tope", "No puede ser menor que el % vigente de gastos administrativos",
                    resultado, pedidos.keySet(), errores);
            noSupera(ClaveParametro.RETIRO_MINIMO, ClaveParametro.RETIRO_MAXIMO,
                    "No puede superar el retiro máximo", "No puede ser menor que el retiro mínimo",
                    resultado, pedidos.keySet(), errores);
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay parámetros inválidos")
                    .con("errores", errores);
        }

        pedidos.forEach((c, nuevo) -> {
            ParametroPlataforma parametro = actuales.get(c);
            if (parametro == null) {
                throw new IllegalStateException("Falta el parámetro " + c + " en la base");
            }
            if (parametro.getValor().compareTo(nuevo) != 0) {
                auditar(usuarioId, c, parametro.getValor(), nuevo);
                parametro.cambiar(nuevo, String.valueOf(usuarioId));
            }
        });
        return detalles(List.copyOf(actuales.values()));
    }

    /** Lo que se puede validar con el valor solo: el rango de su unidad y los decimales. */
    private static void validarValor(ClaveParametro clave, BigDecimal valor, List<ErrorCampo> errores) {
        String campo = clave.name();
        switch (clave.unidad()) {
            case PORCENTAJE -> {
                if (valor.signum() < 0 || valor.compareTo(CIEN) > 0) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.RANGO_INVALIDO, "Tiene que estar entre 0 y 100"));
                } else if (valor.stripTrailingZeros().scale() > 2) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.FORMATO_INVALIDO, "Hasta dos decimales"));
                }
            }
            case MINUTOS -> {
                boolean admiteCero = clave == ClaveParametro.MARGEN_CIERRE_INICIO_MIN;
                if (valor.stripTrailingZeros().scale() > 0) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.FORMATO_INVALIDO, "Son minutos enteros"));
                } else if (admiteCero ? valor.signum() < 0 : valor.signum() <= 0) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.RANGO_INVALIDO,
                            admiteCero ? "No puede ser negativo" : "Tiene que ser mayor que 0"));
                } else if (valor.compareTo(MAXIMO) > 0) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.RANGO_INVALIDO, "Es demasiado grande"));
                }
            }
            case PESOS -> {
                if (valor.signum() <= 0) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.RANGO_INVALIDO, "Tiene que ser mayor que 0"));
                } else if (valor.compareTo(MAXIMO) > 0) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.RANGO_INVALIDO, "Es demasiado grande"));
                } else if (valor.stripTrailingZeros().scale() > 2) {
                    errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.FORMATO_INVALIDO, "Hasta dos decimales"));
                }
            }
        }
    }

    /**
     * {@code menor} no puede quedar por encima de {@code mayor}. El error va en el que se cambió: si se cambió el
     * mayor (o los dos), en el mayor; si no, en el menor.
     */
    private static void noSupera(ClaveParametro menor, ClaveParametro mayor, String mensajeMenor, String mensajeMayor,
            Map<ClaveParametro, BigDecimal> resultado, Set<ClaveParametro> cambiados, List<ErrorCampo> errores) {
        BigDecimal vMenor = resultado.get(menor);
        BigDecimal vMayor = resultado.get(mayor);
        if (vMenor == null || vMayor == null || vMenor.compareTo(vMayor) <= 0) {
            return;
        }
        if (cambiados.contains(mayor)) {
            errores.add(new ErrorCampo(mayor.name(), ErrorCampo.Codigo.RANGO_INVALIDO, mensajeMayor));
        } else {
            errores.add(new ErrorCampo(menor.name(), ErrorCampo.Codigo.RANGO_INVALIDO, mensajeMenor));
        }
    }

    private void auditar(Long usuarioId, ClaveParametro clave, BigDecimal anterior, BigDecimal nuevo) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("anterior", anterior);
        detalle.put("nuevo", nuevo);
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        auditoria.setAccion(AccionAuditoria.MODIFICACION);
        auditoria.setEntidad(ENTIDAD);
        auditoria.setEntidadId(clave.name());
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }

    /** En el orden de {@link ClaveParametro}, con el username de quien modificó cada uno. */
    private List<ParametroDetalle> detalles(List<ParametroPlataforma> parametros) {
        Set<Long> ids = parametros.stream()
                .map(ParametroPlataforma::getModificadoPor)
                .filter(p -> p != null && !p.isEmpty() && p.chars().allMatch(Character::isDigit))
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        Map<String, String> usernames = usuarioRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(u -> String.valueOf(u.getId()), Usuario::getUsername));
        Function<ParametroPlataforma, ClaveParametro> clave = p -> ClaveParametro.de(p.getClave());
        return parametros.stream()
                .filter(p -> clave.apply(p) != null)
                .sorted(Comparator.comparing(clave))
                .map(p -> new ParametroDetalle(p.getClave(), clave.apply(p).unidad(), p.getValor(),
                        p.getDescripcion(), p.getModificadoEn(),
                        usernames.getOrDefault(p.getModificadoPor(), p.getModificadoPor())))
                .toList();
    }
}
