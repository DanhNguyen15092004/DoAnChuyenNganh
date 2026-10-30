package com.example.doanchuyennganh.account.auth;

import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.User;
import com.example.doanchuyennganh.account.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Service
public class RegistrationService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public RegistrationService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public User register(RegistrationController.RegistrationRequest request) {
        // BCrypt accepts at most 72 UTF-8 bytes. Never truncate or normalize a password.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8.");
        }
        if (users.findByEmail(request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được đăng ký.");
        }
        return users.saveAndFlush(new User(request.email(), encoder.encode(request.password()),
                request.displayName(), Role.STUDENT, true));
    }
}
