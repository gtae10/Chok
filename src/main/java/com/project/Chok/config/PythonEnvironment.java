package com.project.Chok.config;

import org.springframework.stereotype.Component;

import java.io.File;

/**
 * python-collector 관련 경로를 전부 여기서 계산한다.
 * application.properties에는 chok.python-collector.base-dir 하나만 지정하면 되고
 * (기본값은 상대경로 "python-collector"), 그 안의 파이썬 실행파일/스크립트/모델파일
 * 위치는 여기서 OS에 맞게 자동으로 만들어준다.
 *
 * base-dir이 상대경로면 애플리케이션 실행 위치(user.dir) 기준으로 절대경로로 바꿔서 쓴다.
 * ProcessBuilder로 외부 프로세스를 실행할 때, 실행 파일 경로를 상대경로로 주면 OS별로
 * 어느 디렉터리 기준으로 찾을지가 애매해지는 문제가 있어서, 항상 절대경로로 넘겨준다.
 */
@Component
public class PythonEnvironment {

    private final File baseDir;

    public PythonEnvironment(AppProperties appProperties) {
        File configured = new File(appProperties.getPythonCollector().getBaseDir());
        this.baseDir = configured.isAbsolute()
                ? configured
                : new File(System.getProperty("user.dir"), configured.getPath());
    }

    public File workingDirectory() {
        return baseDir;
    }

    public String pythonExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String relative = windows ? ".venv/Scripts/python.exe" : ".venv/bin/python";
        return new File(baseDir, relative).getAbsolutePath();
    }

    public String collectScriptPath() {
        return new File(baseDir, "collect.py").getAbsolutePath();
    }

    public String trainScriptPath() {
        return new File(baseDir, "train_model.py").getAbsolutePath();
    }

    public String modelOutputPath() {
        return new File(baseDir, "model" + File.separator + "rise_model.json").getAbsolutePath();
    }
}
