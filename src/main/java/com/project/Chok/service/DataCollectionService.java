package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.config.PythonEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DataCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DataCollectionService.class);

    private final AppProperties appProperties;
    private final PythonEnvironment pythonEnvironment;
    private final String dbUsername;
    private final String dbPassword;

    public DataCollectionService(AppProperties appProperties, PythonEnvironment pythonEnvironment,
                                  @Value("${spring.datasource.username}") String dbUsername,
                                  @Value("${spring.datasource.password}") String dbPassword) {
        this.appProperties = appProperties;
        this.pythonEnvironment = pythonEnvironment;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    public String runCollection() {
        AppProperties.Collector collector = appProperties.getCollector();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonEnvironment.pythonExecutable(),
                    pythonEnvironment.collectScriptPath()
            );
            pb.directory(pythonEnvironment.workingDirectory());
            pb.redirectErrorStream(true);

            // collect.py(db.py)도 train_model.py와 마찬가지로 DB_USER/DB_PASSWORD를
            // os.environ에서 직접 읽는다 (ModelTrainingService 참고).
            Map<String, String> env = pb.environment();
            env.put("DB_USER", dbUsername);
            env.put("DB_PASSWORD", dbPassword);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("[Python] {}", line);
                }
            }

            boolean finished = process.waitFor(
                    collector.getTimeoutSeconds(), TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Python 수집기 실행 시간 초과 ("
                        + collector.getTimeoutSeconds() + "초)");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Python 수집기 비정상 종료 (exitCode=" + exitCode + ")");
            }

            log.info("Python 수집기 정상 완료");
            return output.toString();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Python 수집기 실행 중 오류: " + e.getMessage(), e);
        }
    }
}
