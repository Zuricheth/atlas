package com.qianyu.atlas.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) String username,
            @Email String email,
            @NotBlank @Size(min = 6, max = 64) String password
    ) {
    }

    public record LoginRequest(
            @NotBlank String account,
            @NotBlank String password
    ) {
    }

    public record AuthResponse(
            Long userId,
            String username,
            String token
    ) {
    }
}