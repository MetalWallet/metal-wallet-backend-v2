package com.kb.wallet.ticket.service;

import static org.junit.jupiter.api.Assertions.*;

import com.kb.wallet.global.config.AppConfig;
import com.kb.wallet.ticket.dto.request.TicketRequest;
import com.kb.wallet.ticket.dto.response.TicketResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;

@Slf4j
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
    AppConfig.class
})
@WebAppConfiguration
@ActiveProfiles("test")
class TicketServiceConcurrencyTest {

  @Autowired
  private TicketService ticketService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  public void setUp() {
    // member 데이터 삽입
    for(int i=1; i<=10; i++) {
      jdbcTemplate.execute(
          "insert into member (id, email, is_activated, name, password, phone, pin_number, role) values (" +
              i + ", 'test" + i + "@gmail.com', true, 'test" + i + "', 'password" + i + "', '0100000000" + i + "', '" +
              String.format("%06d", i) + "', 'USER')");
    }

    jdbcTemplate.execute(
        "insert into musical (id, place, place_detail, ranking, running_time, ticketing_end_date, ticketing_start_date, title, detail_image_url, notice_image_url, place_image_url, poster_image_url) values "
            + "(1, '서울', '서울 아트센터', 1, 150, '2024-10-16', '2024-10-01', '킹키부츠', null, null, null, null)");

    jdbcTemplate.execute(
        "insert into schedule (id, date, start_time, end_time, musical_id) values "
            + "(1, '2024-10-17', '10:00:00', '12:30:00', 1)");

    jdbcTemplate.execute(
        "insert into section (id, available_seats, grade, price, musical_id, schedule_id) values "
            + "(1, 50, 'R', 190000, 1, 1)");

    jdbcTemplate.execute(
        "insert into seat (id, is_available, seat_no, schedule_id, section_id) values " +
            "(1, true, 1, 1, 1)");
  }

  @AfterEach
  void tearDown() {
    cleanUpAll();
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
  @DisplayName("10명이 동시에 같은 좌석을 예매할 경우, 단 1건만 성공해야 한다")
  void testConcurrentSameSeatBooking() throws InterruptedException {
    int numberOfThreads = 10;
    Long targetSeatId = 1L;

    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
    CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);
    CopyOnWriteArrayList<Long> bookingOrder = new CopyOnWriteArrayList<>();

    log.info("===== 동시 예매 테스트 시작 (동일 좌석) =====");
    log.info("테스트 설정:");
    log.info("- 총 시도 사용자 수: {} 명", numberOfThreads);
    log.info("- 대상 좌석 번호: {}", targetSeatId);
    log.info("- 예상 성공 건수: 1건");

    ConcurrentHashMap<String, AtomicReference<String>> userResults = new ConcurrentHashMap<>();

    for (int i = 1; i <= numberOfThreads; i++) {
      final int userId = i;
      final String userEmail = String.format("test%d@test.com", userId);
      userResults.put(userEmail, new AtomicReference<>("예매 시도 전"));

      Long currentUserId = jdbcTemplate.queryForObject(
          "SELECT id FROM member WHERE email = ?",
          Long.class,
          userEmail
      );

      log.info("사용자 정보 초기화:");
      log.info("- 사용자 {}: {} (ID: {})", userId, userEmail, currentUserId);
      log.info("  * 상태: 예매 시도 전");

      executor.submit(() -> {
        try {
          log.info("사용자 {} 스레드 시작: 동시 예매 시도 대기 중", userEmail);

          TicketRequest request = new TicketRequest();
          request.setSeatId(Collections.singletonList(targetSeatId));
          request.setDeviceId("device" + userId);

          readyLatch.countDown();
          startLatch.await();

          log.info("사용자 {} 티켓 예매 시도:", userEmail);
          log.info("- 대상 좌석: {}", targetSeatId);
          log.info("- 디바이스: device{}", userId);

          long startTime = System.nanoTime();
          try {
            List<TicketResponse> response = ticketService.bookTicket(userEmail, request);
            long endTime = System.nanoTime();

            log.info("사용자 {} 티켓 예매 성공:", userEmail);
            log.info("- 발급된 티켓 ID: {}", response.get(0).getId());
            log.info("- 소요 시간: {}ms", (endTime - startTime) / 1_000_000.0);

            bookingOrder.add((long) userId);

            userResults.get(userEmail).set("예매 성공");

          } catch (Exception e) {
            long endTime = System.nanoTime();

            log.error("사용자 {} 티켓 예매 실패:", userEmail);
            log.error("- 에러 메시지: {}", e.getMessage());
            log.error("- 시도 좌석: {}", targetSeatId);
            log.error("- 소요 시간: {}ms", (endTime - startTime) / 1_000_000.0);

            userResults.get(userEmail).set("예매 실패: " + e.getMessage());
          }
        } catch (Exception e) {
          log.error("예상치 못한 오류 발생 - 사용자: {}, 오류: {}", userEmail, e.getMessage());
          userResults.get(userEmail).set("예상치 못한 오류 발생: " + e.getMessage());
        } finally {
          doneLatch.countDown();
        }
      });
    }

    readyLatch.await();
    log.info("\n모든 사용자 준비 완료. 동시 예매 시작...");

    long testStartTime = System.nanoTime();
    startLatch.countDown();

    boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
    long testEndTime = System.nanoTime();

    if (!completed) {
      log.error("테스트 타임아웃 발생 (10초 초과)");
    }

    executor.shutdown();

    Map<String, Object> finalTicketInfo = jdbcTemplate.queryForMap(
        "SELECT t.id, m.email, s.is_available " +
            "FROM ticket t " +
            "JOIN member m ON t.member_id = m.id " +
            "JOIN seat s ON t.seat_id = s.id " +
            "WHERE s.id = ?",
        targetSeatId
    );

    log.info("\n최종 상태 검증:");
    log.info("- 좌석 ID: {}", targetSeatId);
    log.info("- 티켓 보유자: {}", finalTicketInfo.get("email"));
    log.info("- 좌석 상태: {}", (Boolean) finalTicketInfo.get("is_available") ? "예약 가능" : "예약 불가");

    log.info("\n각 사용자별 최종 상태:");
    userResults.forEach((email, result) -> {
      log.info("- {}: {}", email, result.get());
    });

    log.info("데이터베이스 상태 검증");
    verifyDatabaseState(targetSeatId);

    log.info("===== 테스트 종료 =====");
  }

  @Test
  @DisplayName("단건 티켓을 예매할 경우, 예매가 성공한다.")
  void testBookTicket_singleTicketSuccess() {
    TicketRequest ticketRequest = new TicketRequest();
    ticketRequest.setSeatId(Collections.singletonList(1L));
    ticketRequest.setDeviceId("deviceID");

    String userEmail = "test1@test.com";

    List<TicketResponse> responses = ticketService.bookTicket(userEmail, ticketRequest);

    assertEquals(1, responses.size(), "One ticket response should be returned.");
    Long ticketCount = jdbcTemplate.queryForObject(
        "select count(*) from ticket where seat_id = 1 and member_id = 1",
        Long.class
    );
    assertEquals(1, ticketCount, "One ticket should be recorded in the database.");
  }
<<<<<<< Updated upstream
  private void analyzeResults(ConcurrentHashMap<String, BookingResult> results,
      List<Long> bookingOrder, long totalTestTime) {
    List<BookingResult> successResults = results.values().stream()
        .filter(BookingResult::isSuccess)
        .sorted(Comparator.comparingLong(BookingResult::getProcessingTimeNanos))
        .collect(Collectors.toList());

  @Test
  @DisplayName("10명의 사용자가 동시에 티켓을 예매할 경우, 단 1건의 예매만 성공한다.")
  void testBookTicket_multipleUsersSingleSeatSuccess2() throws InterruptedException {

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Exception> exceptions = new ArrayList<>();

    for(int i=1; i<=10; i++) {
      String email = "test" + i + "@gmail.com";
      TicketRequest request = new TicketRequest();
      request.setDeviceId("diviceID" + i);
      request.setSeatId(Collections.singletonList(1L));

      executor.submit(() -> {
        try {
          latch.countDown();
          latch.await();
          Thread.sleep(50);
          ticketService.bookTicket(email, request);
        } catch (Exception e) {
          String errorMessage = "Exception occurred for " + email + ": " + e.getMessage();
          System.out.println(errorMessage);
          exceptions.add(e);
        }
      });
    }

    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.MINUTES);

    // Check the number of tickets created in the ticket table
    String sql = "select count(*) from ticket";
    Integer ticketCount = jdbcTemplate.queryForObject(sql, Integer.class);
    System.out.println("현재 생성된 행의 개수: " + ticketCount);
    assertEquals(1, ticketCount.intValue());

    // 예외 처리 내용을 확인하거나 로깅
    if (!exceptions.isEmpty()) {
      System.out.println("Exceptions occurred during ticket booking:");
      for (Exception e : exceptions) {
        System.out.println(e.getMessage());
      }

    }

    log.info("===== 분석 완료 =====\n");
  }
=======
>>>>>>> Stashed changes

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