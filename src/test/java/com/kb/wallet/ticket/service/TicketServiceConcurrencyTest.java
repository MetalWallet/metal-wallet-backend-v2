package com.kb.wallet.ticket.service;

import static org.junit.jupiter.api.Assertions.*;

import com.kb.wallet.global.config.AppConfig;
import com.kb.wallet.global.exception.CustomException;
import com.kb.wallet.ticket.dto.request.TicketRequest;
import com.kb.wallet.ticket.dto.response.TicketResponse;
import com.kb.wallet.ticket.exception.TicketException.AlreadyBookedException;
import com.kb.wallet.ticket.exception.TicketException.SeatStatusMismatchException;
import com.kb.wallet.ticket.exception.TicketException.TicketCancellationException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import com.kb.wallet.ticket.dto.BookingResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {AppConfig.class})
@WebAppConfiguration
@ActiveProfiles("test")
class TicketServiceConcurrencyTest {

  private static final Logger log = LoggerFactory.getLogger(TicketServiceConcurrencyTest.class);

  @Autowired
  private TicketService ticketService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    cleanUpAll();
    initializeTestData();
  }

  @AfterEach
  void tearDown() {
    cleanUpAll();
  }

  private void initializeTestData() {
    // 테스트용 회원 50명 생성
    for (int i = 1; i <= 50; i++) {
      jdbcTemplate.execute(
          String.format(
              "INSERT INTO member (id, email, is_activated, name, password, phone, pin_number, role) "
                  +
                  "VALUES (%d, 'test%d@test.com', true, 'test%d', 'password%d', '0101111%04d', '111111', 'USER')",
              i, i, i, i, i)
      );
    }

    // 뮤지컬 데이터 삽입
    jdbcTemplate.execute(
        "INSERT INTO musical (id, place, place_detail, ranking, running_time, ticketing_end_date, "
            +
            "ticketing_start_date, title) VALUES " +
            "(1, '서울', '서울 아트센터', 1, 150, '2024-10-16', '2024-10-01', '킹키부츠')"
    );

    // 스케줄 데이터 삽입
    jdbcTemplate.execute(
        "INSERT INTO schedule (id, date, start_time, end_time, musical_id) VALUES " +
            "(1, '2024-10-17', '10:00:00', '12:30:00', 1)"
    );

    // 섹션 데이터 삽입
    jdbcTemplate.execute(
        "INSERT INTO section (id, available_seats, grade, price, musical_id, schedule_id) VALUES " +
            "(1, 50, 'R', 190000, 1, 1)"
    );

    // 50개의 좌석 데이터 삽입
    for (int i = 1; i <= 50; i++) {
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
  @DisplayName("결제 성공 후 예매 정보 저장 실패 시나리오")
  @Transactional
  void testPaymentSuccessButBookingFail() {
    // Given
    Long targetSeatId = 1L;
    String userEmail = "test1@test.com";

    TicketRequest request = new TicketRequest();
    request.setSeatId(Collections.singletonList(targetSeatId));
    request.setDeviceId("device1");

    log.info("===== 결제 성공 후 예매 정보 저장 실패 시나리오 테스트 시작 =====");
    log.info("예매 시도 정보:");
    log.info("- 사용자: {}", userEmail);
    log.info("- 좌석 번호: {}", targetSeatId);
    log.info("- 디바이스 ID: {}", request.getDeviceId());

    // When & Then
    // 1. 먼저 정상적인 좌석이 있는지 확인
    Boolean initialSeatExists = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) > 0 FROM seat WHERE id = ?",
        Boolean.class,
        targetSeatId
    );
    assertTrue(initialSeatExists, "테스트 데이터 좌석이 존재해야 함");
    log.info("좌석 상태 확인:");
    log.info("- 좌석 ID {} 존재 여부: {}", targetSeatId, initialSeatExists ? "존재" : "없음");

    // 2. 좌석을 예매 불가능한 상태로 만들기
    jdbcTemplate.update(
        "UPDATE seat SET is_available = false WHERE id = ?",
        targetSeatId
    );
    log.info("좌석 상태 변경:");
    log.info("- 좌석 ID {}를 예매 불가능 상태로 변경 완료", targetSeatId);

    // 3. 예매 시도
    log.info("예매 시도:");
    log.info("- 사용자 {}가 좌석 {}번 예매 시도 중...", userEmail, targetSeatId);

    CustomException exception = assertThrows(CustomException.class, () -> {
      ticketService.bookTicket(userEmail, request);
    });
    assertEquals("이미 예약된 좌석입니다.", exception.getMessage());

    log.error("예매 실패 상세:");
    log.error("- 실패 사유: {}", exception.getMessage());
    log.error("- 예매 시도 사용자: {}", userEmail);
    log.error("- 예매 시도 좌석: {}", targetSeatId);

    // 4. 데이터 정합성 검증
    Integer ticketCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ticket WHERE seat_id = ?",
        Integer.class,
        targetSeatId
    );
    assertEquals(0, ticketCount, "실패한 예매에 대한 티켓이 생성되지 않아야 함");

    log.info("데이터 정합성 검증 결과:");
    log.info("- 좌석 ID: {}", targetSeatId);
    log.info("- 발급된 티켓 수: {}", ticketCount);
    log.info("- 검증 결과: {}", ticketCount == 0 ? "정상 (티켓이 생성되지 않음)" : "비정상 (티켓이 잘못 생성됨)");
    log.info("===== 테스트 종료 =====");
  }

  @Test
  @DisplayName("결제 완료 후 동시 티켓 할당으로 인한 중복 예매 발생 시나리오")
  void testDoubleBookingAfterPayment() throws InterruptedException {
    // Given
    Long targetSeatId = 1L;
    String firstUser = "test1@test.com";
    String secondUser = "test2@test.com";

    log.info("===== 결제 순서와 티켓 할당 순서 불일치 시나리오 테스트 시작 =====");
    log.info("테스트 설정 정보:");
    log.info("- 대상 좌석 ID: {}", targetSeatId);
    log.info("- 첫 번째 결제 사용자: {}", firstUser);
    log.info("- 두 번째 결제 사용자: {}", secondUser);

    // 1. 첫 번째 사용자 결제 시뮬레이션
    Long firstUserId = jdbcTemplate.queryForObject(
        "SELECT id FROM member WHERE email = ?",
        Long.class,
        firstUser
    );

    log.info("첫 번째 사용자 결제 처리:");
    log.info("- 사용자: {} (ID: {})", firstUser, firstUserId);
    log.info("- 결제 상태: 완료");
    log.info("- 티켓 할당 상태: 대기중");

    // 동시성 제어를 위한 래치 추가
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);

    // 결과 기록을 위한 원자적 참조
    AtomicReference<String> firstUserResult = new AtomicReference<>("미시도");
    AtomicReference<String> secondUserResult = new AtomicReference<>("미시도");

    // 두 스레드에서 동시에 티켓 예매 시도
    ExecutorService executorService = Executors.newFixedThreadPool(2);

    // 첫 번째 사용자 티켓 예매 태스크
    Runnable firstUserBookingTask = () -> {
      try {
        log.info("첫 번째 사용자 스레드 시작: 동시 시작 대기 중");
        startLatch.await(); // 동시 시작 대기

        TicketRequest firstRequest = new TicketRequest();
        firstRequest.setSeatId(Collections.singletonList(targetSeatId));
        firstRequest.setDeviceId("device1");

        log.info("첫 번째 사용자 티켓 할당 시도:");
        log.info("- 사용자: {}", firstUser);
        log.info("- 대상 좌석: {}", targetSeatId);
        log.info("- 결제 완료 시점: 두 번째 사용자보다 먼저 완료");

        try {
          // 의도적인 지연 추가
          Thread.sleep(100);

          List<TicketResponse> tickets = ticketService.bookTicket(firstUser, firstRequest);

          log.info("첫 번째 사용자 티켓 발급 성공:");
          log.info("- 발급된 티켓 ID: {}", tickets.get(0).getId());
          log.info("- 할당된 좌석: {}", targetSeatId);

          firstUserResult.set("성공");
        } catch (CustomException e) {
          log.error("첫 번째 사용자 티켓 할당 실패:");
          log.error("- 에러 메시지: {}", e.getMessage());
          log.error("- 시도한 사용자: {}", firstUser);
          log.error("- 티켓 할당 시도 좌석: {}", targetSeatId);

          firstUserResult.set("실패: " + e.getMessage());
        } finally {
          log.info("첫 번째 사용자 스레드 종료");
          doneLatch.countDown();
        }
      } catch (InterruptedException e) {
        log.error("첫 번째 사용자 스레드 인터럽트 발생");
        Thread.currentThread().interrupt();
        doneLatch.countDown();
      }
    };

    // 두 번째 사용자 티켓 예매 태스크
    Runnable secondUserBookingTask = () -> {
      try {
        log.info("두 번째 사용자 스레드 시작: 동시 시작 대기 중");
        startLatch.await(); // 동시 시작 대기

        TicketRequest secondRequest = new TicketRequest();
        secondRequest.setSeatId(Collections.singletonList(targetSeatId));
        secondRequest.setDeviceId("device2");

        log.info("두 번째 사용자 예매 시도:");
        log.info("- 사용자: {}", secondUser);
        log.info("- 대상 좌석: {}", targetSeatId);

        try {
          List<TicketResponse> tickets = ticketService.bookTicket(secondUser, secondRequest);

          log.info("두 번째 사용자 티켓 발급 완료:");
          log.info("- 발급된 티켓 ID: {}", tickets.get(0).getId());
          log.info("- 할당된 좌석: {}", targetSeatId);

          secondUserResult.set("성공");
        } catch (CustomException e) {
          log.error("두 번째 사용자 티켓 할당 실패:");
          log.error("- 에러 메시지: {}", e.getMessage());
          log.error("- 시도한 사용자: {}", secondUser);
          log.error("- 티켓 할당 시도 좌석: {}", targetSeatId);

          secondUserResult.set("실패: " + e.getMessage());
        } finally {
          log.info("두 번째 사용자 스레드 종료");
          doneLatch.countDown();
        }
      } catch (InterruptedException e) {
        log.error("두 번째 사용자 스레드 인터럽트 발생");
        Thread.currentThread().interrupt();
        doneLatch.countDown();
      }
    };

    // 태스크 제출
    log.info("스레드 풀에 태스크 제출");
    executorService.submit(firstUserBookingTask);
    executorService.submit(secondUserBookingTask);

    // 동시 시작
    log.info("동시 시작 신호 발생");
    startLatch.countDown();

    // 모든 작업 완료 대기
    log.info("모든 작업 완료 대기 시작");
    doneLatch.await(10, TimeUnit.SECONDS);
    executorService.shutdown();

    // 최종 상태 검증
    Map<String, Object> finalTicketInfo = jdbcTemplate.queryForMap(
        "SELECT t.id, m.email, s.is_available " +
            "FROM ticket t " +
            "JOIN member m ON t.member_id = m.id " +
            "JOIN seat s ON t.seat_id = s.id " +
            "WHERE s.id = ?",
        targetSeatId
    );

    log.info("최종 상태 검증:");
    log.info("- 좌석 ID: {}", targetSeatId);
    log.info("- 티켓 보유자: {}", finalTicketInfo.get("email"));
    log.info("- 좌석 상태: {}", (Boolean) finalTicketInfo.get("is_available") ? "예약 가능" : "예약 불가");
    log.info("- 결과: 선착순 티켓 할당으로 인한 불일치 발생");

    log.info("===== 테스트 종료 =====");
    log.info("테스트 결과 요약:");
    log.info("1. {} 결제 상태: {}", firstUser, firstUserResult.get());
    log.info("2. {} 결제 상태: {}", secondUser, secondUserResult.get());

    // 실제 동시성 테스트에서는 어떤 사용자의 티켓이 성공했는지 확인
    assertNotNull(finalTicketInfo.get("email"), "최소 한 명의 사용자가 티켓을 예매해야 함");
  }

  private void analyzeResults(ConcurrentHashMap<String, BookingResult> results,
      List<Long> bookingOrder, long totalTestTime) {
    List<BookingResult> successResults = results.values().stream()
        .filter(BookingResult::isSuccess)
        .sorted(Comparator.comparingLong(BookingResult::getProcessingTimeNanos))
        .collect(Collectors.toList());

    List<BookingResult> failResults = results.values().stream()
        .filter(r -> !r.isSuccess())
        .sorted(Comparator.comparingLong(BookingResult::getProcessingTimeNanos))
        .collect(Collectors.toList());

    double avgProcessingTime = results.values().stream()
        .mapToLong(BookingResult::getProcessingTimeNanos)
        .average()
        .orElse(0.0) / 1_000_000.0;

    log.info("\n===== 예매 테스트 결과 분석 =====");
    log.info("1. 전체 요약");
    log.info("- 총 소요 시간: {}ms", totalTestTime / 1_000_000.0);
    log.info("- 전체 시도: {} 건", results.size());
    log.info("- 성공: {} 건", successResults.size());
    log.info("- 실패: {} 건", failResults.size());
    log.info("- 평균 처리 시간: {}ms", avgProcessingTime);

    if (!successResults.isEmpty()) {
      log.info("\n2. 예매 성공 내역");
      successResults.forEach(result -> {
        log.info("사용자: test{}@test.com, 좌석: {}, 처리시간: {}ms",
            result.getUserId(),
            result.getSeatId(),
            result.getProcessingTimeNanos() / 1_000_000.0);
      });
    }

    if (!failResults.isEmpty()) {
      log.info("\n3. 예매 실패 내역");
      Map<String, Long> errorCounts = failResults.stream()
          .collect(Collectors.groupingBy(
              BookingResult::getErrorMessage,
              Collectors.counting()
          ));

      log.info("실패 사유별 통계:");
      errorCounts.forEach((error, count) ->
          log.info("- {}: {} 건", error, count));
    }

    if (!bookingOrder.isEmpty()) {
      log.info("\n4. 예매 처리 순서");
      log.info("순서: {}", bookingOrder.stream()
          .map(id -> String.format("test%d@test.com", id))
          .collect(Collectors.joining(" -> ")));
    }

    log.info("===== 분석 완료 =====\n");
  }

  private void verifyDatabaseState(Long seatId) {
    // 티켓 수 확인
    Integer ticketCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ticket WHERE seat_id = ?",
        Integer.class,
        seatId
    );
    assertEquals(1, ticketCount,
        String.format("Seat %d should have exactly one ticket", seatId));

    // 좌석 상태 확인
    Boolean seatAvailable = jdbcTemplate.queryForObject(
        "SELECT is_available FROM seat WHERE id = ?",
        Boolean.class,
        seatId
    );
    assertFalse(seatAvailable,
        String.format("Seat %d should not be available after booking", seatId));
  }

}