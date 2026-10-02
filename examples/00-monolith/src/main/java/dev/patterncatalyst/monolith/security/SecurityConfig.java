package dev.patterncatalyst.monolith.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SMELL[ch.15]: ONE global {@code SecurityFilterChain} governs every context's
 * endpoints. Review's write endpoint ({@code POST /api/reviews}) is the only
 * authenticated route in the whole monolith, yet its auth rule is declared here,
 * mixed in with (implicitly, via {@code anyRequest().permitAll()}) the rules for
 * order/inventory/payment/shipping/notification too. Review genuinely does not
 * need anything the other five contexts have — no shared transaction, no shared
 * in-process call — but it CANNOT be deployed independently today without first
 * disentangling it from this one filter chain. ch.15 (the walking-skeleton
 * extraction) gives Review its own OIDC-protected security config, built
 * standalone on Quarkus, with nothing to detangle because nothing else was ever
 * really needed.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/reviews").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * In-memory demo user. A real OIDC provider arrives with the ch.15 extraction;
     * the monolith keeps this deliberately minimal since review's auth is entirely
     * a demo seam here, not a feature of the monolith being built out.
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("demo-customer")
                        .password(encoder.encode("demo-pass"))
                        .roles("CUSTOMER")
                        .build());
    }
}
