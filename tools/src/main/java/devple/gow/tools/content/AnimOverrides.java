package devple.gow.tools.content;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 애니메이션 교체·속도 (49 문서 §12). 계층의 {@code anim.properties} 를 합친 표:
 * <pre>
 *   speed.&lt;클립&gt;=1.5                 재생 속도 배율 (시간 진행 × 배율 — 루트 모션·전투 이벤트도 같은 시간축이라 함께 빨라짐)
 *   replace.&lt;클립&gt;=&lt;다른 클립&gt;        같은 ANM 세트에서 &lt;클립&gt; 을 찾으면 &lt;다른 클립&gt; 을 돌려줌 (세트에 없으면 무시)
 *   speed.&lt;클립&gt;@&lt;ANM세트&gt;=…  replace.&lt;클립&gt;@&lt;ANM세트&gt;=…   한 세트에만 (이름만 쓴 키보다 우선. ':' 는 properties 구분자라 못 씀)
 * </pre>
 * 이름은 대소문자를 가리지 않는다. 표가 비면 조회는 곧바로 돌아온다.
 */
public final class AnimOverrides {

	public static final String FILE = "anim.properties";

	private record Table(Map<String, String> replace, Map<String, Float> speed) {
	}

	private static final LayerCache<Table> TABLE = new LayerCache<>(c -> parse(Content.properties(FILE)));

	private AnimOverrides() {
	}

	private static Table parse(Properties p) {
		Map<String, String> rep = new HashMap<>();
		Map<String, Float> spd = new HashMap<>();
		for (String k : p.stringPropertyNames()) {
			String v = p.getProperty(k).trim();
			if (k.startsWith("replace.") && !v.isEmpty()) {
				rep.put(k.substring(8).toLowerCase(Locale.ROOT), v);
			} else if (k.startsWith("speed.")) {
				try {
					float f = Float.parseFloat(v);
					if (f > 0 && Float.isFinite(f)) {
						spd.put(k.substring(6).toLowerCase(Locale.ROOT), f);
					} else {
						System.err.println("[mods] " + FILE + " " + k + ": 0 보다 커야 함");
					}
				} catch (NumberFormatException e) {
					System.err.println("[mods] " + FILE + " " + k + ": 숫자 아님 " + v);
				}
			}
		}
		return new Table(Map.copyOf(rep), Map.copyOf(spd));
	}

	/** set 의 anim (앞의 "그룹/" 은 무시) 을 대신할 클립 이름. 없으면 null. */
	public static String replacement(String set, String anim) {
		Map<String, String> m = TABLE.get().replace;
		return m.isEmpty() ? null : lookup(m, set, anim);
	}

	/** set 의 anim 재생 속도 배율 (기본 1). */
	public static float speed(String set, String anim) {
		Map<String, Float> m = TABLE.get().speed;
		if (m.isEmpty() || anim == null) {
			return 1f;
		}
		Float f = lookup(m, set, anim);
		return f != null ? f : 1f;
	}

	private static <V> V lookup(Map<String, V> m, String set, String anim) {
		int slash = anim.lastIndexOf('/');
		String a = (slash >= 0 ? anim.substring(slash + 1) : anim).toLowerCase(Locale.ROOT);
		V v = set != null ? m.get(a + "@" + set.toLowerCase(Locale.ROOT)) : null;
		return v != null ? v : m.get(a);
	}
}
