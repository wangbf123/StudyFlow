package com.studyflow.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {
    @Bean("parseTaskExecutor")
    public ThreadPoolTaskExecutor parseTaskExecutor() { return executor("studyflow-parse-",4,8,200); }

    @Bean("summaryTaskExecutor")
    public ThreadPoolTaskExecutor summaryTaskExecutor() { return executor("studyflow-summary-",1,2,32); }

    @Bean("qaTaskExecutor")
    public ThreadPoolTaskExecutor qaTaskExecutor() { return executor("studyflow-qa-",4,8,200); }

    private ThreadPoolTaskExecutor executor(String prefix,int core,int maximum,int capacity) {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);executor.setMaxPoolSize(maximum);executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(task -> {
            var context=MDC.getCopyOfContextMap();
            return () -> {
                var previous=MDC.getCopyOfContextMap();
                try { if(context==null) MDC.clear(); else MDC.setContextMap(context); task.run(); }
                finally { if(previous==null) MDC.clear(); else MDC.setContextMap(previous); }
            };
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
