package com.kb.wallet.ticket.exception;

public class TicketException {
  public class AlreadyBookedException extends RuntimeException {
    public AlreadyBookedException(String message) {
      super(message);
    }
  }

  public class SeatStatusMismatchException extends RuntimeException {
    public SeatStatusMismatchException(String message) {
      super(message);
    }
  }

  public class TicketCancellationException extends RuntimeException {
    public TicketCancellationException(String message) {
      super(message);
    }
  }

  public class PaymentProcessingException extends RuntimeException {
    public PaymentProcessingException(String message) {
      super(message);
    }
  }
}
