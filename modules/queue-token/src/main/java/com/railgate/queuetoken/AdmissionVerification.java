package com.railgate.queuetoken;

import java.util.Objects;

/** 입장 토큰 검증 결과. 예외 대신 값으로 돌려준다 — 거부는 정상 흐름이다. */
public sealed interface AdmissionVerification {

    record Accepted(AdmissionClaims claims) implements AdmissionVerification {
        public Accepted {
            Objects.requireNonNull(claims, "claims");
        }
    }

    record Rejected(AdmissionRejection reason) implements AdmissionVerification {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
