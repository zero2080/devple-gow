package devple.gow.tools;

import devple.gow.tools.iso.IsoEntry;
import devple.gow.tools.iso.IsoImage;
import devple.gow.tools.pak.GowDisc;
import devple.gow.tools.pak.TocEntry;
import devple.gow.tools.wad.ResourceType;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 분석 도구 진입점.
 * <pre>
 *   ./gradlew :tools:run --args="iso-info  P2_gow_kor.iso"
 *   ./gradlew :tools:run --args="iso-ls    P2_gow_kor.iso"
 *   ./gradlew :tools:run --args="iso-extract P2_gow_kor.iso work/iso"
 *   ./gradlew :tools:run --args="pak-ls      P2_gow_kor.iso"
 *   ./gradlew :tools:run --args="pak-extract P2_gow_kor.iso gow-rom [--all]"   (--all: 동영상 PSS/PSW 포함)
 *   ./gradlew :tools:run --args="wad-ls gow-rom/ATHN01A.WAD"
 *   ./gradlew :tools:run --args="text:export gow-rom/R_PERMK.WAD messages.properties"   (텍스트 모드 표, 49 문서 §8)
 *   ./gradlew :tools:run --args="disc:info  P2_gow_kor.iso"                 부트 실행 파일·지역·언어 접미사 (다른 판도, 49 문서 §15)
 *   ./gradlew :tools:run --args="disc:setup P2_gow_kor.iso gow-rom [--all]" 추출 + gow.properties rom=·iso=(·region=) 고침
 *   ./gradlew :tools:run --args="wad:get gow-rom/ATHN01A.WAD TXR_floor out.bin"            태그 데이터 꺼내기
 *   ./gradlew :tools:run --args="wad:put gow-rom/ATHN01A.WAD TXR_floor in.bin out.WAD"     태그 오프라인 교체
 *   ./gradlew :tools:run --args="mod:new mymod"                              mods/mymod/ 뼈대
 * </pre>
 * "패키지:명령" 형식은 {@code devple.gow.tools.<패키지>.Cli#main(String[])} 로 위임한다.
 * 예) {@code texture:export gow-rom/R_HERO0.WAD out} → {@code texture.Cli.main(["export", ...])}
 */
public final class ToolsMain {

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: <iso-info|iso-ls|iso-extract|pak-ls|pak-extract|wad-ls|disc:info|disc:setup|wad:get|wad:put|mod:new> <file> ...");
			System.exit(1);
		}
		int colon = args[0].indexOf(':');
		if (colon > 0) {
			String[] sub = args.clone();
			sub[0] = args[0].substring(colon + 1);
			Class.forName("devple.gow.tools." + args[0].substring(0, colon) + ".Cli")
					.getMethod("main", String[].class).invoke(null, (Object) sub);
			return;
		}
		String[] rest = Arrays.copyOfRange(args, 1, args.length);
		switch (args[0]) {
			case "iso-info" -> isoInfo(rest);
			case "iso-ls" -> isoLs(rest);
			case "iso-extract" -> isoExtract(rest);
			case "pak-ls" -> pakLs(rest);
			case "pak-extract" -> pakExtract(rest);
			case "wad-ls" -> wadLs(rest);
			default -> throw new IllegalArgumentException("unknown command: " + args[0]);
		}
	}

	private static void isoInfo(String[] a) throws Exception {
		try (IsoImage iso = new IsoImage(Path.of(a[0]))) {
			for (IsoImage.Volume v : iso.volumes()) {
				System.out.printf("layer %d: base=%d sectors=%d (%.2f GB) system=%s volume=%s%n",
						v.layer(), v.baseSector(), v.sizeSectors(),
						v.sizeSectors() * (double) IsoImage.SECTOR / 1e9, v.systemId(), v.volumeId());
			}
			devple.gow.tools.iso.SystemCnf cnf = devple.gow.tools.iso.SystemCnf.read(iso);
			if (cnf != null) {
				System.out.printf("boot: %s (%s, %s, VER %s, %s)%n", cnf.executable(), cnf.serial(), cnf.region(), cnf.version(), cnf.videoMode());
			}
		}
	}

	private static void isoLs(String[] a) throws Exception {
		try (IsoImage iso = new IsoImage(Path.of(a[0]))) {
			for (IsoEntry e : iso.listAll()) {
				System.out.printf("L%d %10d %12d %s%s%n", e.layer(), e.lba(), e.size(), e.path(), e.dir() ? "/" : "");
			}
		}
	}

	private static void isoExtract(String[] a) throws Exception {
		Path out = Path.of(a.length > 1 ? a[1] : "gow-rom");
		try (IsoImage iso = new IsoImage(Path.of(a[0]))) {
			for (IsoEntry e : iso.listAll()) {
				Path dst = out.resolve("L" + e.layer() + e.path());
				if (e.dir()) {
					Files.createDirectories(dst);
					continue;
				}
				Files.createDirectories(dst.getParent());
				if (Files.exists(dst) && Files.size(dst) == e.size()) {
					continue;
				}
				System.out.printf("extract %s (%d bytes)%n", dst, e.size());
				try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dst))) {
					iso.copyTo(e, os);
				}
			}
		}
	}

	private static void pakLs(String[] a) throws Exception {
		try (GowDisc disc = new GowDisc(Path.of(a[0]))) {
			for (TocEntry e : disc.entries()) {
				System.out.printf("P%d %8d %11d %s%n", e.part() + 1, e.sector(), e.size(), e.name());
			}
		}
	}

	private static void pakExtract(String[] a) throws Exception {
		Path out = Path.of(a.length > 1 ? a[1] : "gow-rom");
		boolean all = Arrays.asList(a).contains("--all");
		try (GowDisc disc = new GowDisc(Path.of(a[0]))) {
			devple.gow.tools.disc.Cli.extract(disc, out, all, false);
		}
	}

	private static void wadLs(String[] a) throws Exception {
		WadFile wad = WadFile.load(Path.of(a[0]));
		for (WadTag t : wad.tags()) {
			String extra = "";
			if (t.tag() == WadTagType.RESOURCE && t.hasData()) {
				extra = ResourceType.describe(t.magic());
			} else if (t.tag() == WadTagType.PARAM) {
				extra = "= " + t.size();
			}
			System.out.printf("%08x %s%-10s fl=%-2d size=%-8x %-24s %s%n", t.offset(), "  ".repeat(Math.max(0, t.depth())),
					WadTagType.name(t.tag()), t.flags(), t.size(), t.name(), extra);
		}
	}
}
