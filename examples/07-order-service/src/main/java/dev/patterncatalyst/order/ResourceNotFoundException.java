package dev.patterncatalyst.order;

/** Lifted byte-for-byte from the monolith's {@code common.exception.ResourceNotFoundException}. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
