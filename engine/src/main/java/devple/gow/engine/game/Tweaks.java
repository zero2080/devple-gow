package devple.gow.engine.game;

import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * 원작 튜닝값(TWK_) 런타임 조회. 게임 수치를 코드에 박지 않고 디스크 데이터에서 읽는다.
 * <p>
 * 노드 경로 = PATH 레코드 + 중첩 GROUP 이름. 예: {@code "/Player/"}, {@code "/Player/Weapon Level 2/"}.
 * 키는 원작과 같은 해시({@link TweakFile#hash})로 찾으므로 이름 복원 여부와 무관하게 동작한다.
 * 같은 경로/키가 여러 WAD 에 있으면 나중에 로드한 값이 우선 (레벨 WAD 가 상주 WAD 를 덮어씀).
 */
public final class Tweaks {

	private final Map<String, Map<Integer, Integer>> numbers = new HashMap<>();
	private final Map<String, Map<Integer, String>> strings = new HashMap<>();

	public void load(WadFile wad) {
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.TWEAK && t.hasData()) {
				add(TweakFile.parse(t.data()));
			}
		}
	}

	private void add(TweakFile f) {
		String root = "/";
		Deque<String> groups = new ArrayDeque<>();
		for (TweakFile.Entry e : f.entries()) {
			switch (e.kind()) {
				case PATH -> {
					root = e.text();
					groups.clear();
				}
				case GROUP -> groups.addLast(e.text());
				case END -> groups.pollLast();
				case NUMBER -> numbers.computeIfAbsent(node(root, groups), k -> new HashMap<>()).put(e.hash(), e.raw());
				case STRING -> strings.computeIfAbsent(node(root, groups), k -> new HashMap<>()).put(e.hash(), e.text());
				default -> {
				}
			}
		}
	}

	private static String node(String root, Deque<String> groups) {
		StringBuilder sb = new StringBuilder(root.endsWith("/") ? root : root + "/");
		for (String g : groups) {
			sb.append(g).append('/');
		}
		return sb.toString();
	}

	public OptionalInt raw(String node, String key) {
		Map<Integer, Integer> m = numbers.get(node);
		Integer v = m == null ? null : m.get(TweakFile.hash(key));
		return v == null ? OptionalInt.empty() : OptionalInt.of(v);
	}

	/** float 형 값. 없으면 def. */
	public float f(String node, String key, float def) {
		OptionalInt r = raw(node, key);
		return r.isPresent() ? Float.intBitsToFloat(r.getAsInt()) : def;
	}

	/**
	 * int 형 값. 실행 파일 쪽 변수가 int/short/bool 이면 하위 바이트만 유효할 수 있다(06 문서 §TWK) —
	 * 그 경우 호출자가 마스크한다.
	 */
	public int i(String node, String key, int def) {
		OptionalInt r = raw(node, key);
		return r.isPresent() ? r.getAsInt() : def;
	}

	public String s(String node, String key, String def) {
		Map<Integer, String> m = strings.get(node);
		String v = m == null ? null : m.get(TweakFile.hash(key));
		return v == null ? def : v;
	}

	/** 키 이름이 복원되지 않은 값용: 해시로 직접 조회. */
	public OptionalInt rawHash(String node, int hash) {
		Map<Integer, Integer> m = numbers.get(node);
		Integer v = m == null ? null : m.get(hash);
		return v == null ? OptionalInt.empty() : OptionalInt.of(v);
	}

	/** short/bool 형 값 (상위 바이트 쓰레기 제거). */
	public int i16(String node, String key, int def) {
		OptionalInt r = raw(node, key);
		return r.isPresent() ? (short) r.getAsInt() : def;
	}

	/**
	 * 템플릿 노드 찾기: prefix(예 "/TweakTemplates/OrbEmitter/") 아래에서 "Instance Name" 이 name 인 노드.
	 */
	public String findTemplate(String prefix, String instanceName) {
		int h = TweakFile.hash("Instance Name");
		for (Map.Entry<String, Map<Integer, String>> e : strings.entrySet()) {
			if (e.getKey().startsWith(prefix) && instanceName.equals(e.getValue().get(h))) {
				return e.getKey();
			}
		}
		return null;
	}

	public int nodeCount() {
		return numbers.size() + strings.size();
	}
}
