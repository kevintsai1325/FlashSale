package com.flashsale.identity.adapter.web;

import com.flashsale.identity.application.LoginService;
import com.flashsale.identity.application.LogoutService;
import com.flashsale.identity.application.RefreshTokenService;
import com.flashsale.identity.application.RegisterUserService;
import com.flashsale.identity.adapter.web.dto.LoginRequest;
import com.flashsale.identity.adapter.web.dto.RegisterRequest;
import com.flashsale.identity.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegisterUserService registerUserService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final LogoutService logoutService;

    public AuthController(RegisterUserService registerUserService, LoginService loginService,
                           RefreshTokenService refreshTokenService, LogoutService logoutService) {
        this.registerUserService = registerUserService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.logoutService = logoutService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = registerUserService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("id", user.getId(), "email", user.getEmail()));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                       jakarta.servlet.http.HttpServletResponse response) {
        LoginService.LoginResult result = loginService.login(request.email(), request.password());

        jakarta.servlet.http.Cookie refreshCookie = new jakarta.servlet.http.Cookie("refresh_token", result.rawRefreshToken());
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/api/auth");
        refreshCookie.setMaxAge(30 * 24 * 60 * 60);
        response.addCookie(refreshCookie);

        return ResponseEntity.ok(Map.of("accessToken", result.accessToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@CookieValue("refresh_token") String refreshToken) {
        String accessToken = refreshTokenService.refresh(refreshToken);
        return ResponseEntity.ok(Map.of("accessToken", accessToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = "refresh_token", required = false) String refreshToken,
                                        jakarta.servlet.http.HttpServletResponse response) {
        if (refreshToken != null) {
            logoutService.logout(refreshToken);
        }
        jakarta.servlet.http.Cookie expired = new jakarta.servlet.http.Cookie("refresh_token", "");
        expired.setHttpOnly(true);
        expired.setSecure(true);
        expired.setPath("/api/auth");
        expired.setMaxAge(0);
        response.addCookie(expired);
        return ResponseEntity.noContent().build();
    }
}
