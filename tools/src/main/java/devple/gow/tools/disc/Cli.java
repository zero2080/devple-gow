package devple.gow.tools.disc;

import devple.gow.tools.content.GameConfig;
import devple.gow.tools.content.Region;
import devple.gow.tools.iso.IsoImage;
import devple.gow.tools.iso.SystemCnf;
import devple.gow.tools.pak.GowDisc;
import devple.gow.tools.pak.TocEntry;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 디스크 (판 무관) 명령 (49 문서 §15).
 * <pre>
 *   disc:info  P2_gow_kor.iso                         부트 실행 파일 (SYSTEM.CNF)·지역·목차·PAK·언어 접미사
 *   disc:setup P2_gow_kor.iso [gow-rom] [--all] [--dry-run] [--config gow.properties]
 *              추출 (pak-extract 와 같음, --all = 동영상 포함) + gow.properties 의 rom=·iso=
 *              (언어 접미사가 여럿이고 region 이 아직 없으면 region= 도) 를 고친다 — 다른 줄은 그대로
 * </pre>
 */
public final class Cli {

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "info" -> {
				try (GowDisc disc = new GowDisc(Path.of(args[1]))) {
					info(disc);
				}
			}
			case "setup" -> setup(args);
			default -> throw new IllegalArgumentException("disc:<info|setup> " + args[0]);
		}
	}

	/** 디스크 요약. @return 목차의 언어 접미사 (이름순) */
	public static List<String> info(GowDisc disc) {
		for (IsoImage.Volume v : disc.iso().volumes()) {
			System.out.printf("레이어 %d: %s / %s, %d 섹터%n", v.layer(), v.systemId(), v.volumeId(), v.sizeSectors());
		}
		SystemCnf cnf = disc.systemCnf();
		if (cnf != null) {
			System.out.printf("부트: %s (%s, %s, VER %s, %s)%n", cnf.executable(), cnf.serial(), cnf.region(), cnf.version(), cnf.videoMode());
		} else {
			System.out.println("부트: SYSTEM.CNF 없음");
		}
		System.out.printf("목차: %s, 파일 %d 개 (사본 포함 %d), PAK %s%n", disc.tocName(), disc.toc().unique().size(), disc.entries().size(),
				disc.parts());
		List<String> sfx = Region.suffixes(disc.toc().unique().keySet());
		List<String> shown = sfx.stream().map(s -> s.isEmpty() ? "(없음)" : s).toList();
		System.out.printf("언어 접미사 (R_PERM*.WAD): %s → %s%s%n", shown, Region.choose(sfx).perm(),
				sfx.size() > 1 ? " (여럿 — gow.properties region= 으로 고름)" : "");
		return sfx;
	}

	private static void setup(String[] args) throws IOException {
		List<String> pos = new ArrayList<>();
		Path config = Path.of(GameConfig.FILE);
		boolean all = false, dry = false;
		for (int i = 1; i < args.length; i++) {
			switch (args[i]) {
				case "--all" -> all = true;
				case "--dry-run" -> dry = true;
				case "--config" -> config = Path.of(args[++i]);
				default -> pos.add(args[i]);
			}
		}
		Path iso = Path.of(pos.getFirst());
		Path rom = Path.of(pos.size() > 1 ? pos.get(1) : "gow-rom");
		try (GowDisc disc = new GowDisc(iso)) {
			List<String> sfx = info(disc);
			int[] n = extract(disc, rom, all, dry);
			System.out.printf("%s: 추출 %d 개, 이미 있음 %d 개%s%n", rom, n[0], n[1], dry ? " (--dry-run: 쓰지 않음)" : "");
			Map<String, String> set = settings(iso, rom, sfx, readProps(config));
			if (dry) {
				System.out.println(config + " (--dry-run): " + set);
				return;
			}
			List<String> changed = GameConfig.update(config, set);
			System.out.println(config + ": " + (changed.isEmpty() ? "바뀐 것 없음" : "고침 " + changed));
		}
	}

	/** gow.properties 에 넣을 값: rom·iso, 언어 접미사가 여럿이고 region 이 아직 없으면 region (K 우선, 빈 접미사는 "-"). */
	public static Map<String, String> settings(Path iso, Path rom, List<String> suffixes, Properties existing) {
		Map<String, String> set = new LinkedHashMap<>();
		set.put("rom", rom.toString());
		set.put("iso", iso.toString());
		if (suffixes.size() > 1 && existing.getProperty("region", "").isBlank()) {
			String s = Region.choose(suffixes).suffix();
			set.put("region", s.isEmpty() ? "-" : s);
		}
		return set;
	}

	private static Properties readProps(Path f) throws IOException {
		Properties p = new Properties();
		if (Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				p.load(r);
			}
		}
		return p;
	}

	/**
	 * 목차의 파일을 out 에 (이름별 첫 사본, 크기가 같은 파일은 건너뜀). all 이 아니면 동영상 PSS/PSW 제외 — 엔진은 ISO 에서 바로 읽는다.
	 *
	 * @return {추출, 건너뜀}
	 */
	public static int[] extract(GowDisc disc, Path out, boolean all, boolean dry) throws IOException {
		if (!dry) {
			Files.createDirectories(out);
		}
		int[] n = new int[2];
		for (TocEntry e : disc.toc().unique().values()) {
			if (!all && (e.name().endsWith(".PSS") || e.name().endsWith(".PSW"))) {
				continue;
			}
			Path dst = out.resolve(e.name());
			if (Files.exists(dst) && Files.size(dst) == e.size()) {
				n[1]++;
				continue;
			}
			n[0]++;
			System.out.printf("extract %-12s %10d%n", e.name(), e.size());
			if (dry) {
				continue;
			}
			try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dst))) {
				disc.copyTo(e, os);
			}
		}
		return n;
	}
}
