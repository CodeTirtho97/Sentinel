package com.sentinel.slo;

import java.util.UUID;

public class SloNotFoundException extends RuntimeException {

    public SloNotFoundException(UUID id) {
        super("no SLO with id " + id);
    }
}
