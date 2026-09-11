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

/**
 * python-collector/train_model.py 를 실행해 상승확률 로지스틱 회귀 모델을 재학습한다.
 * 데이터가 아직 부족하면 스크립트가 exitCode 0으로 조용히 스킵하도록 되어 있어서
 * (train_model.py의 MIN_SAMPLES 체크), 이 서비스도 그 경우를 실패로 취급하지 않는다.
 */
@Service
public class ModelTrainingService {

    private static final Logger log = LoggerFactory.getLogger(ModelTrainingService.class);

    private final AppProperties appProperties;
    private final PythonEnvironment pythonEnvironment;
    private final String dbUsername;
    private final String dbPassword;

    public ModelTrainingService(AppProperties appProperties, PythonEnvironment pythonEnvironment,
                                 @Value("${spring.datasource.username}") String dbUsername,
                                 @Value("${spring.datasource.password}") String dbPassword) {
        this.appProperties = appProperties;
        this.pythonEnvironment = pythonEnvironment;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    public String retrain() {
        AppProperties.Model model = appProperties.getModel();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonEnvironment.pythonExecutable(),
                    pythonEnvironment.trainScriptPath()
            );
            pb.directory(pythonEnvironment.workingDirectory());
            pb.redirectErrorStream(true);

            // train_model.py(db.py)는 DB_USER/DB_PASSWORD를 os.environ에서 직접 읽는다.
            // 이 프로세스(Java)의 환경변수에 그 값이 없으면(로컬에서 흔함 - Docker에서는
            // docker-compose.yml이 컨테이너 환경변수로 이미 넣어줌) 인증에 실패하므로,
            // Spring이 이미 갖고 있는 spring.datasource.* 값을 자식 프로세스 환경변수로 명시적으로 넘긴다.
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
                    log.info("[ModelTrain] {}", line);
                }
            }

            boolean finished = process.waitFor(model.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("모델 학습 시간 초과 (" + model.getTimeoutSeconds() + "초)");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("모델 학습 스크립트 비정상 종료 (exitCode=" + exitCode + ")");
            }

            log.info("모델 학습 스크립트 정상 완료");
            return output.toString();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("모델 학습 실행 중 오류: " + e.getMessage(), e);
        }
    }
}
