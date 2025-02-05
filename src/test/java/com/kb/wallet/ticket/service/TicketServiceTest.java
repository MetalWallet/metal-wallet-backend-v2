package com.kb.wallet.ticket.service;

import com.kb.wallet.global.common.status.ErrorCode;
import com.kb.wallet.global.config.AppConfig;
import com.kb.wallet.global.exception.CustomException;
import com.kb.wallet.member.domain.Member;
import com.kb.wallet.member.service.MemberService;
import com.kb.wallet.musical.domain.Musical;
import com.kb.wallet.seat.domain.Seat;
import com.kb.wallet.seat.service.SeatService;
import com.kb.wallet.ticket.constant.TicketStatus;
import com.kb.wallet.ticket.domain.Schedule;
import com.kb.wallet.ticket.domain.Ticket;
import com.kb.wallet.ticket.dto.request.TicketRequest;
import com.kb.wallet.ticket.dto.response.TicketListResponse;
import com.kb.wallet.ticket.dto.response.TicketResponse;
import com.kb.wallet.ticket.repository.TicketRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {AppConfig.class})
@Transactional
@ActiveProfiles("test")
class TicketServiceTest {

  private static final Logger log = LoggerFactory.getLogger(TicketServiceTest.class);
  @InjectMocks
  private TicketServiceImpl ticketService;

  @Mock
  private TicketRepository ticketRepository;

  @Mock
  private MemberService memberService;

  @Mock
  private SeatService seatService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private Member member;
  private Musical musical;
  private Seat seat1;
  private Seat seat2;
  private Schedule schedule;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    member = new Member();
    member.setEmail("test@example.com");
    musical = mock(Musical.class);
    seat1 = mock(Seat.class);
    seat2 = mock(Seat.class);
    schedule = mock(Schedule.class);

    when(schedule.getDate()).thenReturn(LocalDate.of(2024, 11, 15));
    when(schedule.getStartTime()).thenReturn(LocalTime.of(19, 0));
    when(seat1.getSchedule()).thenReturn(schedule);
    when(seat2.getSchedule()).thenReturn(schedule);
    when(schedule.getMusical()).thenReturn(musical);

    cleanUpAll();
    initializeTestData();
  }

  @AfterEach
  void tearDown() {
    cleanUpAll();
  }

  private void initializeTestData() {
    for (int i = 1; i <= 10; i++) {
      jdbcTemplate.execute(
          String.format(
              "INSERT INTO member (id, email, is_activated, name, password, phone, pin_number, role) "
                  +
                  "VALUES (%d, 'test%d@test.com', true, 'test%d', 'password%d', '0101111%04d', '111111', 'USER')",
              i, i, i, i, i)
      );
    }

    jdbcTemplate.execute(
        "INSERT INTO musical (id, place, place_detail, ranking, running_time, ticketing_end_date, "
            +
            "ticketing_start_date, title) VALUES " +
            "(1, '서울', '서울 아트센터', 1, 150, '2024-10-16', '2024-10-01', '킹키부츠')"
    );

    jdbcTemplate.execute(
        "INSERT INTO schedule (id, date, start_time, end_time, musical_id) VALUES " +
            "(1, '2024-10-17', '10:00:00', '12:30:00', 1)"
    );

    jdbcTemplate.execute(
        "INSERT INTO section (id, available_seats, grade, price, musical_id, schedule_id) VALUES " +
            "(1, 50, 'R', 190000, 1, 1)"
    );

    for (int i = 1; i <= 10; i++) {
      jdbcTemplate.execute(
          String.format(
              "INSERT INTO seat (id, is_available, seat_no, schedule_id, section_id) VALUES " +
                  "(%d, true, %d, 1, 1)", i, i)
      );
    }
  }

  private void cleanUpAll() {
    jdbcTemplate.execute("DELETE FROM ticket");
    jdbcTemplate.execute("DELETE FROM seat");
    jdbcTemplate.execute("DELETE FROM section");
    jdbcTemplate.execute("DELETE FROM schedule");
    jdbcTemplate.execute("DELETE FROM musical");
    jdbcTemplate.execute("DELETE FROM member");
  }

  @Test
  @DisplayName("티켓 조회 성공")
  void getTicket_Success() {
    Ticket ticket = Ticket.builder()
        .id(1L)
        .member(member)
        .musical(musical)
        .ticketStatus(TicketStatus.BOOKED)
        .seat(seat1)
        .validUntil(LocalDateTime.now().plusDays(7))
        .cancelUntil(LocalDateTime.now().minusDays(7))
        .deviceId("device123")
        .build();

    when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

    Ticket result = ticketService.getTicket(1L);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(1L);
    verify(ticketRepository, times(1)).findById(1L);
  }

  @Test
  @DisplayName("티켓 조회 실패 - 티켓 미발견")
  void getTicket_NotFound() {

    when(ticketRepository.findById(1L)).thenReturn(Optional.empty());

    CustomException exception = assertThrows(CustomException.class,
        () -> ticketService.getTicket(1L));
    assertThat(exception.getMessage()).isEqualTo("티켓을 찾을 수 없습니다.");
  }

  @Test
  @DisplayName("티켓 예약 실패 - 좌석 이미 예약됨")
  void bookTicket_SeatAlreadyBooked() {
    TicketRequest request = new TicketRequest();
    request.setSeatId(Collections.singletonList(1L));
    request.setDeviceId("device123");

    when(memberService.getMemberByEmail("test@example.com")).thenReturn(member);
    when(seatService.getSeatById(1L)).thenReturn(seat1);
    doThrow(new CustomException(ErrorCode.TICKET_NOT_FOUND_ERROR)).when(seat1)
        .checkSeatAvailability();

    CustomException exception = assertThrows(CustomException.class,
        () -> ticketService.bookTicket("test@example.com", request));
    assertThat(exception.getMessage()).isEqualTo("티켓을 찾을 수 없습니다.");
  }

  @Test
  @DisplayName("티켓 취소 성공")
  void cancelTicket_Success() {
    Long ticketId = 1L;

    Ticket ticket = Ticket.builder()
        .id(ticketId)
        .member(member)
        .ticketStatus(TicketStatus.BOOKED)
        .build();

    when(ticketRepository.findByTicketIdAndEmail(ticketId, member.getEmail())).thenReturn(
        Optional.of(ticket));

    ticketService.cancelTicket("test@example.com", ticketId);

    assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.CANCELED);
    verify(ticketRepository, times(1)).save(ticket);
  }

  @Test
  @DisplayName("티켓 취소 실패 - 존재하지 않는 티켓")
  void cancelTicket_NotFound() {
    Long ticketId = 1L;

    when(ticketRepository.findByTicketIdAndEmail(ticketId, "test@example.com")).thenReturn(
        Optional.empty());

    CustomException exception = assertThrows(CustomException.class,
        () -> ticketService.cancelTicket("test@example.com", ticketId));
    assertThat(exception.getMessage()).isEqualTo("티켓을 찾을 수 없습니다.");
  }

  @Test
  @DisplayName("사용자 티켓 목록 조회 성공")
  void getTickets_Success() {
    TicketListResponse response = TicketListResponse.builder()
        .id(1L)
        .ticketStatus(TicketStatus.BOOKED)
        .build();
    List<TicketListResponse> responses = Collections.singletonList(response);

    Pageable pageable = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(ticketRepository.findAllByMemberAndTicketStatus("test@example.com", TicketStatus.BOOKED,
        null, pageable))
        .thenReturn(responses);

    List<TicketListResponse> result = ticketService.getTickets("test@example.com",
        TicketStatus.BOOKED, 0, 10, null);

    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(1);
    assertThat(result.get(0).getId()).isEqualTo(response.getId());
  }

  @Test
  @DisplayName("티켓 예약 성공")
  void bookTicket_Success() {
    TicketRequest request = new TicketRequest();
    request.setSeatId(Arrays.asList(1L, 2L));
    request.setDeviceId("device123");

    when(memberService.getMemberByEmail("test@example.com")).thenReturn(member);
    when(seatService.getSeatById(1L)).thenReturn(seat1);
    when(seatService.getSeatById(2L)).thenReturn(seat2);

    LocalDateTime now = LocalDateTime.now();
    Ticket ticket1 = Ticket.builder()
        .id(1L)
        .createdAt(now)
        .validUntil(now.plusHours(2))
        .cancelUntil(now.minusDays(7))
        .ticketStatus(TicketStatus.BOOKED)
        .deviceId("device123")
        .build();

    Ticket ticket2 = Ticket.builder()
        .id(2L)
        .createdAt(now)
        .validUntil(now.plusHours(2))
        .cancelUntil(now.minusDays(7))
        .ticketStatus(TicketStatus.BOOKED)
        .deviceId("device123")
        .build();

    when(ticketRepository.save(any(Ticket.class))).thenReturn(ticket1, ticket2);

    List<TicketResponse> responses = ticketService.bookTicket("test@example.com", request);

    assertThat(responses).isNotNull();
    assertThat(responses.size()).isEqualTo(2);
    verify(seat1, times(1)).checkSeatAvailability();
    verify(seat2, times(1)).checkSeatAvailability();
    verify(seat1, times(1)).updateSeatAvailability();
    verify(seat2, times(1)).updateSeatAvailability();
    verify(schedule, times(2)).getMusical();
    verify(seatService, times(1)).getSeatById(1L);
    verify(seatService, times(1)).getSeatById(2L);
  }

  @Test
  @DisplayName("10명이 동시에 서로 다른 좌석을 예매할 경우, 모든 예매가 성공해야 한다")
  void testConcurrentDifferentSeatBooking() throws InterruptedException {
    int numberOfThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
    CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

    CopyOnWriteArrayList<Long> bookingOrder = new CopyOnWriteArrayList<>();

    log.info("===== 동시 예매 테스트 시작 (서로 다른 좌석) =====");
    log.info("테스트 설정:");
    log.info("- 총 시도 사용자 수: {} 명", numberOfThreads);
    log.info("- 예상 성공 건수: {} 건 (전체 성공)", numberOfThreads);

    for (int i = 1; i <= numberOfThreads; i++) {
      final int userId = i;
      final long seatId = i;
      final String userEmail = String.format("test%d@test.com", userId);

      executor.submit(() -> {
        try {
          TicketRequest request = new TicketRequest();
          request.setSeatId(Collections.singletonList(seatId));
          request.setDeviceId("device" + userId);

          readyLatch.countDown();
          startLatch.await();

          long startTime = System.nanoTime();
          try {
            List<TicketResponse> response = ticketService.bookTicket(userEmail, request);
            long endTime = System.nanoTime();

            bookingOrder.add((long) userId);
          } catch (Exception e) {
            long endTime = System.nanoTime();

          }
        } catch (Exception e) {
          log.error("예상치 못한 오류 발생 - 사용자: {}, 좌석: {}, 오류: {}",
              userEmail, seatId, e.getMessage());
        } finally {
          doneLatch.countDown();
        }
      });
    }

    readyLatch.await();
    log.info("모든 사용자 준비 완료. 동시 예매 시작...");

    long testStartTime = System.nanoTime();
    startLatch.countDown();

    boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
    long testEndTime = System.nanoTime();

    if (!completed) {
      log.error("테스트 타임아웃 발생 (10초 초과)");
    }

    executor.shutdown();

    log.info("테스트 결과 분석 시작");
    log.info("데이터베이스 상태 검증 시작");
    for (long seatId = 1; seatId <= numberOfThreads; seatId++) {
      log.info("좌석 {} 상태 검증 중...", seatId);
      verifyDatabaseState(seatId);
    }
    log.info("===== 테스트 종료 =====");
  }

  @Test
  @DisplayName("좌석 상태와 예매 정보 불일치 검증")
  void testSeatStatusAndTicketMismatch() {

    Long targetSeatId = 1L;
    log.info("좌석 ID {} 예약 불가능 상태로 설정", targetSeatId);
    jdbcTemplate.update("UPDATE seat SET is_available = false WHERE id = ?", targetSeatId);

    TicketRequest request = new TicketRequest();
    request.setSeatId(Collections.singletonList(targetSeatId));
    request.setDeviceId("device1");

    log.info("사용자 test1@test.com이 좌석 ID {}의 예매 시도", targetSeatId);

    CustomException exception = assertThrows(CustomException.class, () -> {
      ticketService.bookTicket("test1@test.com", request);
    });

    log.error("예매 실패 - 좌석 ID {}: {}", targetSeatId, exception.getMessage());
  }


  private void verifyDatabaseState(Long seatId) {
    Integer ticketCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ticket WHERE seat_id = ?",
        Integer.class,
        seatId
    );
    assertEquals(1, ticketCount,
        String.format("Seat %d should have exactly one ticket", seatId));

    Boolean seatAvailable = jdbcTemplate.queryForObject(
        "SELECT is_available FROM seat WHERE id = ?",
        Boolean.class,
        seatId
    );
    assertFalse(seatAvailable,
        String.format("Seat %d should not be available after booking", seatId));
  }
}
