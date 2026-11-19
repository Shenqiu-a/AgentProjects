package com.example.aftersales.order;

import com.example.aftersales.platform.ServiceApiException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static com.example.aftersales.order.OrderDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final EligibilityService eligibility = mock(EligibilityService.class);
    private final ActiveTicketClient tickets = mock(ActiveTicketClient.class);
    private final OrderService service = new OrderService(orders, eligibility, tickets);

    @Test void inaccessibleOrderStopsBeforeRemoteQuery() {
        UUID alice = UUID.randomUUID(), otherOrder = UUID.randomUUID();
        when(orders.findOwned(alice, otherOrder)).thenReturn(Optional.empty());
        var error = assertThrows(ServiceApiException.class, () -> service.detail(alice, otherOrder));
        assertEquals("ORDER_NOT_FOUND", error.code());
        verifyNoInteractions(tickets, eligibility);
    }
    @Test void blankOrOverlongOrderNumberIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.list(UUID.randomUUID(), new PageQuery(1, 20), "  ", null));
        assertThrows(IllegalArgumentException.class, () -> service.list(UUID.randomUUID(), new PageQuery(1, 20), "x".repeat(65), null));
        verifyNoInteractions(orders);
    }
    @Test void paginationCannotOverflowAnInt() {
        assertEquals(214748364600L, new PageQuery(Integer.MAX_VALUE, 100).offset());
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(0, 20));
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(1, 101));
    }
}