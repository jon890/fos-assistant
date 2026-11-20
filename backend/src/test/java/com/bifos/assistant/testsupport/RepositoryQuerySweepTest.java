package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * {@link RepositoryQuerySweep} 가 쿼리 실패를 삼키지 않고 {@link RepositoryQuerySweep.Result#failures()} 에 원인과 함께 남기는지 본다.
 *
 * <p>{@code RepositoryQueryMysqlTest} 는 실패 목록이 비었는지만 본다. sweep 이 실패를 기록하지 못하면 그 검사는 아무것도 잡지 못한 채 통과한다.
 * MySQL 이 정렬 규칙 섞임을 빈 표에서도 거절한다는 전제는 {@code CollationMixQueryMysqlTest} 가 본다.
 */
class RepositoryQuerySweepTest {

    /** 정렬 규칙이 섞인 쿼리에서 MySQL 이 내는 오류 코드다. */
    private static final int ILLEGAL_MIX_OF_COLLATIONS = 1267;


    @Test
    @DisplayName("저장소 메서드가 던진 예외를 원인과 함께 실패 목록에 남기고 성공한 메서드는 실행 목록에 남긴다")
    void recordsFailuresWithCause() {
        var sweep = new RepositoryQuerySweep(contextWith(new SampleRepository() {
            @Override
            public long countMixed() {
                throw new InvalidDataAccessResourceUsageException("정렬 규칙 섞임",
                    new SQLException("Illegal mix of collations", "HY000", ILLEGAL_MIX_OF_COLLATIONS));
            }


            @Override
            public long countSame() {
                return 0;
            }
        }), new NoOpTransactionManager());

        var result = sweep.run(Set.of());

        assertThat(result.executed()).containsExactly("SampleRepository.countSame");
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.method()).isEqualTo("SampleRepository.countMixed");
            assertThat(failure.cause()).isInstanceOf(InvalidDataAccessResourceUsageException.class);
            assertThat(failure.cause().getCause()).isInstanceOfSatisfying(SQLException.class,
                sql -> assertThat(sql.getErrorCode()).isEqualTo(ILLEGAL_MIX_OF_COLLATIONS));
        });
    }


    @Test
    @DisplayName("제외한 메서드는 실행하지 않는다")
    void skipsExcludedMethods() {
        var sweep = new RepositoryQuerySweep(contextWith(new SampleRepository() {
            @Override
            public long countMixed() {
                throw new IllegalStateException("제외했으니 부르지 않아야 한다");
            }


            @Override
            public long countSame() {
                return 0;
            }
        }), new NoOpTransactionManager());

        var result = sweep.run(Set.of("SampleRepository.countMixed"));

        assertThat(result.failures()).isEmpty();
        assertThat(result.executed()).containsExactly("SampleRepository.countSame");
    }


    private static ApplicationContext contextWith(SampleRepository repository) {
        RepositoryInformation information = mock(RepositoryInformation.class);
        when(information.getRepositoryInterface()).thenAnswer(ignored -> SampleRepository.class);
        RepositoryFactoryInformation<?, ?> factory = mock(RepositoryFactoryInformation.class);
        when(factory.getRepositoryInformation()).thenReturn(information);
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(RepositoryFactoryInformation.class)).thenReturn(Map.of("sample", factory));
        when(context.getBean(SampleRepository.class)).thenReturn(repository);
        return context;
    }


    /** sweep 이 찾는 저장소 인터페이스 자리다. 패키지가 com.bifos.assistant 아래라 수집 대상이다. */
    interface SampleRepository {
        long countMixed();


        long countSame();
    }

    /** 트랜잭션을 열고 되돌리는 일만 흉내 낸다. sweep 은 메서드마다 트랜잭션을 열고 되돌린다. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;


        @Override
        protected Object doGetTransaction() {
            return new Object();
        }


        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }


        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }


        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
