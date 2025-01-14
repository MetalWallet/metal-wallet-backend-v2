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
import java.util.stream.Collectors;

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
    // 테스트용 회원 100명 생성
    for (int i = 1; i <= 50; i++) {
      jdbcTemplate.execute(
        String.format("INSERT INTO member (id, email, is_activated, name, password, phone, pin_number, role) " +
            "VALUES (%d, 'test%d@test.com', true, 'test%d', 'password%d', '0101111%04d', '111111', 'USER')",
          i, i, i, i, i)
      );
    }

    // 뮤지컬 데이터 삽입
    jdbcTemplate.execute(
      "INSERT INTO musical (id, place, place_detail, ranking, running_time, ticketing_end_date, " +
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
        String.format("INSERT INTO seat (id, is_available, seat_no, schedule_id, section_id) VALUES " +
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
  @DisplayName("50명이 동시에 같은 좌석을 예매할 경우, 단 1건만 성공해야 한다")
  void testConcurrentSameSeatBooking() throws InterruptedException {
    // Given
    int numberOfThreads = 50;
    Long targetSeatId = 1L;

    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
    CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

    ConcurrentHashMap<String, BookingResult> results = new ConcurrentHashMap<>();
    CopyOnWriteArrayList<Long> bookingOrder = new CopyOnWriteArrayList<>();

    log.info("===== 동시 예매 테스트 시작 (동일 좌석) =====");
    log.info("테스트 설정:");
    log.info("- 총 시도 사용자 수: {} 명", numberOfThreads);
    log.info("- 대상 좌석 번호: {}", targetSeatId);
    log.info("- 예상 성공 건수: 1건");

    for (int i = 1; i <= numberOfThreads; i++) {
      final int userId = i;
      final String userEmail = String.format("test%d@test.com", userId);

      executor.submit(() -> {
        try {
          TicketRequest request = new TicketRequest();
          request.setSeatId(Collections.singletonList(targetSeatId));
          request.setDeviceId("device" + userId);

          readyLatch.countDown();
          startLatch.await();

          long startTime = System.nanoTime();
          try {
            List<TicketResponse> response = ticketService.bookTicket(userEmail, request);
            long endTime = System.nanoTime();

            bookingOrder.add((long) userId);
            results.put(userEmail, new BookingResult(userId, true, null, endTime - startTime, targetSeatId));

          } catch (Exception e) {
            long endTime = System.nanoTime();
            results.put(userEmail, new BookingResult(userId, false, e.getMessage(), endTime - startTime, targetSeatId));
          }
        } catch (Exception e) {
          log.error("예상치 못한 오류 발생 - 사용자: {}, 오류: {}", userEmail, e.getMessage());
        } finally {
          doneLatch.countDown();
        }
      });
    }

    readyLatch.await();
    log.info("모든 사용자 준비 완료. 동시 예매 시작...");

    long testStartTime = System.nanoTime();
    startLatch.countDown();

    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
    long testEndTime = System.nanoTime();

    if (!completed) {
      log.error("테스트 타임아웃 발생 (30초 초과)");
    }

    executor.shutdown();

    log.info("테스트 결과 분석 시작");
    analyzeResults(results, bookingOrder, testEndTime - testStartTime);

    log.info("데이터베이스 상태 검증");
    verifyDatabaseState(targetSeatId);
    log.info("===== 테스트 종료 =====");
  }

  @Test
  @DisplayName("50명이 동시에 서로 다른 좌석을 예매할 경우, 모든 예매가 성공해야 한다")
  void testConcurrentDifferentSeatBooking() throws InterruptedException {
    int numberOfThreads = 50;
    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
    CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

    ConcurrentHashMap<String, BookingResult> results = new ConcurrentHashMap<>();
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
            results.put(userEmail, new BookingResult(userId, true, null, endTime - startTime, seatId));

          } catch (Exception e) {
            long endTime = System.nanoTime();
            results.put(userEmail, new BookingResult(userId, false, e.getMessage(), endTime - startTime, seatId));
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

    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
    long testEndTime = System.nanoTime();

    if (!completed) {
      log.error("테스트 타임아웃 발생 (30초 초과)");
    }

    executor.shutdown();

    log.info("테스트 결과 분석 시작");
    analyzeResults(results, bookingOrder, testEndTime - testStartTime);

    log.info("데이터베이스 상태 검증 시작");
    for (long seatId = 1; seatId <= numberOfThreads; seatId++) {
      log.info("좌석 {} 상태 검증 중...", seatId);
      verifyDatabaseState(seatId);
    }
    log.info("===== 테스트 종료 =====");
  }


  @Test
  @DisplayName("동시성 제어가 없을 경우, 동일 좌석에 대해 여러 건 예매가 성공할 수 있다")
  void testConcurrentSameSeatBookingWithoutLock() throws InterruptedException {
    // Given
    int numberOfThreads = 5; // 스레드 수를 제한
    Long targetSeatId = 1L;

    log.info("===== 동시성 제어 없는 동시 예매 테스트 시작 =====");
    log.info("테스트 설정:");
    log.info("- 총 시도 사용자 수: {} 명", numberOfThreads);
    log.info("- 대상 좌석 번호: {}", targetSeatId);
    log.info("- 예상: 동시성 제어 부재로 인해 여러 건의 예매가 성공할 것");

    // 좌석 상태 초기화
    jdbcTemplate.update("DELETE FROM ticket WHERE seat_id = ?", targetSeatId);
    jdbcTemplate.update("UPDATE seat SET is_available = true WHERE id = ?", targetSeatId);

    // 동시성 제어 객체 설정
    CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);
    AtomicInteger successCount = new AtomicInteger(0);

    // 태스크 생성
    for (int i = 1; i <= numberOfThreads; i++) {
      final int userId = i;
      final String userEmail = String.format("test%d@test.com", userId);

      new Thread(() -> {
        try {
          readyLatch.countDown();
          startLatch.await(); // 모든 스레드 동시 시작

          String sql =
            "INSERT INTO ticket (member_id, musical_id, seat_id, device_id, ticket_status, created_at, valid_until, cancel_until) " +
              "VALUES (?, 1, ?, ?, 'VALID', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY))";

          try {
            int result = jdbcTemplate.update(sql, userId, targetSeatId, "device" + userId);
            if (result > 0) {
              successCount.incrementAndGet();
              log.info("예매 성공 - 사용자: {}, 좌석: {}", userEmail, targetSeatId);
            }
          } catch (DataAccessException e) {
            log.error("예매 실패 - 사용자: {}, 좌석: {}, 사유: {}", userEmail, targetSeatId, e.getMessage());
          }
        } catch (InterruptedException e) {
          log.error("스레드 인터럽트 발생 - 사용자: {}, 오류: {}", userEmail, e.getMessage());
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      }).start();
    }

    readyLatch.await();
    log.info("모든 스레드 준비 완료. 동시 예매 시작...");
    startLatch.countDown();

    // 완료 대기
    doneLatch.await(5, TimeUnit.SECONDS);

    // 결과 검증
    Integer ticketCount = jdbcTemplate.queryForObject(
      "SELECT COUNT(*) FROM ticket WHERE seat_id = ?",
      Integer.class,
      targetSeatId
    );

    log.info("테스트 결과:");
    log.info("- 총 예매 시도: {}", numberOfThreads);
    log.info("- 성공한 예매: {}", successCount.get());
    log.info("- 실제 발급된 티켓: {}", ticketCount);

    // 동시성 제어가 없어 여러 건의 티켓이 발급될 수 있음을 검증
    assertTrue(ticketCount > 1,
      String.format("동시성 제어 부재로 인해 중복 예매(%d건)가 발생해야 함", ticketCount));
  }
  @Test
  @DisplayName("이미 예매된 좌석에 대한 중복 예매 시도")
  void testBookingAlreadyReservedSeat() {
    // Given
    Long targetSeatId = 1L;
    String firstUser = "test1@test.com";
    String secondUser = "test2@test.com";

    TicketRequest request = new TicketRequest();
    request.setSeatId(Collections.singletonList(targetSeatId));
    request.setDeviceId("device1");

    // When: 첫 번째 예매 시도
    log.info("첫 번째 사용자 {}가 좌석 ID {} 예매 시도", firstUser, targetSeatId);
    List<TicketResponse> firstBooking = ticketService.bookTicket(firstUser, request);
    log.info("첫 번째 예매 성공 - 사용자: {}, 좌석 ID: {}, 티켓 ID: {}",
      firstUser, targetSeatId, firstBooking.get(0).getId());

    // Then: 두 번째 예매 시도는 실패해야 함
    log.info("두 번째 사용자 {}가 이미 예매된 좌석 ID {} 예매 시도", secondUser, targetSeatId);
    CustomException exception = assertThrows(CustomException.class, () -> {
      ticketService.bookTicket(secondUser, request);
    });

    assertEquals("이미 예약된 좌석입니다.", exception.getMessage());
    log.error("예상대로 중복 예매 실패 - 좌석 ID {}: {} (시도한 사용자: {})",
      targetSeatId, exception.getMessage(), secondUser);
  }

  @Test
  @DisplayName("좌석 상태와 예매 정보 불일치 검증")
  void testSeatStatusAndTicketMismatch() {
    // Given: 좌석만 예약된 비정상 상태 생성
    Long targetSeatId = 1L;
    log.info("좌석 ID {} 예약 불가능 상태로 설정", targetSeatId);
    jdbcTemplate.update("UPDATE seat SET is_available = false WHERE id = ?", targetSeatId);

    // When: 검증 및 복구 프로세스 실행
    TicketRequest request = new TicketRequest();
    request.setSeatId(Collections.singletonList(targetSeatId));
    request.setDeviceId("device1");

    log.info("사용자 test1@test.com이 좌석 ID {}의 예매 시도", targetSeatId);

    // Then: 예매 시도시 예외 발생 확인
    CustomException exception = assertThrows(CustomException.class, () -> {
      ticketService.bookTicket("test1@test.com", request);
    });

    log.error("예매 실패 - 좌석 ID {}: {}", targetSeatId, exception.getMessage());
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
  @Transactional
  void testDoubleBookingAfterPayment() {
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

    // 2. 두 번째 사용자의 결제 및 티켓 발급
    TicketRequest secondRequest = new TicketRequest();
    secondRequest.setSeatId(Collections.singletonList(targetSeatId));
    secondRequest.setDeviceId("device2");

    log.info("두 번째 사용자 예매 시도:");
    log.info("- 사용자: {}", secondUser);
    log.info("- 대상 좌석: {}", targetSeatId);

    List<TicketResponse> secondTicket = ticketService.bookTicket(secondUser, secondRequest);

    log.info("두 번째 사용자 티켓 발급 완료:");
    log.info("- 발급된 티켓 ID: {}", secondTicket.get(0).getId());
    log.info("- 할당된 좌석: {}", targetSeatId);

    // 3. 첫 번째 사용자의 티켓 할당 시도
    TicketRequest firstRequest = new TicketRequest();
    firstRequest.setSeatId(Collections.singletonList(targetSeatId));
    firstRequest.setDeviceId("device1");

    log.info("첫 번째 사용자 티켓 할당 시도:");
    log.info("- 사용자: {}", firstUser);
    log.info("- 대상 좌석: {}", targetSeatId);

    CustomException exception = assertThrows(CustomException.class, () -> {
      ticketService.bookTicket(firstUser, firstRequest);
    });

    log.error("첫 번째 사용자 티켓 할당 실패:");
    log.error("- 에러 메시지: {}", exception.getMessage());
    log.error("- 시도한 사용자: {}", firstUser);
    log.error("- 결제 완료 시점: 두 번째 사용자보다 먼저 완료");
    log.error("- 티켓 할당 시도 좌석: {}", targetSeatId);

    // 4. 최종 상태 검증
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
    log.info("- 좌석 상태: {}", (Boolean)finalTicketInfo.get("is_available") ? "예약 가능" : "예약 불가");
    log.info("- 결과: 선착순 티켓 할당으로 인한 불일치 발생");

    // Assertions
    assertEquals(secondUser, finalTicketInfo.get("email"),
      "티켓은 실제 먼저 할당받은 두 번째 사용자에게 있어야 함");
    assertEquals("이미 예약된 좌석입니다.", exception.getMessage());

    log.info("===== 테스트 종료 =====");
    log.info("테스트 결과 요약:");
    log.info("1. {}가 먼저 결제 완료", firstUser);
    log.info("2. {}가 나중에 결제했지만 먼저 티켓 할당 받음", secondUser);
    log.info("3. {}의 티켓 할당 시도 실패", firstUser);
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

  private static class BookingResult {
    private final int userId;
    private final boolean success;
    private final String errorMessage;
    private final long processingTimeNanos;
    private final Long seatId;

    public BookingResult(int userId, boolean success, String errorMessage,
      long processingTimeNanos, Long seatId) {
      this.userId = userId;
      this.success = success;
      this.errorMessage = errorMessage;
      this.processingTimeNanos = processingTimeNanos;
      this.seatId = seatId;
    }

    public int getUserId() {
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
}