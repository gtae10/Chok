package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.config.PythonEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DataCollectionService {

    private final AppProperties appProperties;
    private final PythonEnvironment pythonEnvironment;
    private final Map<String, String> dbEnv;

    public DataCollectionService(AppProperties appProperties, PythonEnvironment pythonEnvironment,
                                  @Value("${spring.datasource.username}") String dbUsername,
                                  @Value("${spring.datasource.password}") String dbPassword) {
        this.appProperties = appProperties;
        this.pythonEnvironment = pythonEnvironment;
        // collect.py(db.py)는 DB_USER/DB_PASSWORD를 os.environ에서 직접 읽는다 (ModelTrainingService 참고)
        this.dbEnv = Map.of("DB_USER", dbUsername, "DB_PASSWORD", dbPassword);
    }

    public String runCollection() {
        return pythonEnvironment.run(pythonEnvironment.collectScriptPath(),
                appProperties.getCollector().getTimeoutSeconds(), "Python 수집기", dbEnv);
    }
}
