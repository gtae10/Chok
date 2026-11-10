package com.project.Chok;

import com.project.Chok.service.AnalysisScheduler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@SpringBootApplication
@EnableScheduling
public class ChokApplication {
    public static void main(String[] args) {
        // --run-once: 웹 서버 없이 수집+분석 한 번 하고 종료 (Windows 작업 스케줄러가 하루 두 번 실행)
        if (Arrays.asList(args).contains("--run-once")) {
            // 명령행 인자로 넘겨야 application.properties 값을 이긴다 (.properties() 기본값은 진다)
            // scheduler.enabled=false: 같은 JVM의 @Scheduled runDaily가 겹쳐 돌지 않게 (runNow는 이 값을 안 본다)
            String[] runArgs = Arrays.copyOf(args, args.length + 2);
            runArgs[args.length] = "--chok.scheduler.catch-up-on-startup=false";
            runArgs[args.length + 1] = "--chok.scheduler.enabled=false";
            int exitCode = 1;
            try {
                ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ChokApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(runArgs);
                boolean ok = ctx.getBean(AnalysisScheduler.class).runNow();
                exitCode = SpringApplication.exit(ctx, () -> ok ? 0 : 1);
            } catch (Throwable t) {
                t.printStackTrace(); // Error까지 잡아 무인 실행이 멈추거나 0으로 끝나지 않게
            } finally {
                System.exit(exitCode);
            }
        }
        SpringApplication.run(ChokApplication.class, args);
    }
}
