package com.hydra.pica.plataforma_pica.user.service;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Búsqueda de jugadores para cargar una nómina (PICA-208). */
@Service
public class UsuarioBusquedaService {

    // el id desempata, así un mismo nombre no salta de una página a otra
    private static final int MINIMO_CARACTERES = 3;
    private static final Sort ORDEN = Sort.by("persona.apellidos", "persona.nombres", "id");

    private final UsuarioRepository usuarioRepository;

    public UsuarioBusquedaService(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional(readOnly = true)
    public Page<UsuarioPublico> buscarJugadores(String q, int page, int size) {
        // se cuenta sin los espacios de los costados: "  a " es una búsqueda de una letra
        if (q == null || q.strip().length() < MINIMO_CARACTERES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo("q", ErrorCampo.Codigo.LONGITUD,
                            "Tiene que tener al menos " + MINIMO_CARACTERES + " caracteres")));
        }
        // a un bloqueado o pendiente de verificación no se lo puede cargar en una nómina
        Specification<Usuario> filtro = Specification
                .where(UsuarioSpecifications.conNombreOUsername(q))
                .and(UsuarioSpecifications.conEstado(EstadoUsuario.ACTIVO));
        return usuarioRepository.findAll(filtro, PageRequest.of(page, size, ORDEN)).map(UsuarioPublico::desde);
    }
}
