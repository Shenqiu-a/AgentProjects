package com.example.aftersales.agent;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/internal/tickets")
public class InternalTicketController {
    private final ActiveTicketRepository tickets;
    public InternalTicketController(ActiveTicketRepository tickets) { this.tickets = tickets; }

    @GetMapping("/active")
    public ActiveTicketView active(@RequestParam UUID userId, @RequestParam UUID orderId) {
        return new ActiveTicketView(tickets.exists(userId, orderId));
    }

    public record ActiveTicketView(boolean hasActiveTicket) {}
}
