package com.kb.wallet.ticket.dto;

public class BookingResult {
  private final Long userId;
  private final boolean success;
  private final String errorMessage;
  private final long processingTimeNanos;
  private final Long seatId;

  public BookingResult(int userId, boolean success, String errorMessage,
      long processingTimeNanos, Long seatId) {
    this.userId = (long) userId;
    this.success = success;
    this.errorMessage = errorMessage;
    this.processingTimeNanos = processingTimeNanos;
    this.seatId = seatId;
  }

  public Long getUserId() {
    return userId;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public long getProcessingTimeNanos() {
    return processingTimeNanos;
  }

  public Long getSeatId() {
    return seatId;
  }
}