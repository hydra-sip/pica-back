package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.CodigoCanjeOAuth;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.CodigoCanjeOAuthRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthCodeStoreTest {

    @Mock
    private CodigoCanjeOAuthRepository codigoCanjeOAuthRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    private OAuthCodeStore store;
    private final Map<String, CodigoCanjeOAuth> db = new HashMap<>();

    @BeforeEach
    void setUp() {
        db.clear();
        store = new OAuthCodeStore(codigoCanjeOAuthRepository, usuarioRepository);
    }

    private void configurarMocks() {
        when(usuarioRepository.getReferenceById(any())).thenAnswer(invocation -> {
            Usuario u = new Usuario();
            org.springframework.test.util.ReflectionTestUtils.setField(u, "id", (Long) invocation.getArgument(0));
            return u;
        });
        when(codigoCanjeOAuthRepository.save(any(CodigoCanjeOAuth.class))).thenAnswer(invocation -> {
            CodigoCanjeOAuth c = invocation.getArgument(0);
            db.put(c.getCodigoHash(), c);
            return c;
        });
        when(codigoCanjeOAuthRepository.findByCodigoHash(any())).thenAnswer(invocation -> {
            String hash = invocation.getArgument(0);
            return Optional.ofNullable(db.get(hash));
        });
    }

    @Test
    @DisplayName("Genera código y lo consume exitosamente")
    void generarYConsumirCodigo() {
        configurarMocks();
        String code = store.generarCodigo(42L);

        assertThat(code).isNotBlank();
        Long usuarioId = store.consumirCodigo(code);

        assertThat(usuarioId).isEqualTo(42L);
    }

    @Test
    @DisplayName("El código es de un solo uso (el segundo intento falla)")
    void codigoEsDeUnSoloUso() {
        configurarMocks();
        String code = store.generarCodigo(42L);

        assertThat(store.consumirCodigo(code)).isEqualTo(42L);
        assertThat(store.consumirCodigo(code)).isNull();
    }

    @Test
    @DisplayName("Código expirado devuelve null")
    void codigoExpiradoDevuelveNull() {
        configurarMocks();
        String code = store.generarCodigo(42L);
        // Expirar manualmente el código en la BD simulada
        db.values().forEach(c -> c.setVenceEn(Instant.now().minusSeconds(10)));

        assertThat(store.consumirCodigo(code)).isNull();
    }

    @Test
    @DisplayName("Código inexistente o nulo devuelve null")
    void codigoInexistenteODevuelveNull() {
        assertThat(store.consumirCodigo(null)).isNull();
        assertThat(store.consumirCodigo("   ")).isNull();
        assertThat(store.consumirCodigo("codigo-fantasma")).isNull();
    }
}
