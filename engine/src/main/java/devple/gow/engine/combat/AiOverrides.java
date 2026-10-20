package devple.gow.engine.combat;

import devple.gow.tools.content.Content;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 적 AI 수치 덮어쓰기 (49 문서 §13). 계층의 {@code ai.properties} 를 합친 표에서 {@code <열쇠>.<항목>=값} 을 읽는다.
 * <pre>
 *   열쇠 (낮음 → 높음): *  &lt;  WAD 이름 (r_min, min)  &lt;  템플릿 Instance Name  &lt;  AI1 번호 (50, 050)
 *   항목: motion.* body.* ride.* soldier.* stone.*   ({@link AiData} 레코드 필드 이름)
 *         tmpl.*                                       ({@link AiTemplate} 필드 이름)
 *         health (= tmpl.hitPoints + tmpl.maxHitPoints), damage (= tmpl.damageMult), tree (= tmpl.decisionTree)
 *   값: 10 = 바꿈, *1.5 (또는 x1.5) = 원래 값에 곱함
 * </pre>
 * 적용 지점은 하나 — {@link EnemyFighter} 생성자 (AI1 → Sold 번호를 푸는 곳). 레코드는 바꾼 사본을 만든다. 표가 비면 원래 값 그대로.
 */
public final class AiOverrides {

	public static final String FILE = "ai.properties";
	public static final AiOverrides NONE = new AiOverrides(Map.of());

	private static volatile Content cachedFor;
	private static volatile Properties cached;

	/** "motion.maxSpeed" → "10" (위 열쇠가 덮은 결과). */
	private final Map<String, String> values;

	private AiOverrides(Map<String, String> values) {
		this.values = values;
	}

	/** 합친 ai.properties (계층이 바뀌면 다시 읽음). */
	static Properties table() {
		Content c = Content.get();
		if (c != cachedFor || cached == null) {
			cached = Content.properties(FILE);
			cachedFor = c;
		}
		return cached;
	}

	/** 적 하나의 열쇠 목록 (소문자, 낮음 → 높음). 플러그인 등록표 ({@code Plugins}) 도 같은 열쇠를 쓴다. */
	public static List<String> keys(int ai1, String name, String wad) {
		List<String> k = new ArrayList<>();
		k.add("*");
		if (wad != null) {
			String w = wad.toLowerCase(Locale.ROOT).replaceFirst("\\.wad$", "");
			k.add(w);
			if (w.startsWith("r_")) {
				k.add(w.substring(2));
			}
		}
		if (name != null && !name.isBlank()) {
			k.add(name.trim().toLowerCase(Locale.ROOT));
		}
		k.add(String.valueOf(ai1));
		k.add(String.format("%03d", ai1));
		return k;
	}

	public static AiOverrides of(List<String> keys) {
		Properties p = table();
		if (p.isEmpty()) {
			return NONE;
		}
		Map<String, String> v = new LinkedHashMap<>();
		for (String k : keys) {
			for (String pk : p.stringPropertyNames()) {
				if (!pk.toLowerCase(Locale.ROOT).startsWith(k + ".")) {
					continue;
				}
				String item = pk.substring(k.length() + 1), val = p.getProperty(pk).trim();
				switch (item) {
					case "health" -> {
						v.put("tmpl.hitPoints", val);
						v.put("tmpl.maxHitPoints", val);
					}
					case "damage" -> v.put("tmpl.damageMult", val);
					case "tree" -> v.put("tmpl.decisionTree", val);
					default -> v.put(item, val);
				}
			}
		}
		return v.isEmpty() ? NONE : new AiOverrides(Map.copyOf(v));
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	public Map<String, String> values() {
		return values;
	}

	public int i(String item, int def) {
		String s = values.get(item);
		return s == null ? def : (int) Math.round(num(s, def, item));
	}

	public float f(String item, float def) {
		String s = values.get(item);
		return s == null ? def : (float) num(s, def, item);
	}

	/** 레코드 사본: group.&lt;필드&gt; 가 있는 필드만 바꾼다 (없으면 같은 객체, null 은 null). */
	@SuppressWarnings("unchecked")
	public <R extends Record> R apply(String group, R rec) {
		if (rec == null || values.isEmpty()) {
			return rec;
		}
		RecordComponent[] cs = rec.getClass().getRecordComponents();
		Object[] args = new Object[cs.length];
		Class<?>[] types = new Class<?>[cs.length];
		boolean changed = false;
		try {
			for (int i = 0; i < cs.length; i++) {
				types[i] = cs[i].getType();
				args[i] = cs[i].getAccessor().invoke(rec);
				String item = group + "." + cs[i].getName(), s = values.get(item);
				if (s == null) {
					continue;
				}
				changed = true;
				if (types[i] == int.class) {
					args[i] = (int) Math.round(num(s, (Integer) args[i], item));
				} else if (types[i] == float.class) {
					args[i] = (float) num(s, (Float) args[i], item);
				} else if (types[i] == String.class) {
					args[i] = s.isEmpty() ? null : s;
				}
			}
			return changed ? (R) rec.getClass().getDeclaredConstructor(types).newInstance(args) : rec;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(group + " 덮어쓰기 실패", e);
		}
	}

	private static double num(String s, double base, String item) {
		try {
			if (s.startsWith("*") || s.startsWith("x")) {
				return base * Double.parseDouble(s.substring(1).trim());
			}
			if (s.startsWith("0x") || s.startsWith("0X")) {
				return Long.parseLong(s.substring(2), 16);
			}
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			System.err.println("[mods] ai.properties " + item + "=" + s + " 숫자 아님 — 무시");
			return base;
		}
	}

	@Override
	public String toString() {
		return values.toString();
	}
}
