package com.yangpang.api.config;

import com.yangpang.api.security.GradeRefreshInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final GradeRefreshInterceptor gradeRefreshInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gradeRefreshInterceptor)
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/favicon.svg");
    }
}
