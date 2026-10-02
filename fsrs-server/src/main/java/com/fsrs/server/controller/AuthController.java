package com.fsrs.server.controller;

import com.fsrs.server.common.ApiException;
import com.fsrs.server.domain.User;
import com.fsrs.server.dto.auth.LoginRequest;
import com.fsrs.server.dto.auth.LoginResponse;
import com.fsrs.server.mapper.UserMapper;
import com.fsrs.server.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The only unauthenticated endpoint: exchanges credentials for a bearer token. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userMapper.findByUsername(request.username());
        // One message for "no such user" and "wrong password": distinguishable errors would
        // turn this endpoint into a username enumeration oracle.
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("invalid username or password");
        }
        return new LoginResponse(jwtService.issue(user.getId(), user.getUsername()),
                user.getUsername(), user.getId());
    }
}
