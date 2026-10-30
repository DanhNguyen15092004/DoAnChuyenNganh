package com.example.doanchuyennganh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import jakarta.servlet.http.Cookie;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    DaoAuthenticationProvider accountAuthenticationProvider(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(DaoAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    XorCsrfTokenRequestAttributeHandler csrfRequestHandler() { return new XorCsrfTokenRequestAttributeHandler(); }

    @Bean
    SecurityContextRepository securityContextRepository() {
        var repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy loginSessionAuthenticationStrategy(HttpSessionCsrfTokenRepository repository,
                                                                     XorCsrfTokenRequestAttributeHandler handler) {
        var csrf = new CsrfAuthenticationStrategy(repository);
        csrf.setRequestHandler(handler);
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), csrf));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment,
                                          ObjectMapper mapper, HttpSessionCsrfTokenRepository csrf,
                                          XorCsrfTokenRequestAttributeHandler csrfHandler,
                                          SecurityContextRepository contexts) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        // Protect every API and every exposed Actuator endpoint. Unmapped non-API paths
        // still reach MVC's existing 404 handler; no fallback login UI is installed.
        http.securityMatcher(new OrRequestMatcher(EndpointRequest.toAnyEndpoint(),
                        paths.matcher("/api/**"), paths.matcher("/login"), paths.matcher("/logout")))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET, "/actuator/health",
                            "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout",
                                    "/api/v1/auth/register").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").hasAnyRole("STUDENT", "TEACHER", "ADMIN")
                            .requestMatchers("/api/v1/student/**").hasRole("STUDENT")
                            .requestMatchers("/api/v1/teacher/**").hasAnyRole("TEACHER", "ADMIN")
                            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
                        authorize.requestMatchers(HttpMethod.GET, "/api/examples/errors/not-found",
                                        "/api/examples/errors/internal").permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/examples/errors/validation").permitAll();
                    }
                    authorize.anyRequest().denyAll();
                })
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrfHandler))
                .securityContext(config -> config.securityContextRepository(contexts).requireExplicitSave(true))
                // Controller login owns the strategy call. No SessionManagementFilter repeats it.
                // IF_REQUIRED is the framework default. Setting it explicitly in 6.5
                // would force the legacy SessionManagementFilter and conflict with this mode.
                .sessionManagement(config -> config.requireExplicitAuthenticationStrategy(true))
                .requestCache(config -> config.disable())
                .formLogin(config -> config.disable())
                .httpBasic(config -> config.disable())
                .logout(config -> config
                        .logoutRequestMatcher(paths.matcher(HttpMethod.POST, "/api/v1/auth/logout"))
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .addLogoutHandler(sessionCookieClearingHandler(environment))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(mapper, request, response, HttpStatus.UNAUTHORIZED,
                                        "Bạn chưa đăng nhập."))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(mapper, request, response, HttpStatus.FORBIDDEN,
                                        "Yêu cầu bị từ chối.")));
        return http.build();
    }

    private CookieClearingLogoutHandler sessionCookieClearingHandler(Environment environment) {
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        cookie.setSecure(environment.acceptsProfiles(Profiles.of("prod")));
        cookie.setAttribute("SameSite", "Lax");
        return new CookieClearingLogoutHandler(cookie);
    }

    private static void writeProblem(ObjectMapper mapper, HttpServletRequest request,
                                     HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
