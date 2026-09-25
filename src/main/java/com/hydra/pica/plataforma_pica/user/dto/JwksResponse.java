package com.hydra.pica.plataforma_pica.user.dto;

import java.util.List;

public record JwksResponse(
        List<JwkKeyDto> keys
) {}
