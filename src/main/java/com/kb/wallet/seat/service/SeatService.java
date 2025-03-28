package com.kb.wallet.seat.service;

import com.kb.wallet.seat.domain.Seat;

public interface SeatService {

  Seat getSeatById(Long seatId);
  Seat getSeatByIdWithLock(Long seatId);
}
