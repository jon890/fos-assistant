package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bifos.assistant.testsupport.MemorySearchSqlProbe;
import com.bifos.assistant.testsupport.MysqlIntegrationTest;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** H2에서 확인한 검색 동작과 SQL 투영을 Flyway로 만든 실제 MySQL 스키마에서도 확인한다. */
@MysqlIntegrationTest
class MemorySearchMysqlTest extends MemorySearchTest {

    @Test
    @DisplayName("검색 statement는 바깥 트랜잭션의 30초 제한과 분리해 2초에 지연을 중단하고 실패를 돌려준다")
    void cancelsDelayedSearchWithinItsOwnTimeout() {
        save("합성 지연");
        MemorySearchSqlProbe.capture();
        MemorySearchSqlProbe.delay();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setTimeout(30);
        long started = System.nanoTime();
        Throwable failure = catchThrowable(() -> outer.execute(status -> search(USER, CORE, "합성", 10)));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        SQLException sqlFailure = sqlFailure(failure);
        System.out.printf(
            "memory-search timeoutMs=%d jdbcTimeoutSeconds=%d selectCount=%d readOnly=%b sqlState=%s errorCode=%d sqlTimeout=%b%n",
            elapsed,
            MemorySearchSqlProbe.timeout(),
            MemorySearchSqlProbe.selects().size(),
            MemorySearchSqlProbe.readOnly(),
            sqlFailure == null ? null : sqlFailure.getSQLState(),
            sqlFailure == null ? 0 : sqlFailure.getErrorCode(),
            sqlFailure instanceof SQLTimeoutException);
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat((Throwable)sqlFailure).isInstanceOf(SQLTimeoutException.class);
        assertThat(elapsed).isBetween(800L, 4500L);
        assertProjectionAndTimeout();
    }


    @Test
    @DisplayName("검색 SQL 오류를 빈 결과로 바꾸지 않는다")
    void propagatesSqlFailure() {
        save("합성 오류");
        MemorySearchSqlProbe.capture();
        MemorySearchSqlProbe.invalidate();
        assertThatThrownBy(() -> search(USER, CORE, "합성", 10)).isInstanceOf(RuntimeException.class);
        assertProjectionAndTimeout();
    }


    private static SQLException sqlFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
