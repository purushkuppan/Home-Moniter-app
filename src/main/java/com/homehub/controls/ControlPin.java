package com.homehub.controls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks the household PIN that protects device controls.
 * <p>
 * The PIN comes from the {@code homehub.control.pin} secret (never from the repository) and is never logged.
 * Without a PIN of at least {@value #MIN_LENGTH} characters, controls stay disabled. After
 * {@value #MAX_FAILURES} wrong attempts, all PIN checks are refused for {@link #LOCKOUT}, which makes guessing
 * a 4-digit PIN impractical.
 */
@Component
public class ControlPin {

    static final int MIN_LENGTH = 4;
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ControlPin.class);

    public enum Result { OK, WRONG, LOCKED, NOT_CONFIGURED }

    private final byte[] pinHash;   // null = not configured
    private final Clock clock;
    private int failures;
    private Instant lockedUntil = Instant.EPOCH;

    @Autowired
    public ControlPin(@Value("${homehub.control.pin:}") String pin) {
        this(pin, Clock.systemUTC());
    }

    ControlPin(String pin, Clock clock) {
        this.clock = clock;
        String trimmed = pin == null ? "" : pin.trim();
        if (trimmed.isEmpty()) {
            this.pinHash = null;
        } else if (trimmed.length() < MIN_LENGTH) {
            log.warn("homehub.control.pin is shorter than {} characters; device controls stay disabled.", MIN_LENGTH);
            this.pinHash = null;
        } else {
            this.pinHash = sha256(trimmed);
        }
    }

    public boolean isConfigured() {
        return pinHash != null;
    }

    public synchronized Result check(String attempt) {
        if (pinHash == null) return Result.NOT_CONFIGURED;
        Instant now = clock.instant();
        if (now.isBefore(lockedUntil)) return Result.LOCKED;
        // Comparing hashes takes the same time whatever the input, so timing reveals nothing about the PIN.
        if (attempt != null && MessageDigest.isEqual(pinHash, sha256(attempt.trim()))) {
            failures = 0;
            return Result.OK;
        }
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            lockedUntil = now.plus(LOCKOUT);
            log.warn("Device controls locked for {} minutes after {} wrong PIN attempts.", LOCKOUT.toMinutes(), MAX_FAILURES);
            return Result.LOCKED;
        }
        return Result.WRONG;
    }

    /** Seconds until a lockout ends (0 if not locked). */
    public synchronized long lockedSeconds() {
        long seconds = Duration.between(clock.instant(), lockedUntil).toSeconds();
        return Math.max(0, seconds);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
