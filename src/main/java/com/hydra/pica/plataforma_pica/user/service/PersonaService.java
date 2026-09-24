package com.hydra.pica.plataforma_pica.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve la persona a la que se va a vincular un usuario nuevo (PICA-109).
 *
 * La regla es simple: si viene documento se busca por él; si ya hay una persona activa se
 * reutiliza y si no existe se crea. Si existe pero está inactiva o eliminada se corta con
 * 409 PERSONA_INACTIVA, porque la baja la tiene que revertir un administrador. Sin documento
 * (Google) no hay con qué buscar y siempre se crea.
 *
 * Nunca pisa datos de una persona existente: si lo que llega no coincide con lo guardado
 * solo queda un WARN en el log para que se revise a mano.
 */
@Service
@RequiredArgsConstructor
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);

    static final String UQ_DOCUMENTO = "uq_persona_tipo_doc_nro_doc";

    private final PersonaRepository personaRepository;

    @Transactional
    public Persona buscarOCrear(DatosPersona datos) {
        if (!datos.tieneDocumento()) {
            return crear(datos);
        }

        return personaRepository
                .findByDocumentoIncluyendoEliminadas(datos.tipoDoc().name(), mayusculas(datos.nroDoc()))
                .map(existente -> {
                    if (existente.getEliminadoEn() != null || existente.getEstado() != EstadoGeneral.ACTIVO) {
                        throw new PersonaInactivaException(datos.tipoDoc(), datos.nroDoc());
                    }
                    avisarSiDifieren(existente, datos);
                    return existente;
                })
                .orElseGet(() -> crear(datos));
    }

    /**
     * Dos registros simultáneos con el mismo documento pasan los dos por el SELECT de arriba sin
     * encontrar nada y uno pierde contra uq_persona_tipo_doc_nro_doc. Sin esto la
     * DataIntegrityViolationException llega al handler genérico y el cliente ve un 500.
     *
     * No se puede releer la persona acá: después de la violación Postgres deja la transacción
     * abortada. El 409 es la respuesta honesta y en el reintento la persona ya está creada.
     */
    private Persona crear(DatosPersona datos) {
        try {
            // flush ahora para que el choque salte acá y no al cerrar la transacción
            return personaRepository.saveAndFlush(nueva(datos));
        } catch (DataIntegrityViolationException e) {
            if (!UQ_DOCUMENTO.equals(nombreDeConstraint(e))) {
                throw e;
            }
            throw new ConflictoException(CodigoError.DOCUMENTO_DUPLICADO,
                    "El documento " + datos.tipoDoc() + " " + datos.nroDoc()
                            + " se registró al mismo tiempo desde otra solicitud, reintentá");
        }
    }

    /**
     * Carga el documento de una persona que no tenía (el usuario de Google completando Mi perfil,
     * PICA-121). Si ya es de otra persona, viva o eliminada, 409 DOCUMENTO_DUPLICADO: el índice
     * único cuenta a las eliminadas. Quién puede cambiar un documento ya cargado lo decide quien llama.
     */
    @Transactional
    public void asignarDocumento(Persona persona, TipoDoc tipoDoc, String nroDocRecibido) {
        String nroDoc = mayusculas(nroDocRecibido);
        boolean deOtra = personaRepository.findByDocumentoIncluyendoEliminadas(tipoDoc.name(), nroDoc)
                .filter(existente -> !existente.getId().equals(persona.getId()))
                .isPresent();
        if (deOtra) {
            throw new ConflictoException(CodigoError.DOCUMENTO_DUPLICADO,
                    "El documento " + tipoDoc + " " + nroDoc + " ya pertenece a otra persona");
        }

        persona.setTipoDoc(tipoDoc.name());
        persona.setNroDoc(nroDoc);
        try {
            // mismo caso que en crear: dos pedidos a la vez con el mismo documento
            personaRepository.saveAndFlush(persona);
        } catch (DataIntegrityViolationException e) {
            if (!UQ_DOCUMENTO.equals(nombreDeConstraint(e))) {
                throw e;
            }
            throw new ConflictoException(CodigoError.DOCUMENTO_DUPLICADO,
                    "El documento " + tipoDoc + " " + nroDoc + " se registró al mismo tiempo desde otra solicitud");
        }
    }

    /** El documento se guarda y se compara siempre en mayúsculas ("ab123" y "AB123" son el mismo). */
    private static String mayusculas(String nroDoc) {
        return nroDoc == null ? null : nroDoc.toUpperCase(Locale.ROOT);
    }

    static String nombreDeConstraint(DataIntegrityViolationException e) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        return causa == null ? null : ((ConstraintViolationException) causa).getConstraintName();
    }

    private Persona nueva(DatosPersona datos) {
        Persona persona = new Persona();
        persona.setNombres(datos.nombres());
        persona.setApellidos(datos.apellidos());
        persona.setTipoDoc(datos.tipoDoc() != null ? datos.tipoDoc().name() : null);
        persona.setNroDoc(mayusculas(datos.nroDoc()));
        persona.setFechaNacimiento(datos.fechaNacimiento());
        persona.setDomicilioPostal(datos.domicilioPostal());
        persona.setTelefono(datos.telefono());
        // la columna tiene default en la BD pero Hibernate manda el null si no se setea
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }

    private void avisarSiDifieren(Persona existente, DatosPersona datos) {
        List<String> distintos = new ArrayList<>();
        if (!mismoTexto(existente.getNombres(), datos.nombres())) {
            distintos.add("nombres");
        }
        if (!mismoTexto(existente.getApellidos(), datos.apellidos())) {
            distintos.add("apellidos");
        }
        if (datos.fechaNacimiento() != null
                && !Objects.equals(existente.getFechaNacimiento(), datos.fechaNacimiento())) {
            distintos.add("fechaNacimiento");
        }
        if (!distintos.isEmpty()) {
            log.warn("Persona {} {} (id {}) ya existía y los datos recibidos difieren en {}; se mantienen los guardados",
                    existente.getTipoDoc(), existente.getNroDoc(), existente.getId(), distintos);
        }
    }

    private static boolean mismoTexto(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.strip().equalsIgnoreCase(b.strip());
    }
}
