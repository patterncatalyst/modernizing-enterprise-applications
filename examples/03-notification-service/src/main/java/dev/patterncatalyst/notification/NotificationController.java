package dev.patterncatalyst.notification;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LIFTED UNCHANGED from
 * {@code dev.patterncatalyst.monolith.notification.NotificationController}
 * (ch.17 Phase A, DRQ-035). {@code @RestController}/{@code @RequestMapping}/
 * {@code @GetMapping}/{@code @RequestParam} are all supported as-is by
 * {@code quarkus-spring-web}, reproducing the exact same
 * {@code GET /api/notifications?customerId=...} contract the strangler proxy
 * (S6) will route to once {@code strangler.notification.enabled} flips — the
 * behavior-equivalence suite's "Notification Context Contract" folder targets
 * this endpoint unchanged.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationDto> listByCustomerId(@RequestParam Long customerId) {
        return service.listByCustomerId(customerId);
    }
}
