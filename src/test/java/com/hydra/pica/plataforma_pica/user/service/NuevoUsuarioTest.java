package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NuevoUsuarioTest {

    private static final DatosPersona PERSONA = new DatosPersona(
            TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null);

    @Test
    @DisplayName("autoRegistro: pendiente de verificación, mail sin verificar, sin roles explícitos")
    void autoRegistro() {
        NuevoUsuario nuevo = NuevoUsuario.autoRegistro("jperez", " juan@example.com ", "Pica2026", PERSONA);

        assertThat(nuevo.origen()).isEqualTo(NuevoUsuario.Origen.AUTO_REGISTRO);
        assertThat(nuevo.estadoInicial()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        assertThat(nuevo.emailVerificado()).isFalse();
        assertThat(nuevo.email()).isEqualTo("juan@example.com");
        assertThat(nuevo.datosPersona()).isEqualTo(PERSONA);
        assertThat(nuevo.personaId()).isNull();
        assertThat(nuevo.rolIds()).isEmpty();
        assertThat(nuevo.llevaRolParticipante()).isTrue();
    }

    @Test
    @DisplayName("porAdmin: activo por defecto, mail verificado, persona por id y roles por id")
    void porAdmin() {
        NuevoUsuario nuevo = NuevoUsuario.porAdmin("jperez", "juan@example.com", "Temporal1", "desc",
                null, 7L, Set.of(2L, 3L));

        assertThat(nuevo.origen()).isEqualTo(NuevoUsuario.Origen.ADMIN);
        assertThat(nuevo.estadoInicial()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(nuevo.emailVerificado()).isTrue();
        assertThat(nuevo.personaId()).isEqualTo(7L);
        assertThat(nuevo.rolIds()).containsExactlyInAnyOrder(2L, 3L);
        assertThat(nuevo.llevaRolParticipante()).isFalse();

        assertThat(NuevoUsuario.porAdmin("j", "j@x.com", "Temporal1", null, EstadoUsuario.BLOQUEADO, 7L, null)
                .estadoInicial()).isEqualTo(EstadoUsuario.BLOQUEADO);
    }

    @Test
    @DisplayName("desdeGoogle: activo y verificado, sin contraseña ni username, persona sin documento")
    void desdeGoogle() {
        NuevoUsuario nuevo = NuevoUsuario.desdeGoogle("juan@gmail.com", "sub-123", "Juan", "Pérez");

        assertThat(nuevo.origen()).isEqualTo(NuevoUsuario.Origen.GOOGLE);
        assertThat(nuevo.username()).isNull();
        assertThat(nuevo.password()).isNull();
        assertThat(nuevo.googleSub()).isEqualTo("sub-123");
        assertThat(nuevo.estadoInicial()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(nuevo.emailVerificado()).isTrue();
        assertThat(nuevo.datosPersona().tieneDocumento()).isFalse();
        assertThat(nuevo.llevaRolParticipante()).isTrue();
    }

    @Test
    @DisplayName("Combinaciones inválidas: sin contraseña, sin googleSub, persona por las dos vías")
    void combinacionesInvalidas() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NuevoUsuario.autoRegistro("jperez", "juan@example.com", " ", PERSONA));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NuevoUsuario.desdeGoogle("juan@gmail.com", null, "Juan", "Pérez"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NuevoUsuario(NuevoUsuario.Origen.ADMIN, "j", "j@x.com", "Temporal1", null, null,
                        EstadoUsuario.ACTIVO, true, PERSONA, 7L, Set.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NuevoUsuario(NuevoUsuario.Origen.ADMIN, "j", "j@x.com", "Temporal1", null, null,
                        EstadoUsuario.ACTIVO, true, null, null, Set.of()));
    }
}
