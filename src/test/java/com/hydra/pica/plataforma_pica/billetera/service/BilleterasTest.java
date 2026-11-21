package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BilleterasTest {

    @Mock private BilleteraRepository billeteraRepository;
    @Mock private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("Billetera.nuevaPara inicializa saldos en cero, moneda ARS y estado ACTIVA")
    void nuevaParaInicializaValoresPorDefecto() {
        Usuario usuario = new Usuario();
        Billetera billetera = Billetera.nuevaPara(usuario);

        assertThat(billetera.getUsuario()).isSameAs(usuario);
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getSaldoRetenido()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getGananciaAcumulada()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getMoneda()).isEqualTo(Billetera.MONEDA_POR_DEFECTO);
        assertThat(billetera.getEstado()).isEqualTo(EstadoBilletera.ACTIVA);
    }

    @Test
    @DisplayName("obtenerOCrear devuelve la billetera existente sin crear otra")
    void obtenerOCrearDevuelveExistente() {
        Billetera existente = new Billetera();
        when(billeteraRepository.findByUsuarioId(10L)).thenReturn(Optional.of(existente));

        Billetera resultado = Billeteras.obtenerOCrear(billeteraRepository, usuarioRepository, 10L);

        assertThat(resultado).isSameAs(existente);
        verify(billeteraRepository, never()).save(any());
        verify(usuarioRepository, never()).getReferenceById(any());
    }

    @Test
    @DisplayName("obtenerOCrear crea y guarda una billetera nueva cuando falta")
    void obtenerOCrearCreaCuandoFalta() {
        Usuario usuario = new Usuario();
        when(billeteraRepository.findByUsuarioId(10L)).thenReturn(Optional.empty());
        when(usuarioRepository.getReferenceById(10L)).thenReturn(usuario);
        when(billeteraRepository.save(any(Billetera.class))).thenAnswer(inv -> inv.getArgument(0));

        Billetera resultado = Billeteras.obtenerOCrear(billeteraRepository, usuarioRepository, 10L);

        assertThat(resultado.getUsuario()).isSameAs(usuario);
        assertThat(resultado.getMoneda()).isEqualTo("ARS");
        assertThat(resultado.getEstado()).isEqualTo(EstadoBilletera.ACTIVA);
        verify(billeteraRepository).save(any(Billetera.class));
    }

    @Test
    @DisplayName("obtenerOCrearBloqueada devuelve la existente bloqueada sin guardar")
    void obtenerOCrearBloqueadaDevuelveExistente() {
        Billetera existente = new Billetera();
        when(billeteraRepository.findConBloqueoPorUsuarioId(10L)).thenReturn(Optional.of(existente));

        Billetera resultado = Billeteras.obtenerOCrearBloqueada(billeteraRepository, usuarioRepository, 10L);

        assertThat(resultado).isSameAs(existente);
        verify(billeteraRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("obtenerOCrearBloqueada crea con saveAndFlush y reintenta lectura bloqueada cuando falta")
    void obtenerOCrearBloqueadaCreaYReintentaBloqueo() {
        Usuario usuario = new Usuario();
        Billetera bloqueada = new Billetera();
        bloqueada.setUsuario(usuario);
        when(billeteraRepository.findConBloqueoPorUsuarioId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(bloqueada));
        when(usuarioRepository.getReferenceById(10L)).thenReturn(usuario);

        Billetera resultado = Billeteras.obtenerOCrearBloqueada(billeteraRepository, usuarioRepository, 10L);

        assertThat(resultado).isSameAs(bloqueada);
        verify(billeteraRepository).saveAndFlush(any(Billetera.class));
    }
}
