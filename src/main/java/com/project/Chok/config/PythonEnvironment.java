package com.project.Chok.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private static final Logger log = LoggerFactory.getLogger(PythonEnvironment.class);

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

    /** 기간별 배포 모델(rise_model_30d.json 식)이 저장되는 디렉터리. */
    public File modelDir() {
        return new File(baseDir, "model");
    }

    /** 기본 배포 모델(rise_model.json)을 제외한, 기간별로 따로 저장된 모델 파일들. */
    public File[] horizonModelFiles() {
        File[] files = modelDir().listFiles((dir, name) -> name.matches("rise_model_\\d+d\\.json"));
        return files == null ? new File[0] : files;
    }

    /**
     * 가상환경 파이썬으로 script를 실행하고 출력 전체를 반환한다. 제한 시간을 넘기거나
     * exitCode가 0이 아니면 RuntimeException. label은 로그 태그와 오류 메시지에 쓰인다.
     */
    public String run(String script, int timeoutSeconds, String label, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(pythonExecutable(), script);
        pb.directory(baseDir);
        pb.redirectErrorStream(true);
        pb.environment().putAll(env);
        // 윈도우 파이썬은 기본이 cp949 출력이라 UTF-8로 읽으면 한글 로그가 깨진다
        pb.environment().put("PYTHONUTF8", "1");

        try {
            Process process = pb.start();
            StringBuffer output = new StringBuffer();
            // 출력은 별도 스레드에서 읽는다 - 여기서 EOF까지 읽고 나서 waitFor하면
            // 파이썬이 멈췄을 때 제한 시간이 걸리지 않고 무한 대기한다
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        output.append(line).append('\n');
                        log.info("[{}] {}", label, line);
                    }
                } catch (IOException ignored) {
                    // 강제 종료로 스트림이 닫힌 경우
                }
            }, "python-output");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException(label + " 실행 시간 초과 (" + timeoutSeconds + "초)");
            }
            reader.join(5000);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException(label + " 비정상 종료 (exitCode=" + exitCode + ")");
            }
            log.info("{} 정상 완료", label);
            return output.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(label + " 실행 중단됨", e);
        } catch (IOException e) {
            throw new RuntimeException(label + " 실행 중 오류: " + e.getMessage(), e);
        }
    }
}
