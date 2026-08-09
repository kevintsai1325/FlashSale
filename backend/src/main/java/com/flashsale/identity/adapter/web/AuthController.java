package com.flashsale.identity.adapter.web;

import com.flashsale.identity.application.LoginService;
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

    public AuthController(RegisterUserService registerUserService, LoginService loginService) {
        this.registerUserService = registerUserService;
        this.loginService = loginService;
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
}
