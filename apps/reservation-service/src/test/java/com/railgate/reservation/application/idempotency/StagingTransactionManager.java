package com.railgate.reservation.application.idempotency;

import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 단위 테스트용 트랜잭션 관리자. DB 없이 <b>커밋하면 반영되고 롤백하면 사라지는</b> 동작을 흉내 낸다.
 *
 * <p>가짜 포트와 가짜 선점이 쓰기를 {@link #stage} 로 현재 트랜잭션에 쌓고, 커밋할 때만 실행된다.
 * 트랜잭션마다 번호를 매겨 "어느 쓰기가 어느 트랜잭션에서 일어났는가" 를 테스트가 확인한다.
 *
 * <p>{@link #failCommitAfterApplying} 은 <b>커밋은 반영됐는데 호출자에게는 실패로 보이는</b>
 * 커밋 결과 불명 상황을 흉내 낸다. 실제 COMMIT 중 연결 단절을 재현하는 것은 아니다.
 */
final class StagingTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    /** 한 트랜잭션. */
    static final class Tx {
        final int id;
        final List<Runnable> staged = new ArrayList<>();
        String end = "open";

        Tx(int id) {
            this.id = id;
        }
    }

    private final transient ThreadLocal<Tx> current = new ThreadLocal<>();
    private final transient List<Tx> history = new ArrayList<>();
    private transient int failCommitOfTx = -1;

    StagingTransactionManager() {
        setTransactionSynchronization(SYNCHRONIZATION_ALWAYS);
    }

    /** 현재 트랜잭션에 쓰기를 쌓는다. 트랜잭션 밖이면 실패한다 — 가짜 포트의 참여 검사. */
    void stage(Runnable write) {
        Tx tx = current.get();
        if (tx == null) {
            throw new IllegalStateException("트랜잭션 밖의 쓰기");
        }
        tx.staged.add(write);
    }

    int currentTxId() {
        Tx tx = current.get();
        if (tx == null) {
            throw new IllegalStateException("트랜잭션 밖");
        }
        return tx.id;
    }

    synchronized List<String> ends() {
        return history.stream().map(tx -> tx.id + ":" + tx.end).toList();
    }

    /** {@code txId} 번 트랜잭션의 커밋을 반영한 뒤 실패로 보이게 한다. */
    void failCommitAfterApplying(int txId) {
        failCommitOfTx = txId;
    }

    @Override
    protected Object doGetTransaction() {
        return new Object[] {current.get()};
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Object[]) transaction)[0] != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Tx tx;
        synchronized (this) {
            tx = new Tx(history.size() + 1);
            history.add(tx);
        }
        current.set(tx);
        ((Object[]) transaction)[0] = tx;
    }

    @Override
    protected Object doSuspend(Object transaction) {
        Tx suspended = current.get();
        current.remove();
        return suspended;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        current.set((Tx) suspendedResources);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        Tx tx = (Tx) ((Object[]) status.getTransaction())[0];
        tx.staged.forEach(Runnable::run);
        tx.end = "commit";
        if (tx.id == failCommitOfTx) {
            tx.end = "commit-unknown";
            throw new TransactionSystemException("커밋 결과 불명 (흉내)");
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        Tx tx = (Tx) ((Object[]) status.getTransaction())[0];
        tx.staged.clear();
        tx.end = "rollback";
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        current.remove();
    }
}
