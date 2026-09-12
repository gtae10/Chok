package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.service.SentimentAnalysisService;
import com.project.Chok.service.sentiment.AnthropicProvider;
import com.project.Chok.service.sentiment.LlmProvider;
import com.project.Chok.service.sentiment.OpenAiProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SentimentAnalysisService의 재료성 프롬프트가 실제로 의도한 대로 분류하는지 검증하는
 * 정형(canonical) 테스트셋. 실제 LLM API를 호출하므로(비용 발생) 기본 ./gradlew build/test
 * 에서는 제외되고 ./gradlew llmTest 로만 실행된다 - 프롬프트를 바꿀 때마다 이 커맨드로
 * 재실행해서 회귀 여부를 확인한다.
 *
 * 카테고리(SentimentAnalysisService의 SYSTEM_PROMPT 기준):
 * - 고재료성: |score| >= 0.6
 * - 저재료성/PR성: |score| <= 0.2
 * - 애매한 초기단계: 0.2 < |score| < 0.6
 */
@Tag("llm-live")
class SentimentAccuracyRegressionTest {

    private record Case(String category, String headline, double minAbs, double maxAbs, int expectedSign) {}

    private static final List<Case> CASES = List.of(
            // 고재료성 호재 (|score| >= 0.6, 양수)
            new Case("고재료성_호재", "OO전자, 자사주 5000억원 전량 소각 확정", 0.6, 1.0, 1),
            new Case("고재료성_호재", "OO바이오, 미국 제약사와 1조원 규모 신약 기술수출 계약 체결", 0.6, 1.0, 1),
            new Case("고재료성_호재", "OO전자 3분기 영업이익 전년比 45%↑…시장 컨센서스 큰 폭 상회", 0.6, 1.0, 1),
            new Case("고재료성_호재", "OO건설, 사우디아라비아서 3조원 규모 초대형 플랜트 수주", 0.6, 1.0, 1),

            // 고재료성 악재 (|score| >= 0.6, 음수)
            new Case("고재료성_악재", "OO그룹 회장, 2000억원대 배임·횡령 혐의로 구속기소", 0.6, 1.0, -1),
            new Case("고재료성_악재", "OO전자, 분식회계 혐의로 금융당국 특별감리 착수 및 검찰 고발", 0.6, 1.0, -1),
            new Case("고재료성_악재", "OO바이오, 신약 후보물질 임상 3상 실패로 개발 전면 중단", 0.6, 1.0, -1),
            new Case("고재료성_악재", "OO전자, 4분기 영업손실 전환…시장 예상치 크게 밑도는 어닝쇼크", 0.6, 1.0, -1),

            // 저재료성/PR성 (|score| <= 0.2, 톤과 무관)
            new Case("저재료성_PR", "OO전자, 임직원 봉사활동으로 지역사회와 상생 나서", 0.0, 0.2, 0),
            new Case("저재료성_PR", "OO그룹, 창립 30주년 기념행사 성황리 개최", 0.0, 0.2, 0),
            new Case("저재료성_PR", "OO전자, ESG 경영 우수기업 3년 연속 선정", 0.0, 0.2, 0),

            // 애매한 초기단계 (0.2 < |score| < 0.6)
            new Case("애매한_초기단계", "OO전자, 반도체 장비업체와 MOU 체결…구체적 계약 규모는 미정", 0.2, 0.6, 0),
            new Case("애매한_초기단계", "OO바이오, 해외 유통사와 사업 협력 논의…세부 조건은 추후 확정", 0.2, 0.6, 0),
            new Case("애매한_초기단계", "OO건설, 신사업 진출 검토 착수…구체적 시기와 규모는 불투명", 0.2, 0.6, 0)
    );

    @Test
    void canonical_headlines_match_expected_materiality() throws IOException {
        SentimentAnalysisService service = buildRealService();

        List<NewsArticle> articles = CASES.stream()
                .map(c -> new NewsArticle(c.headline(), "http://test.local", LocalDate.now()))
                .toList();

        List<SentimentResult> results = service.analyzeBatch("테스트기업", articles);
        assertThat(results).hasSize(CASES.size());

        StringBuilder report = new StringBuilder();
        report.append("category,headline,expectedRange,expectedSign,actualScore,actualLabel,pass,summary\n");

        int passCount = 0;
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            SentimentResult r = results.get(i);
            boolean magnitudeOk = Math.abs(r.getScore()) >= c.minAbs() && Math.abs(r.getScore()) <= c.maxAbs();
            boolean signOk = c.expectedSign() == 0 || Math.signum(r.getScore()) == c.expectedSign()
                    || r.getScore() == 0.0; // 0.0(중립)은 부호 판정에서 관대하게 허용
            boolean pass = magnitudeOk && signOk;
            if (pass) passCount++;

            report.append(String.join(",",
                    c.category(),
                    "\"" + c.headline() + "\"",
                    "[" + c.minAbs() + "," + c.maxAbs() + "]",
                    String.valueOf(c.expectedSign()),
                    String.valueOf(r.getScore()),
                    r.getLabel(),
                    String.valueOf(pass),
                    "\"" + r.getSummary().replace("\"", "'") + "\""
            )).append("\n");
        }

        String summaryLine = String.format("%n=== 정형 테스트셋 결과: %d/%d 통과 ===%n", passCount, CASES.size());
        System.out.println(report);
        System.out.println(summaryLine);

        Path outPath = Path.of("sentiment-accuracy-report.csv");
        Files.writeString(outPath, report.toString());
        System.out.println("결과 저장: " + outPath.toAbsolutePath());

        assertThat(passCount)
                .withFailMessage("정형 테스트셋 %d개 중 %d개만 기대 기준을 통과함 (상세: %s)", CASES.size(), passCount, outPath.toAbsolutePath())
                .isEqualTo(CASES.size());
    }

    private SentimentAnalysisService buildRealService() throws IOException {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new UncheckedIOException(new IOException(
                        "application.properties를 찾을 수 없음 - src/main/resources/application.properties에 실제 API 키 설정 필요"));
            }
            props.load(is);
        }

        AppProperties appProperties = new AppProperties();

        AppProperties.Sentiment sentiment = new AppProperties.Sentiment();
        sentiment.setProvider(props.getProperty("chok.sentiment.provider", "openai"));
        sentiment.setMaxConcurrentCalls(Integer.parseInt(props.getProperty("chok.sentiment.max-concurrent-calls", "3")));
        appProperties.setSentiment(sentiment);

        AppProperties.OpenAi openai = new AppProperties.OpenAi();
        openai.setApiKey(props.getProperty("chok.openai.api-key"));
        openai.setModel(props.getProperty("chok.openai.model"));
        openai.setBaseUrl(props.getProperty("chok.openai.base-url"));
        appProperties.setOpenai(openai);

        AppProperties.Anthropic anthropic = new AppProperties.Anthropic();
        anthropic.setApiKey(props.getProperty("chok.anthropic.api-key"));
        anthropic.setModel(props.getProperty("chok.anthropic.model"));
        anthropic.setBaseUrl(props.getProperty("chok.anthropic.base-url"));
        appProperties.setAnthropic(anthropic);

        WebClient webClient = WebClient.builder().build();
        Map<String, LlmProvider> providers = Map.of(
                "openai", new OpenAiProvider(webClient, appProperties),
                "anthropic", new AnthropicProvider(webClient, appProperties)
        );

        return new SentimentAnalysisService(providers, appProperties);
    }
}
