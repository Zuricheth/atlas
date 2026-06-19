package com.qianyu.atlas.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;

@Service
public class JwtService {
    private static final String DEFAULT_DEV_SECRET = "change-me-dev-secret-key-at-least-32-bytes-long";

    private final SecretKey secretKey;
    private final long expireHours;

    public JwtService(
            @Value("${atlas.jwt.secret}") String secret,
            @Value("${atlas.jwt.expire-hours}") long expireHours,
            Environment environment
    ) {
        if (DEFAULT_DEV_SECRET.equals(secret) && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("生产环境必须通过 ATLAS_JWT_SECRET 配置强随机 JWT 密钥");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = expireHours;
    }

    public String issue(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
                .signWith(secretKey)
                .compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new CurrentUser(Long.valueOf(claims.getSubject()), claims.get("username", String.class));
    }
}
