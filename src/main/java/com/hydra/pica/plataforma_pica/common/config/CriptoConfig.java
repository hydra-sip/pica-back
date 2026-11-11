package com.hydra.pica.plataforma_pica.common.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CriptoConfig.CriptoProperties.class)
public class CriptoConfig {

    /**
     * La pasarela de cargas en Base Sepolia (HU-609, PICA-333).
     *
     * @param rpcUrl                el nodo JSON-RPC con el que se leen las transacciones
     * @param contrato              la dirección de PasarelaCargas (PICA-328)
     * @param chainId               la red del contrato, para que el front pida esa
     * @param confirmacionesMinimas bloques que tiene que tener la transacción, contando el suyo, para acreditarla
     * @param timeout               lo que se espera al nodo antes de responder que la red no está disponible
     * @param weiPorPeso            la cotización fija de testnet; múltiplo de 100 para que un monto en centavos
     *                              dé una cantidad entera de wei
     * @param exploradorUrl         donde cualquiera ve la transacción de una carga
     */
    @ConfigurationProperties(prefix = "app.cripto")
    public record CriptoProperties(
            @DefaultValue("https://sepolia.base.org") String rpcUrl,
            @DefaultValue("0x36e633eDD1E79Ef2b9A3c6A099D873D52e44EDF0") String contrato,
            @DefaultValue("84532") long chainId,
            @DefaultValue("3") int confirmacionesMinimas,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("10000000000") BigInteger weiPorPeso,
            @DefaultValue("https://sepolia.basescan.org") String exploradorUrl) {

        private static final BigInteger CIEN = BigInteger.valueOf(100);

        public CriptoProperties {
            if (weiPorPeso.signum() <= 0 || weiPorPeso.mod(CIEN).signum() != 0) {
                throw new IllegalArgumentException(
                        "app.cripto.wei-por-peso tiene que ser un múltiplo positivo de 100, y es " + weiPorPeso);
            }
        }

        /** Lo que hay que pagar en el contrato por ese monto en pesos (con 2 decimales como mucho). */
        public BigInteger weiPara(BigDecimal pesos) {
            return pesos.movePointRight(2).toBigIntegerExact().multiply(weiPorPeso).divide(CIEN);
        }
    }
}
