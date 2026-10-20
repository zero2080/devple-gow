package devple.gow.tools.content;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 스테이지 표 (49 문서 §9). 계층의 {@code stages.properties}:
 * <pre>
 *   start = ATHN01A              새 게임 시작 레벨 (기본 원작 0x2B9740 "Athn01A")
 *   start.entry =                시작 인스턴스 (비우면 레벨 기본 시작점)
 *   redirect.ATHN02A = MYLVL01   레벨 ATHN02A 를 적재할 때 MYLVL01.WAD 를 읽는다 (rom/ 또는 모드 rom/)
 * </pre>
 * 바꾸는 것은 읽는 <b>WAD 파일</b>뿐이다. 레벨의 논리 이름 (세이브·스크립트 LoadWad/LoadCheck·이웃 슬롯·보스 표·오프닝 판정) 은
 * 그대로이고, 대신 읽은 WadFile 의 이름도 논리 이름이 된다 ({@link #logicalName}). 연쇄는 따르지 않는다 (한 번만).
 * 대상 WAD 가 어느 계층에도 없으면 경고하고 원래 WAD 를 쓴다. 파일이 없으면 원작 그대로.
 */
public final class Stages {

	public static final String FILE = "stages.properties";
	/** 원작 새 게임 시작 레벨. */
	public static final String DEFAULT_START = "ATHN01A";

	/** 대신 읽은 실제 WAD 경로 (정규화) → 논리 WAD 파일 이름 (예: .../MYLVL01.WAD → ATHN02A.WAD). */
	private static final Map<Path, String> LOGICAL = new ConcurrentHashMap<>();
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private Stages() {
	}

	private static Properties table() {
		return Content.properties(FILE);
	}

	/** 새 게임 시작 레벨 (대문자). */
	public static String start() {
		String s = table().getProperty("start", "").trim();
		return s.isEmpty() ? DEFAULT_START : s.toUpperCase(Locale.ROOT);
	}

	/** 새 게임 시작 인스턴스 (없으면 null). */
	public static String startEntry() {
		String s = table().getProperty("start.entry", "").trim();
		return s.isEmpty() ? null : s;
	}

	/** 레벨 이름 → 읽을 WAD 이름 (확장자 없이 대문자). 바꿈이 없으면 레벨 이름 그대로. */
	public static String wadName(String level) {
		String lv = level.toUpperCase(Locale.ROOT);
		Properties t = table();
		String r = t.getProperty("redirect." + lv);
		if (r == null) {
			for (String k : t.stringPropertyNames()) { // 키 대소문자 무관
				if (k.regionMatches(true, 0, "redirect.", 0, 9) && k.substring(9).trim().equalsIgnoreCase(lv)) {
					r = t.getProperty(k);
				}
			}
		}
		if (r == null || r.isBlank()) {
			return lv;
		}
		r = r.trim().toUpperCase(Locale.ROOT);
		return r.endsWith(".WAD") ? r.substring(0, r.length() - 4) : r;
	}

	/**
	 * 레벨 이름 → WAD 경로. 엔진의 레벨 WAD 접근 (새 게임·Warp·LoadWad/LoadCheck·이웃 슬롯·보스·--game) 이 이 함수를 지난다.
	 * 바꾼 WAD 가 없으면 원래 경로.
	 */
	public static Path wad(Path rom, String level) {
		String lv = level.toUpperCase(Locale.ROOT);
		String w = wadName(lv);
		if (w.equals(lv)) {
			return Content.file(rom, lv + ".WAD");
		}
		Path p = Content.file(rom, w + ".WAD");
		if (!Files.isRegularFile(p)) {
			if (WARNED.add(lv + ">" + w)) {
				System.err.println("[stages] " + lv + " → " + w + ".WAD 없음 — 원래 WAD 사용");
			}
			return Content.file(rom, lv + ".WAD");
		}
		LOGICAL.put(key(p), lv + ".WAD");
		return p;
	}

	/** 레벨 WAD 가 있는지 (바꿈 반영). */
	public static boolean exists(Path rom, String level) {
		return Files.isRegularFile(wad(rom, level));
	}

	/** WadFile 이름: 바꿔 읽은 WAD 면 논리 이름 (예: ATHN02A.WAD), 아니면 파일 이름. */
	public static String logicalName(Path wadFile) {
		String n = LOGICAL.isEmpty() ? null : LOGICAL.get(key(wadFile));
		return n != null ? n : wadFile.getFileName().toString();
	}

	private static Path key(Path p) {
		return p.toAbsolutePath().normalize();
	}
}
