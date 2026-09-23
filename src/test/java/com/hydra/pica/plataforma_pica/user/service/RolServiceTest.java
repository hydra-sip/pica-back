package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.dto.RolRequest;
import com.hydra.pica.plataforma_pica.user.repository.PermisoRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.auditing.AuditingHandler;

/**
 * Lo único de {@link RolService} que no se puede armar contra Postgres en un test: dos altas con el
 * mismo nombre que pasan juntas el chequeo previo. El resto está en {@code RolServiceIntegracionTest}.
 */
@ExtendWith(MockitoExtension.class)
class RolServiceTest {

    private static final RolRequest VEEDOR = new RolRequest("VEEDOR", "Veedor", null, null);

    @Mock private RolRepository rolRepository;
    @Mock private UsuarioRolRepository usuarioRolRepository;
    @Mock private PermisoRepository permisoRepository;
    @Mock private AuditingHandler auditingHandler;

    @InjectMocks
    private RolService rolService;

    @Test
    @DisplayName("Si otra solicitud crea el mismo nombre entre el chequeo y el INSERT: 409, no 500")
    void carreraContraElIndiceUnicoDa409() {
        when(rolRepository.findByNombreIncluyendoEliminados("VEEDOR")).thenReturn(Optional.empty());
        when(rolRepository.saveAndFlush(any(Rol.class))).thenThrow(violacion("uq_rol_nombre"));

        assertThatThrownBy(() -> rolService.crear(VEEDOR))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.ROL_DUPLICADO);
    }

    @Test
    @DisplayName("Otra violación de integridad no se disfraza de ROL_DUPLICADO")
    void otraViolacionSigueDeLargo() {
        when(rolRepository.findByNombreIncluyendoEliminados("VEEDOR")).thenReturn(Optional.empty());
        when(rolRepository.saveAndFlush(any(Rol.class))).thenThrow(violacion("ck_rol_estado"));

        assertThatThrownBy(() -> rolService.crear(VEEDOR))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static DataIntegrityViolationException violacion(String constraint) {
        return new DataIntegrityViolationException("violación",
                new ConstraintViolationException("violación", new SQLException(), constraint));
    }
}
