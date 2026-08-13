package com.sentinel.incident;

import java.util.UUID;

public class IncidentNotFoundException extends RuntimeException {

    public IncidentNotFoundException(UUID id) {
        super("no incident with id " + id);
    }
}
