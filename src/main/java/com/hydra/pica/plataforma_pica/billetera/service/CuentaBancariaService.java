package com.hydra.pica.plataforma_pica.billetera.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.dto.CrearCuentaBancariaRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.CuentaBancariaResponse;
import com.hydra.pica.plataforma_pica.billetera.event.CuentaBancariaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.EntidadAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
public class CuentaBancariaService {

    public static final String MENSAJE_TITULAR_NO_COINCIDE =
            "La cuenta destino debe estar a nombre del titular verificado en la plataforma";

    private static final int ALIAS_LONGITUD_MINIMA = 6;
    private static final int ALIAS_LONGITUD_MAXIMA = 20;
    private static final int CBU_CVU_DIGITOS = 22;
    private static final int CUIT_CUIL_DIGITOS = 11;
    private static final Pattern PATRON_ALIAS = Pattern.compile("^[a-zA-Z0-9.-]+$");
    private static final Pattern PATRON_NO_DIGITOS = Pattern.compile("\\D");

    private final CuentaBancariaRepository cuentaBancariaRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventos;

    public CuentaBancariaService(
            CuentaBancariaRepository cuentaBancariaRepository,
            UsuarioRepository usuarioRepository,
            AuditoriaService auditoriaService,
            PlatformTransactionManager transactionManager) {
        this(cuentaBancariaRepository, usuarioRepository, auditoriaService, transactionManager, null);
    }

    @Autowired
    public CuentaBancariaService(
            CuentaBancariaRepository cuentaBancariaRepository,
            UsuarioRepository usuarioRepository,
            AuditoriaService auditoriaService,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher eventos) {
        this.cuentaBancariaRepository = cuentaBancariaRepository;
        this.usuarioRepository = usuarioRepository;
        this.auditoriaService = auditoriaService;
        this.transactionManager = transactionManager;
        this.eventos = eventos;
    }

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
        String cuitDigitos = soloDigitos(request.cuitCuil());
        String docDigitos = soloDigitos(nroDoc);

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
                EntidadAuditoria.CUENTA_BANCARIA,
                String.valueOf(guardada.getId()),
                Map.of("tipo", guardada.getTipo().name(), "valor", guardada.getValor(),
                        "estado", EstadoValidacion.VALIDADA.name(), "titular", guardada.getTitular()));

        publicarRegistro(guardada, usuarioId);

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
                EntidadAuditoria.CUENTA_BANCARIA,
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
                    EntidadAuditoria.CUENTA_BANCARIA,
                    guardada != null && guardada.getId() != null ? String.valueOf(guardada.getId()) : "sin-id",
                    Map.of("tipo", request.tipo().name(), "valor", valorLimpio,
                            "estado", EstadoValidacion.RECHAZADA.name(), "motivo", "CUENTA_TITULAR_NO_COINCIDE"));

            publicarRegistro(guardada, usuario.getId());
        });
    }

    private void publicarRegistro(CuentaBancaria guardada, Long usuarioId) {
        if (eventos != null && guardada != null) {
            eventos.publishEvent(new CuentaBancariaRegistrada(
                    guardada.getId(), usuarioId, guardada.getTipo(), guardada.getValor()));
        }
    }

    private static void validarFormato(CrearCuentaBancariaRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "El cuerpo del request no puede estar vacío");
        }
        List<ErrorCampo> errores = new ArrayList<>();

        if (request.tipo() == null) {
            errores.add(new ErrorCampo("tipo", ErrorCampo.Codigo.REQUERIDO, "El tipo de cuenta es obligatorio"));
        }
        validarValor(request.tipo(), request.valor(), errores);
        if (request.titular() == null || request.titular().isBlank()) {
            errores.add(new ErrorCampo("titular", ErrorCampo.Codigo.REQUERIDO, "El titular es obligatorio"));
        }
        validarCuitCuil(request.cuitCuil(), errores);

        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }

    private static void validarValor(TipoCuenta tipo, String valorRecibido, List<ErrorCampo> errores) {
        if (valorRecibido == null || valorRecibido.isBlank()) {
            errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.REQUERIDO, "El CBU, CVU o alias es obligatorio"));
            return;
        }
        if (tipo == null) {
            return;
        }
        String valor = valorRecibido.strip();
        switch (tipo) {
            case CBU, CVU -> validarCbuOCvu(tipo, valor, errores);
            case ALIAS -> validarAlias(valor, errores);
        }
    }

    private static void validarCbuOCvu(TipoCuenta tipo, String valor, List<ErrorCampo> errores) {
        if (!ValidadorCbuCvu.esValido(valor)) {
            errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.FORMATO_INVALIDO,
                    "El " + tipo + " debe tener " + CBU_CVU_DIGITOS + " dígitos válidos con su dígito verificador"));
        }
    }

    private static void validarAlias(String valor, List<ErrorCampo> errores) {
        if (valor.length() < ALIAS_LONGITUD_MINIMA || valor.length() > ALIAS_LONGITUD_MAXIMA) {
            errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.LONGITUD,
                    "El alias debe tener entre " + ALIAS_LONGITUD_MINIMA + " y " + ALIAS_LONGITUD_MAXIMA + " caracteres"));
            return;
        }
        if (!PATRON_ALIAS.matcher(valor).matches()) {
            errores.add(new ErrorCampo("valor", ErrorCampo.Codigo.FORMATO_INVALIDO,
                    "El alias solo puede contener letras, números, puntos y guiones"));
        }
    }

    private static void validarCuitCuil(String cuitCuil, List<ErrorCampo> errores) {
        if (cuitCuil == null || cuitCuil.isBlank()) {
            errores.add(new ErrorCampo("cuitCuil", ErrorCampo.Codigo.REQUERIDO, "El CUIT/CUIL es obligatorio"));
            return;
        }
        if (soloDigitos(cuitCuil).length() != CUIT_CUIL_DIGITOS) {
            errores.add(new ErrorCampo("cuitCuil", ErrorCampo.Codigo.FORMATO_INVALIDO,
                    "El CUIT/CUIL debe contener " + CUIT_CUIL_DIGITOS + " dígitos"));
        }
    }

    private static String soloDigitos(String texto) {
        return PATRON_NO_DIGITOS.matcher(texto).replaceAll("");
    }
}
