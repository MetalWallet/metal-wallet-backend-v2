package com.kb.wallet.ticket.controller;

import com.kb.wallet.global.common.response.ApiResponse;
import com.kb.wallet.global.common.response.CursorResponse;
import com.kb.wallet.member.domain.Member;
import com.kb.wallet.ticket.constant.TicketStatus;
import com.kb.wallet.ticket.dto.request.EncryptRequest;
import com.kb.wallet.ticket.dto.request.TicketRequest;
import com.kb.wallet.ticket.dto.request.VerifyTicketRequest;
import com.kb.wallet.ticket.dto.response.ProposedEncryptResponse;
import com.kb.wallet.ticket.dto.response.TicketListResponse;
import com.kb.wallet.ticket.dto.response.TicketResponse;
import com.kb.wallet.ticket.service.TicketService;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {

  private final TicketService ticketService;

  @PostMapping
  public ApiResponse<List<TicketResponse>> createTicket(
      @AuthenticationPrincipal Member member,
      @RequestBody @Valid TicketRequest ticketRequest) {
    List<TicketResponse> tickets = ticketService.bookTicket(member.getEmail(), ticketRequest);
    return ApiResponse.created(tickets);
  }

  @GetMapping
  public ApiResponse<CursorResponse<TicketListResponse>> getUserTickets(
      @AuthenticationPrincipal Member member,
      @RequestParam(name = "cursor", required = false) Long cursor,
      @RequestParam(name = "size", defaultValue = "10") int size,
      @RequestParam(name = "status", required = false) String status) {
    TicketStatus ticketStatus = TicketStatus.convertToTicketStatus(status);

    List<TicketListResponse> tickets;
    Long nextCursor = null;

    tickets = ticketService.getTickets(member.getEmail(),
        ticketStatus, 0,
        size, cursor);
    if (!tickets.isEmpty()) {
      nextCursor = tickets.get(tickets.size() - 1).getId();
    }
    CursorResponse<TicketListResponse> cursorResponse = new CursorResponse<>(tickets, nextCursor);
    return ApiResponse.ok(cursorResponse);
  }

  @PostMapping("encrypt/{ticketId}")
  public ResponseEntity<ProposedEncryptResponse> generateEncryptData(
      @AuthenticationPrincipal Member member,
      @PathVariable(name = "ticketId") Long ticketId,
      @RequestBody EncryptRequest encryptRequest) {
    ProposedEncryptResponse response = ticketService.provideEncryptElement(ticketId,
        member.getEmail(), encryptRequest);
    return ResponseEntity.ok(response);
  }

  @PutMapping("/use")
  public ResponseEntity<Void> updateTicket(
      @AuthenticationPrincipal Member member,
      @RequestBody VerifyTicketRequest request) {
    ticketService.updateToCheckedStatus(request);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/{ticketId}")
  public ApiResponse<Void> cancelTicket(
      @AuthenticationPrincipal Member member, @PathVariable(name = "ticketId") long ticketId) {
    ticketService.cancelTicket(member.getEmail(), ticketId);
    return ApiResponse.ok();
  }
}