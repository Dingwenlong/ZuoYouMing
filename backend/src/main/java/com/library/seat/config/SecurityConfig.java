package com.library.seat.config;

import com.library.seat.modules.sys.service.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;

    @Autowired
    private UnauthEntryPoint unauthEntryPoint;

    @Autowired
    private RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {}) // Enable CORS
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/ws/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/doc.html", "/webjars/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/menus").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/seats/all").hasAuthority("admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/messages/system-notification").hasAuthority("admin")
                .requestMatchers(HttpMethod.PUT, "/api/v1/configs").hasAnyAuthority("admin", "librarian")
                .requestMatchers("/api/v1/users/**").hasAuthority("admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/seats/**").hasAnyAuthority("admin", "librarian")
                .requestMatchers(HttpMethod.PUT, "/api/v1/seats/**").hasAnyAuthority("admin", "librarian")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/seats/**").hasAnyAuthority("admin", "librarian")
                .requestMatchers(HttpMethod.POST, "/api/v1/reservations/*/force-release").hasAnyAuthority("admin", "librarian")
                .requestMatchers(HttpMethod.POST, "/api/v1/reservations/appeals/*/review").hasAnyAuthority("admin", "librarian")
                .anyRequest().authenticated()
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(unauthEntryPoint)
                .accessDeniedHandler(restAccessDeniedHandler)
            );

        http.addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
