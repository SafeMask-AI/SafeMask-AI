package haitai.safemask.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 장시간 AI 실행과 SSE heartbeat가 일반 웹 요청 스레드를 점유하지 않게 하는 전용 풀입니다. */
@Configuration
public class AiExecutionConfig {

	@Bean(name = "aiTaskExecutor")
	public ThreadPoolTaskExecutor aiTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(4);
		executor.setMaxPoolSize(12);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("safemask-ai-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(20);
		executor.initialize();
		return executor;
	}

	@Bean(name = "aiHeartbeatScheduler")
	public ThreadPoolTaskScheduler aiHeartbeatScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("safemask-sse-heartbeat-");
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		scheduler.initialize();
		return scheduler;
	}
}
