-- ShedLock's own reference schema (https://github.com/lukas-krecan/ShedLock), with
-- lock_until/locked_at changed from plain TIMESTAMP to TIMESTAMPTZ: every timestamp
-- column in this project's schema (V1-V11) is TIMESTAMPTZ, and it also sidesteps a
-- caveat in ShedLock's own docs about JVM-timezone-dependent timestamp loss with a
-- plain TIMESTAMP column.
CREATE TABLE shedlock (
    name       VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at  TIMESTAMPTZ NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
