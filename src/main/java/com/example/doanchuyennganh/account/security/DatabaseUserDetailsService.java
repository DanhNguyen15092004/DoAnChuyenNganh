package com.example.doanchuyennganh.account.security;

import com.example.doanchuyennganh.account.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public DatabaseUserDetailsService(UserRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public AccountPrincipal loadUserByUsername(String email) {
        return users.findByEmail(email).map(AccountPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Email hoặc mật khẩu không hợp lệ."));
    }
}
