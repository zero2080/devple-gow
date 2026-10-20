package devple.gow.engine.ui;

import devple.gow.tools.content.Content;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * TrueType 폰트 교체 설정 (49 문서 §7). 계층의 {@code font.properties}:
 * <pre>
 *   ttf=fonts/MyFont.ttf   계층 디렉터리 기준 상대 경로 (Content.find, 위 계층 우선) 또는 절대 경로
 *   index=0                .ttc 안의 폰트 번호
 *   scale=1.0              FLP 글자 높이 (em) 에 대한 배율
 *   yOffset=0              기준선 이동, em 비율 (+ = 아래)
 *   mode=missing           missing = FLP 폰트에 없는 글자만, all = 모든 글자 (TTF 에 없으면 FLP)
 *   bakeSize=48            아틀라스에 굽는 em 픽셀 크기
 * </pre>
 * ttf 가 없거나 파일을 못 찾으면 null — 원래 FLP 글리프만 쓴다.
 */
public record FontOverride(Path ttf, int index, float scale, float yOffset, boolean all, int bakeSize) {

	public static final String FILE = "font.properties";

	public static FontOverride load() {
		return parse(Content.properties(FILE), Content::find);
	}

	/** find: 상대 경로 → 계층 파일. */
	public static FontOverride parse(Properties p, Function<String, Optional<Path>> find) {
		String rel = p.getProperty("ttf", "").trim();
		if (rel.isEmpty()) {
			return null;
		}
		Path ttf = Path.of(rel).isAbsolute() ? Path.of(rel) : find.apply(rel).orElse(null);
		if (ttf == null || !Files.isRegularFile(ttf)) {
			warn("ttf '" + rel + "' 없음 — FLP 폰트 그대로");
			return null;
		}
		String mode = p.getProperty("mode", "missing").trim().toLowerCase(Locale.ROOT);
		if (!mode.equals("missing") && !mode.equals("all")) {
			warn("mode '" + mode + "' 모름 — missing");
			mode = "missing";
		}
		return new FontOverride(ttf, (int) num(p, "index", 0, 0, 1000), num(p, "scale", 1f, 0.05f, 20f),
				num(p, "yOffset", 0f, -10f, 10f), mode.equals("all"), (int) num(p, "bakeSize", 48, 8, 256));
	}

	private static float num(Properties p, String key, float def, float min, float max) {
		String v = p.getProperty(key);
		if (v == null || v.isBlank()) {
			return def;
		}
		try {
			float f = Float.parseFloat(v.trim());
			if (f >= min && f <= max) {
				return f;
			}
		} catch (NumberFormatException ignored) {
		}
		warn(key + "='" + v.trim() + "' 범위 밖 — " + def);
		return def;
	}

	private static void warn(String msg) {
		System.err.println("[font] " + FILE + ": " + msg);
	}
}
