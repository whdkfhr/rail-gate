package com.railgate.reservation.application.idempotency;

import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import com.railgate.reservation.idempotency.IdempotencyKeyState;
import com.railgate.reservation.idempotency.IdempotencyRecord;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.KeyOwnership;
import com.railgate.reservation.idempotency.KeyWriteOutcome;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.idempotency.StoredHoldOutcome;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 메모리 위의 {@link IdempotencyKeyPort} 대역. 운영 저장소와 같은 <b>소유권 조건</b>을 흉내 내고,
 * 쓰기는 {@link StagingTransactionManager} 의 현재 트랜잭션에 쌓아 커밋 때만 반영한다.
 *
 * <p>리스 만료는 시각이 아니라 {@link #expireLease} 로 만든다. 장애 주입은 {@link #failOn} 으로 한다.
 * 모든 호출을 "메서드@트랜잭션번호" 로 기록한다 (조회는 트랜잭션 밖이면 {@code @-}).
 */
final class FakeIdempotencyKeyPort implements IdempotencyKeyPort {

    /** 행 하나. */
    static final class Row {
        RequestFingerprint fingerprint;
        IdempotencyKeyState state = IdempotencyKeyState.IN_PROGRESS;
        KeyOwnership ownership;
        boolean leaseExpired;
        StoredHoldOutcome outcome;
    }

    private final StagingTransactionManager tx;
    private final Map<IdempotencyScope, Row> rows = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final Map<String, Supplier<RuntimeException>> failures = new HashMap<>();

    FakeIdempotencyKeyPort(StagingTransactionManager tx) {
        this.tx = tx;
    }

    // ------------------------------------------------------------------ 테스트 조작

    void failOn(String method, Supplier<RuntimeException> failure) {
        failures.put(method, failure);
    }

    synchronized Row row(IdempotencyScope scope) {
        return rows.get(scope);
    }

    synchronized void put(IdempotencyScope scope, Row row) {
        rows.put(scope, row);
    }

    synchronized void expireLease(IdempotencyScope scope) {
        rows.get(scope).leaseExpired = true;
    }

    synchronized void delete(IdempotencyScope scope) {
        rows.remove(scope);
    }

    synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    // ------------------------------------------------------------------ 포트

    @Override
    public synchronized Optional<KeyOwnership> tryInsert(
            IdempotencyScope scope, RequestFingerprint fingerprint, Duration lease) {
        record("tryInsert");
        if (rows.containsKey(scope)) {
            return Optional.empty();
        }
        KeyOwnership owned = KeyOwnership.newGeneration();
        Row row = new Row();
        row.fingerprint = fingerprint;
        row.ownership = owned;
        tx.stage(() -> put(scope, row));
        return Optional.of(owned);
    }

    @Override
    public synchronized Optional<IdempotencyRecord> find(IdempotencyScope scope) {
        record("find");
        Row row = rows.get(scope);
        if (row == null) {
            return Optional.empty();
        }
        boolean completed = row.state == IdempotencyKeyState.COMPLETED;
        return Optional.of(new IdempotencyRecord(row.fingerprint, row.state, row.ownership,
                !completed && row.leaseExpired, Optional.ofNullable(row.outcome)));
    }

    @Override
    public synchronized Optional<KeyOwnership> takeOver(IdempotencyScope scope, KeyOwnership seen, Duration lease) {
        Objects.requireNonNull(seen);
        record("takeOver");
        Row row = rows.get(scope);
        if (row == null || row.state != IdempotencyKeyState.IN_PROGRESS || !row.leaseExpired
                || !row.ownership.equals(seen)) {
            return Optional.empty();
        }
        KeyOwnership next = seen.next();
        tx.stage(() -> {
            row.ownership = next;
            row.leaseExpired = false;
        });
        return Optional.of(next);
    }

    @Override
    public synchronized KeyWriteOutcome completeSucceeded(IdempotencyScope scope, KeyOwnership mine, Succeeded outcome) {
        record("completeSucceeded");
        return complete(scope, mine, outcome);
    }

    @Override
    public synchronized KeyWriteOutcome completeRejected(IdempotencyScope scope, KeyOwnership mine, Rejected outcome) {
        record("completeRejected");
        return complete(scope, mine, outcome);
    }

    @Override
    public synchronized KeyWriteOutcome abandon(IdempotencyScope scope, KeyOwnership mine) {
        record("abandon:" + mine.fence());
        Row row = rows.get(scope);
        if (!isOwner(row, mine)) {
            return KeyWriteOutcome.NOT_OWNER;
        }
        tx.stage(() -> row.leaseExpired = true);
        return KeyWriteOutcome.APPLIED;
    }

    private KeyWriteOutcome complete(IdempotencyScope scope, KeyOwnership mine, StoredHoldOutcome outcome) {
        Row row = rows.get(scope);
        if (!isOwner(row, mine)) {
            return KeyWriteOutcome.NOT_OWNER;
        }
        tx.stage(() -> {
            row.state = IdempotencyKeyState.COMPLETED;
            row.outcome = outcome;
            row.leaseExpired = false;
        });
        return KeyWriteOutcome.APPLIED;
    }

    private static boolean isOwner(Row row, KeyOwnership mine) {
        return row != null && row.state == IdempotencyKeyState.IN_PROGRESS && row.ownership.equals(mine);
    }

    private void record(String method) {
        String name = method.contains(":") ? method.substring(0, method.indexOf(':')) : method;
        Supplier<RuntimeException> failure = failures.get(name);
        String where;
        try {
            where = String.valueOf(tx.currentTxId());
        } catch (IllegalStateException outside) {
            where = "-";
        }
        calls.add(method + "@" + where);
        if (!"find".equals(name) && "-".equals(where)) {
            throw new IllegalStateException(name + " 는 트랜잭션 안에서만 부를 수 있다");
        }
        if (failure != null) {
            throw failure.get();
        }
    }
}
