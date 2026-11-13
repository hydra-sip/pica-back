package com.hydra.pica.plataforma_pica.billetera.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.dto.CrearCuentaBancariaRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.CuentaBancariaResponse;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gestión de cuentas bancarias y virtuales con validación simulada contra documento (HU-607, PICA-336).
 */
@Service
@RequiredArgsConstructor
public class CuentaBancariaService {

    public static final String MENSAJE_TITULAR_NO_COINCIDE =
            "La cuenta destino debe estar a nombre del titular verificado en la plataforma";

    private final CuentaBancariaRepository cuentaBancariaRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;
    private final PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true)
    public List<CuentaBancariaResponse> listar(Long usuarioId) {
        return cuentaBancariaRepository.findByUsuarioIdOrderByCreadoEnDescIdDesc(usuarioId).stream()
                .map(CuentaBancariaResponse::de)
                .toList();
    }

    @Transactional
    public CuentaBancariaResponse crear(Long usuarioId, CrearCuentaBancariaRequest request) {
        validarFormato(request);

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "Usuario no encontrado"));

        Persona persona = usuario.getPersona();
        String nroDoc = persona != null ? persona.getNroDoc() : null;
        if (nroDoc == null || nroDoc.isBlank()) {
            throw new ConflictoException(CodigoError.IDENTIDAD_NO_VERIFICADA,
                    "Debés completar tu documento en el perfil antes de agregar una cuenta bancaria");
        }

        String valorLimpio = request.valor().strip();
        String cuitDigitos = request.cuitCuil().replaceAll("\\D", "");
        String docDigitos = nroDoc.replaceAll("\\D", "");

        // Chequear duplicado activo
        Optional<CuentaBancaria> existente = cuentaBancariaRepository.findByUsuarioIdAndValorIgnoreCase(usuarioId, valorLimpio);
        if (existente.isPresent() && existente.get().getEstadoValidacion() != EstadoValidacion.RECHAZADA) {
            throw new ConflictoException(CodigoError.CUENTA_BANCARIA_DUPLICADA,
                    "Ya tenés una cuenta registrada con ese valor");
        }

        boolean coincide = !docDigitos.isBlank() && cuitDigitos.contains(docDigitos);

        if (!coincide) {
            persistirRechazoEnTransaccionAutonoma(usuario, request, valorLimpio, existente.orElse(null));
            throw new ConflictoException(CodigoError.CUENTA_TITULAR_NO_COINCIDE, MENSAJE_TITULAR_NO_COINCIDE);
        }

        CuentaBancaria cuenta = existente.orElseGet(CuentaBancaria::new);
        cuenta.setUsuario(usuario);
        cuenta.setTipo(request.tipo());
        cuenta.setValor(valorLimpio);
        cuenta.setTitular(request.titular().strip());
        cuenta.setCuitCuil(request.cuitCuil().strip());
        cuenta.setEstadoValidacion(EstadoValidacion.VALIDADA);
        cuenta.setValidadaEn(Instant.now());
        cuenta.setEliminadoEn(null);
        CuentaBancaria guardada = cuentaBancariaRepository.save(cuenta);

        auditoriaService.registrar(
                usuarioId,
                AccionAuditoria.ALTA,
                "cuenta_bancaria",
                String.valueOf(guardada.getId()),
                Map.of("tipo", guardada.getTipo().name(), "valor", guardada.getValor(),
                        "estado", EstadoValidacion.VALIDADA.name(), "titular", guardada.getTitular()));

        return CuentaBancariaResponse.de(guardada);
    }

    @Transactional
    public void eliminar(Long usuarioId, Long cuentaId) {
        CuentaBancaria cuenta = cuentaBancariaRepository.findByIdAndUsuarioId(cuentaId, usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.CUENTA_BANCARIA_NO_ENCONTRADA,
                        "Cuenta bancaria no encontrada"));

        cuenta.setEliminadoEn(Instant.now());
        cuentaBancariaRepository.save(cuenta);

        auditoriaService.registrar(
                usuarioId,
                AccionAuditoria.BAJA,
                "cuenta_bancaria",
                String.valueOf(cuenta.getId()),
                Map.of("tipo", cuenta.getTipo().name(), "valor", cuenta.getValor()));
    }

    private void persistirRechazoEnTransaccionAutonoma(Usuario usuario, CrearCuentaBancariaRequest request,
                                                      String valorLimpio, CuentaBancaria previa) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            CuentaBancaria cuenta = previa != null ? previa : new CuentaBancaria();
            cuenta.setUsuario(usuario);
            cuenta.setTipo(request.tipo());
            cuenta.setValor(valorLimpio);
            cuenta.setTitular(request.titular().strip());
            cuenta.setCuitCuil(request.cuitCuil().strip());
            cuenta.setEstadoValidacion(EstadoValidacion.RECHAZADA);
            cuenta.setValidadaEn(null);
            cuenta.setEliminadoEn(null);
            CuentaBancaria guardada = cuentaBancariaRepository.saveAndFlush(cuenta);

            auditoriaService.registrar(
                    usuario.getId(),
                    AccionAuditoria.ALTA,
                    "cuenta_bancaria",
                    guardada != null && guardada.getId() != null ? String.valueOf(guardada.getId()) : "sin-id",
                    Map.of("tipo", request.tipo().name(), "valor", valorLimpio,
                            "estado", EstadoValidacion.RECHAZADA.name(), "motivo", "CUENTA_TITULAR_NO_COINCIDE"));
        });
    }

    private static void validarFormato(CrearCuentaBancariaRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "El cuerpo del request no puede estar vacío");
        }
        List<ErrorCampo> errores = new ArrayList<>();

        if (request.tipo() == null) {
            errores.add(new ErrorCampo("tipo", ErrorCampo.Codigo.REQUERIDO, "El tipo de cuenta es obligatorio"));
        }

        if (request.valor() == null || request.valor().isBlank()) {
            errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.REQUERIDO, "El CBU, CVU o alias es obligatorio"));
        } else {
            String valor = request.valor().strip();
            if (request.tipo() == TipoCuenta.CBU || request.tipo() == TipoCuenta.CVU) {
                if (!ValidadorCbuCvu.esValido(valor)) {
                    errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.FORMATO_INVALIDO,
                            "El " + request.tipo() + " debe tener 22 dígitos válidos con su dígito verificador"));
                }
            } else if (request.tipo() == TipoCuenta.ALIAS) {
                if (valor.length() < 6 || valor.length() > 20) {
                    errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.LONGITUD,
                            "El alias debe tener entre 6 y 20 caracteres"));
                } else if (!valor.matches("^[a-zA-Z0-9.-]+$")) {
                    errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.FORMATO_INVALIDO,
                            "El alias solo puede contener letras, números, puntos y guiones"));
                }
            }
        }

        if (request.titular() == null || request.titular().isBlank()) {
            errores.add(new ErrorCampo("titular", ErrorCampo.Codigo.REQUERIDO, "El titular es obligatorio"));
        }

        if (request.cuitCuil() == null || request.cuitCuil().isBlank()) {
            errores.add(new ErrorCampo("cuitCuil", ErrorCampo.Codigo.REQUERIDO, "El CUIT/CUIL es obligatorio"));
        } else {
            String digitos = request.cuitCuil().replaceAll("\\D", "");
            if (digitos.length() != 11) {
                errores.add(new ErrorCampo("cuitCuil", ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "El CUIT/CUIL debe contener 11 dígitos"));
            }
        }

        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }
}
