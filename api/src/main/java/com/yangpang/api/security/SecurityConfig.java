package com.yangpang.api.security;

import org.springframework.http.HttpMethod;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginSuccessHandler loginSuccessHandler) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/seller", "/seller/apply").authenticated()
                        .requestMatchers("/seller/**").hasRole("SELLER")
                        // 장바구니는 비회원도 쓸 수 있다 (세션). 구매(checkout)부터 로그인 필요
                        .requestMatchers("/my/**", "/checkout/**", "/orders/**", "/plus/join", "/partners", "/partners/**", "/subscriptions/**", "/cs/inquiries", "/cs/inquiries/**", "/adult-verify", "/adult-verify/**").authenticated()
                        // 로그인해야 하는 버튼 (비회원이 누르면 로그인 화면으로)
                        .requestMatchers(HttpMethod.POST, "/attendance", "/coupons/*/download", "/products/*/wish", "/reviews/*/helpful",
                                "/reviews/*/report", "/products/*/inquiries", "/inquiries/*/delete").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .successHandler(loginSuccessHandler)
                        .failureUrl("/login?error")
                        .permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HtmxAwareEntryPoint()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/"))
                .rememberMe(remember -> remember
                        .key("yangpang-dev-remember-me")
                        .rememberMeParameter("remember")
                        .tokenValiditySeconds(14 * 24 * 3600));
        return http.build();
    }

    /** {bcrypt} 접두어 방식. seed 모듈과 같은 인코더 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
