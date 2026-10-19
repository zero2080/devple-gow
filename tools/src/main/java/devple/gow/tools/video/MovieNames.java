package devple.gow.tools.video;

import java.util.Locale;

/** 스크립트/실행 파일의 동영상 이름 → TOC 파일 이름 (18 문서 §5). */
public final class MovieNames {

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
}
