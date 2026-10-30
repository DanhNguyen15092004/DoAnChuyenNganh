package com.example.doanchuyennganh.account.security;

import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.List;

/** Detached account snapshot; never keeps a managed JPA entity. */
public final class AccountPrincipal implements UserDetails, CredentialsContainer {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String displayName;
    private final Role role;
    private final boolean enabled;
    private String passwordHash;

    private AccountPrincipal(User user) {
        id = user.getId();
        email = user.getEmail();
        displayName = user.getDisplayName();
        role = user.getRole();
        enabled = user.isEnabled();
        passwordHash = user.getPasswordHash();
    }

    public static AccountPrincipal from(User user) { return new AccountPrincipal(user); }
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    @Override
    public String getUsername() { return email; }
    @Override
    @JsonIgnore
    public String getPassword() { return passwordHash; }
    @Override
    public List<SimpleGrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override
    public boolean isEnabled() { return enabled; }
    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return true; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public void eraseCredentials() { passwordHash = null; }

    @Override
    public String toString() { return "AccountPrincipal[id=" + id + ", role=" + role + "]"; }
}
