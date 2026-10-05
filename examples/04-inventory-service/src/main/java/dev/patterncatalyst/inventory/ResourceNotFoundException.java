package dev.patterncatalyst.inventory;

/**
 * LIFTED UNCHANGED from
 * {@code dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException}
 * (r05/ch.19 S5, Phase A).
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
