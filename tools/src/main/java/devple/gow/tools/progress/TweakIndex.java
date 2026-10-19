package devple.gow.tools.progress;

import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WAD 의 TWK(태그 0x71) 값을 "경로 + 키 이름"으로 찾는 색인. 키는 {@link TweakFile#hash(String)} 로 비교한다.
 * 경로 = 0x80 레코드 문자열 + 0x10 그룹 이름들 (예: {@code /Player/Costume 3/}).
 */
public final class TweakIndex {

	/** 태그 이름(TWK_Player, TWK_Orb_000 ...) → (경로|해시 → 엔트리). */
	private final Map<String, Map<String, TweakFile.Entry>> byTag = new LinkedHashMap<>();

	public static TweakIndex of(WadFile wad) {
		TweakIndex ix = new TweakIndex();
		for (WadTag t : wad.tags()) {
			if (t.tag() != WadTagType.TWEAK || !t.hasData()) {
				continue;
			}
			Map<String, TweakFile.Entry> m = new HashMap<>();
			String root = "";
			Deque<String> groups = new ArrayDeque<>();
			for (TweakFile.Entry e : TweakFile.parse(t.data()).entries()) {
				switch (e.kind()) {
					case PATH -> {
						root = e.text();
						groups.clear();
					}
					case GROUP -> groups.addLast(e.text());
					case END -> {
						if (!groups.isEmpty()) {
							groups.removeLast();
						}
					}
					case NUMBER, STRING -> m.put(key(path(root, groups), e.hash()), e);
					default -> {
					}
				}
			}
			ix.byTag.put(t.name(), m);
		}
		return ix;
	}

	private static String path(String root, Deque<String> groups) {
		StringBuilder sb = new StringBuilder(root.endsWith("/") ? root : root + "/");
		for (String g : groups) {
			sb.append(g).append('/');
		}
		return sb.toString();
	}

	private static String key(String path, int hash) {
		return path + "|" + Integer.toHexString(hash);
	}

	public TweakFile.Entry find(String tag, String path, String name) {
		return findHash(tag, path, TweakFile.hash(name));
	}

	public TweakFile.Entry findHash(String tag, String path, int hash) {
		Map<String, TweakFile.Entry> m = byTag.get(tag);
		return m == null ? null : m.get(key(path, hash));
	}

	/** float 값 (없으면 NaN). */
	public float f(String tag, String path, String name) {
		TweakFile.Entry e = find(tag, path, name);
		return e == null ? Float.NaN : Float.intBitsToFloat(e.raw());
	}

	/** 정수/열거형 값. 하위 바이트만 유효한 경우가 많다(06 문서 §5.2). */
	public int lowByte(String tag, String path, String name) {
		TweakFile.Entry e = find(tag, path, name);
		return e == null ? -1 : e.raw() & 0xFF;
	}

	public String s(String tag, String path, String name) {
		TweakFile.Entry e = find(tag, path, name);
		return e == null ? null : e.text();
	}

	public Iterable<String> tags() {
		return byTag.keySet();
	}
}
