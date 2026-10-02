package dev.patterncatalyst.monolith.notification;

import dev.patterncatalyst.monolith.common.NotificationDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
