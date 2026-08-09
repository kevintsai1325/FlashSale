package com.flashsale.identity.adapter.web;

import com.flashsale.identity.application.RegisterUserService;
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

    public AuthController(RegisterUserService registerUserService) {
        this.registerUserService = registerUserService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = registerUserService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("id", user.getId(), "email", user.getEmail()));
    }
}
