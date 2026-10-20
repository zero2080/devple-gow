package devple.gow.tools.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 지역판 (49 문서 §4). 언어별 자산은 이름 끝 접미사로 갈린다 — 한글판 SCKA_300.02 는 {@code K}
 * (R_PERMK.WAD, R_SHELLK.WAD, FLP_ShellK, FLP_HUDK). 다른 판은 rom 디렉터리의 R_PERM*.WAD 에서 접미사를 감지하고,
 * 여러 개면 설정 {@code region} 으로 고른다.
 */
public record Region(String suffix) {

	public static final Region KOREAN = new Region("K");
	private static final Pattern PERM = Pattern.compile("R_PERM([A-Z]*)\\.WAD");

	private static final java.util.Map<Path, Region> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/** 설정의 rom 디렉터리 기준. */
	public static Region current() {
		return of(Content.romDir());
	}

	/** 설정 region → rom 의 R_PERM&lt;접미사&gt;.WAD 감지 (K 우선, 그다음 이름순 첫째) → K. */
	public static Region of(Path rom) {
		return CACHE.computeIfAbsent(rom.toAbsolutePath().normalize(), Region::compute);
	}

	static void clearCache() {
		CACHE.clear();
	}

	private static Region compute(Path rom) {
		String s = Content.get().config().region();
		if (s != null) {
			return new Region(s.equals("-") ? "" : s);
		}
		List<String> found = detect(rom);
		if (found.isEmpty() || found.contains("K")) {
			return KOREAN;
		}
		return new Region(found.getFirst());
	}

	/** rom 디렉터리의 R_PERM 접미사 목록 (이름순). */
	public static List<String> detect(Path rom) {
		try (Stream<Path> s = Files.list(rom)) {
			return s.map(p -> PERM.matcher(p.getFileName().toString())).filter(Matcher::matches).map(m -> m.group(1)).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** 공용 자산 WAD (HUD·메시지·효과음). */
	public String perm() {
		return "R_PERM" + suffix + ".WAD";
	}

	/** 메인 메뉴 WAD. */
	public String shell() {
		return "R_SHELL" + suffix + ".WAD";
	}

	public String flpShell() {
		return "FLP_Shell" + suffix;
	}

	public String flpHud() {
		return "FLP_HUD" + suffix;
	}
}
