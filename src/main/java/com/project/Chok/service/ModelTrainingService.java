package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.config.PythonEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * python-collector/train_model.py 를 실행해 상승확률 로지스틱 회귀 모델을 재학습한다.
 * 데이터가 아직 부족하면 스크립트가 exitCode 0으로 조용히 스킵하도록 되어 있어서
 * (train_model.py의 MIN_SAMPLES 체크), 이 서비스도 그 경우를 실패로 취급하지 않는다.
 */
@Service
public class ModelTrainingService {

    private final AppProperties appProperties;
    private final PythonEnvironment pythonEnvironment;
    private final Map<String, String> dbEnv;

    public ModelTrainingService(AppProperties appProperties, PythonEnvironment pythonEnvironment,
                                 @Value("${spring.datasource.username}") String dbUsername,
                                 @Value("${spring.datasource.password}") String dbPassword) {
        this.appProperties = appProperties;
        this.pythonEnvironment = pythonEnvironment;
        // train_model.py(db.py)는 DB_USER/DB_PASSWORD를 os.environ에서 직접 읽는다.
        // Java 프로세스 환경변수에 그 값이 없으면(로컬에서 흔함 - Docker에서는 docker-compose.yml이
        // 컨테이너 환경변수로 이미 넣어줌) 인증에 실패하므로 spring.datasource.* 값을 명시적으로 넘긴다.
        this.dbEnv = Map.of("DB_USER", dbUsername, "DB_PASSWORD", dbPassword);
    }

    public String retrain() {
        return pythonEnvironment.run(pythonEnvironment.trainScriptPath(),
                appProperties.getModel().getTimeoutSeconds(), "모델 학습 스크립트", dbEnv);
    }
}
