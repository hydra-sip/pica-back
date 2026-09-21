package com.hydra.pica.plataforma_pica.common.security;

import java.util.Optional;

public interface CurrentUserProvider {

    Optional<Long> getCurrentUserId();
}
