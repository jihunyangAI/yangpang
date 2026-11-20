package com.yangpang.api.search;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** 검색 색인처럼 요청 응답을 늦출 필요가 없는 일은 별도 스레드에서 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
