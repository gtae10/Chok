package com.project.Chok.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 종목 뉴스 API가 돌려준 기사가 정말 그 종목 이야기인지 거른다.
 * 네이버 종목 뉴스는 기사 전문에 종목명이 한 번만 나와도(예: 매경플러스 안내 문구의 "네이버에서 검색")
 * 그 종목 뉴스로 묶어서, 전통시장 축제·시황·다른 회사 기사가 감성점수와 재료성 태그에 섞여 들어온다.
 *
 * 규칙: 제목 또는 본문 앞부분(API의 body, "※" 이후 안내 문구는 제외)에 종목명/별칭이 있어야 통과.
 * 별칭은 우선주 접미사·'홀딩스'/'지주' 제거와 아래 수동 목록(자주 쓰는 약칭)뿐이다.
 * ponytail: 약칭은 수동 목록이라 빠진 별칭은 정상 기사가 걸러진다 - 종목별 0건이 잦으면 ALIASES에 추가.
 */
public final class NewsRelevance {

    private static final Pattern SPACES = Pattern.compile("\\s");
    private static final Pattern PREFERRED_SUFFIX = Pattern.compile("([0-9]?우[A-Z]?)$");
    private static final Pattern PAREN = Pattern.compile("\\(.*?\\)");

    private static final Map<String, List<String>> ALIASES = Map.of(
            "NAVER", List.of("네이버"),
            "삼성전자", List.of("삼전"),
            "SK하이닉스", List.of("하이닉스"),
            "POSCO홀딩스", List.of("포스코"),
            "현대차", List.of("현대자동차"),
            "LG에너지솔루션", List.of("엔솔"),
            "삼성바이오로직스", List.of("삼바"),
            "HD현대중공업", List.of("현대중공업"),
            "HD한국조선해양", List.of("한국조선해양"));

    private NewsRelevance() {}

    public static boolean isRelevant(String stockName, String headline, String body) {
        if (stockName == null || stockName.isBlank()) return true; // 이름을 모르면 걸러낼 근거가 없다
        String text = normalize(headline) + " " + normalize(stripNotice(body));
        return aliases(stockName).stream().anyMatch(text::contains);
    }

    static Set<String> aliases(String stockName) {
        String base = PAREN.matcher(stockName).replaceAll("").trim();
        String common = PREFERRED_SUFFIX.matcher(base).replaceAll(""); // 삼성전자우 -> 삼성전자
        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        out.add(common);
        for (String suffix : List.of("홀딩스", "지주")) {
            if (common.endsWith(suffix) && common.length() - suffix.length() >= 2) {
                out.add(common.substring(0, common.length() - suffix.length()));
            }
        }
        out.addAll(ALIASES.getOrDefault(base, List.of()));
        out.addAll(ALIASES.getOrDefault(common, List.of()));
        Set<String> normalized = new LinkedHashSet<>();
        for (String a : out) {
            String n = normalize(a);
            if (n.length() >= 2) normalized.add(n);
        }
        return normalized;
    }

    /** "※ 기사 전문은 ...에서 확인할 수 있습니다. 네이버에서 ... 검색" 같은 안내 문구는 기사 내용이 아니다. */
    private static String stripNotice(String body) {
        if (body == null) return "";
        int i = body.indexOf('※');
        return i >= 0 ? body.substring(0, i) : body;
    }

    private static String normalize(String s) {
        return s == null ? "" : SPACES.matcher(s).replaceAll("").toLowerCase();
    }
}
