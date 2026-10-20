package devple.gow.tools.video;

import devple.gow.tools.content.Content;

import java.util.Locale;
import java.util.Properties;

/**
 * 스크립트/실행 파일의 동영상 이름 → TOC 파일 이름 (18 문서 §5).
 * 모드 (49 문서 §10): 계층의 {@code movies.properties} 로 바꿔 틀거나 건너뛴다.
 * <pre>
 *   Semo01 = MYINTRO     Semo01 대신 MYINTRO.PSS (rom/ 또는 모드 rom/, 없으면 ISO TOC)
 *   a01Mp03 = skip       재생하지 않고 바로 끝 (스크립트 종료 이벤트는 원래 이름으로 그대로)
 * </pre>
 * 키는 대소문자·확장자 무관. 연쇄는 따르지 않는다.
 */
public final class MovieNames {

	public static final String FILE = "movies.properties";
	public static final String SKIP = "skip";

	private MovieNames() {
	}

	/** "a01mp05" / "A01MP05.PSS" → "A01MP05". */
	public static String fileBase(String name) {
		String n = name.trim();
		int dot = n.lastIndexOf('.');
		if (dot > 0) {
			n = n.substring(0, dot);
		}
		return n.toUpperCase(Locale.ROOT);
	}

	/** 바꿔 틀 이름 (fileBase 형식). 바꿈이 없으면 원래 이름, 건너뛰면 null. */
	public static String remap(String name) {
		String base = fileBase(name);
		Properties t = Content.properties(FILE);
		for (String k : t.stringPropertyNames()) {
			if (fileBase(k).equals(base)) {
				String v = t.getProperty(k).trim();
				if (v.equalsIgnoreCase(SKIP)) {
					return null;
				}
				return v.isEmpty() ? base : fileBase(v);
			}
		}
		return base;
	}

	/** movies.properties 에서 건너뛰기로 지정했는지. */
	public static boolean skipped(String name) {
		return remap(name) == null;
	}
}
