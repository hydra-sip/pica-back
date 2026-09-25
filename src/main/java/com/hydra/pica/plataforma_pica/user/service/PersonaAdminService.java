package com.hydra.pica.plataforma_pica.user.service;

import java.time.Instant;
import java.util.Locale;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.dto.PersonaDetalle;
import com.hydra.pica.plataforma_pica.user.dto.PersonaRequest;
import com.hydra.pica.plataforma_pica.user.dto.PersonaResumen;
import com.hydra.pica.plataforma_pica.user.repository.PersonaAdminRepositoryCustom.PersonaAdminRow;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABM de personas del backoffice (PICA-123). La creación y vinculación que hace el registro está en
 * {@link PersonaService}.
 *
 * El documento es único contando a las personas dadas de baja (el índice es total), así que el chequeo
 * de duplicados las incluye. Igual que con los usuarios, una persona dada de baja no se modifica (404):
 * para traerla de vuelta está {@link #reactivar}. Darla de baja otra vez no hace nada. Y no se puede dar de
 * baja a una persona cuyo usuario sigue vivo: primero se da de baja el usuario.
 */
@Service
@RequiredArgsConstructor
public class PersonaAdminService {

    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional(readOnly = true)
    public Page<PersonaResumen> listar(String q, EstadoGeneral estado, boolean incluirEliminados, Pageable pageable) {
        return personaRepository.buscar(q, estado, incluirEliminados, pageable).map(PersonaAdminService::aResumen);
    }

    /** Trae también a las eliminadas: la ficha es desde donde se las reactiva. */
    @Transactional(readOnly = true)
    public PersonaDetalle obtener(Long id) {
        return detalle(buscarIncluyendoEliminadas(id));
    }

    @Transactional
    public PersonaDetalle crear(PersonaRequest request) {
        TipoDoc tipoDoc = request.tipoDoc();
        String nroDoc = mayusculas(request.nroDoc());
        exigirDocumentoLibre(null, tipoDoc, nroDoc);

        Persona persona = new Persona();
        aplicar(persona, request);
        persona.setEstado(request.estado() == null ? EstadoGeneral.ACTIVO : request.estado());
        return detalle(guardar(persona, tipoDoc, nroDoc));
    }

    /** Reemplaza todos los campos; solo el estado, si no viene, se conserva. */
    @Transactional
    public PersonaDetalle modificar(Long id, PersonaRequest request) {
        Persona persona = buscarViva(id);
        TipoDoc tipoDoc = request.tipoDoc();
        String nroDoc = mayusculas(request.nroDoc());
        exigirDocumentoLibre(persona.getId(), tipoDoc, nroDoc);

        aplicar(persona, request);
        if (request.estado() != null) {
            persona.setEstado(request.estado());
        }
        return detalle(guardar(persona, tipoDoc, nroDoc));
    }

    /**
     * Baja lógica. Idempotente, como la de usuarios: si ya estaba dada de baja no hace nada. Un id que no
     * existe es 404.
     */
    @Transactional
    public void eliminar(Long id) {
        Persona persona = buscarIncluyendoEliminadas(id);
        if (persona.getEliminadoEn() != null) {
            return;
        }
        boolean conUsuarioVivo = usuarioRepository.findByPersonaIdIncluyendoEliminados(id)
                .filter(usuario -> usuario.getEliminadoEn() == null)
                .isPresent();
        if (conUsuarioVivo) {
            throw new ConflictoException(CodigoError.PERSONA_CON_USUARIO,
                    "La persona " + id + " tiene un usuario activo: primero hay que darlo de baja");
        }

        persona.setEliminadoEn(Instant.now());
        personaRepository.saveAndFlush(persona);
    }

    /** Si no estaba dada de baja no cambia nada y la devuelve igual. */
    @Transactional
    public PersonaDetalle reactivar(Long id) {
        Persona persona = buscarIncluyendoEliminadas(id);
        persona.setEliminadoEn(null);
        // el flush es para que modificadoEn salga actualizado en la respuesta
        return detalle(personaRepository.saveAndFlush(persona));
    }

    private void aplicar(Persona persona, PersonaRequest request) {
        persona.setNombres(request.nombres().strip());
        persona.setApellidos(request.apellidos().strip());
        persona.setTipoDoc(request.tipoDoc().name());
        persona.setNroDoc(mayusculas(request.nroDoc()));
        persona.setFechaNacimiento(request.fechaNacimiento());
        persona.setDomicilioPostal(textoONull(request.domicilioPostal()));
        persona.setTelefono(textoONull(request.telefono()));
        persona.setDescripcion(textoONull(request.descripcion()));
    }

    /** 409 si el documento ya es de otra persona, viva o eliminada. */
    private void exigirDocumentoLibre(Long propiaId, TipoDoc tipoDoc, String nroDoc) {
        boolean deOtra = personaRepository.findByDocumentoIncluyendoEliminadas(tipoDoc.name(), nroDoc)
                .filter(existente -> !existente.getId().equals(propiaId))
                .isPresent();
        if (deOtra) {
            throw new ConflictoException(CodigoError.DOCUMENTO_DUPLICADO,
                    "El documento " + tipoDoc + " " + nroDoc + " ya pertenece a otra persona");
        }
    }

    /** flush ahora: dos pedidos a la vez con el mismo documento pasan el chequeo y uno pierde contra el índice. */
    private Persona guardar(Persona persona, TipoDoc tipoDoc, String nroDoc) {
        try {
            return personaRepository.saveAndFlush(persona);
        } catch (DataIntegrityViolationException e) {
            if (!PersonaService.UQ_DOCUMENTO.equals(PersonaService.nombreDeConstraint(e))) {
                throw e;
            }
            throw new ConflictoException(CodigoError.DOCUMENTO_DUPLICADO,
                    "El documento " + tipoDoc + " " + nroDoc + " se cargó al mismo tiempo desde otra solicitud");
        }
    }

    /** findById respeta el @SQLRestriction: una persona dada de baja da 404. */
    private Persona buscarViva(Long id) {
        return personaRepository.findById(id).orElseThrow(() -> noExiste(id));
    }

    private Persona buscarIncluyendoEliminadas(Long id) {
        return personaRepository.findByIdIncluyendoEliminadas(id).orElseThrow(() -> noExiste(id));
    }

    private static NoEncontradoException noExiste(Long id) {
        return new NoEncontradoException(CodigoError.PERSONA_NO_ENCONTRADA, "No existe la persona " + id);
    }

    private PersonaDetalle detalle(Persona persona) {
        return PersonaDetalle.desde(persona,
                usuarioRepository.findByPersonaIdIncluyendoEliminados(persona.getId()).orElse(null));
    }

    private static PersonaResumen aResumen(PersonaAdminRow fila) {
        return new PersonaResumen(fila.id(), fila.nombres(), fila.apellidos(), fila.tipoDoc(), fila.nroDoc(),
                fila.estado(), fila.eliminadoEn() != null, fila.tieneUsuario());
    }

    private static String mayusculas(String nroDoc) {
        return nroDoc.strip().toUpperCase(Locale.ROOT);
    }

    private static String textoONull(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }
}
