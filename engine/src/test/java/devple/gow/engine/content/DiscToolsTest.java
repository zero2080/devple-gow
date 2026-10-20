package devple.gow.engine.content;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.GameConfig;
import devple.gow.tools.content.Region;
import devple.gow.tools.iso.IsoImage;
import devple.gow.tools.iso.SystemCnf;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 도구 · 다른 디스크 (49 문서 §15): SYSTEM.CNF, 언어 접미사·설정 합치기, wad:get / wad:put. */
class DiscToolsTest {

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Content.install(null);
	}

	@Test
	void systemCnfBootExecutableAndRegion() throws Exception {
		SystemCnf us = SystemCnf.parse("BOOT2 = cdrom0:\\SCUS_973.99;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n");
		assertEquals("SCUS_973.99", us.executable());
		assertEquals("SCUS-97399", us.serial());
		assertTrue(us.region().startsWith("북미"));
		assertEquals("NTSC", us.videoMode());
		SystemCnf pal = SystemCnf.parse("BOOT2=cdrom0:\\SCES_532.86;1\nVMODE=PAL\n");
		assertEquals("SCES_532.86", pal.executable());
		assertTrue(pal.region().startsWith("유럽"));
		assertNull(SystemCnf.parse("").executable());

		// 실제 한글판 디스크 (읽기만 — 루트 디렉터리와 SYSTEM.CNF 한 섹터)
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Path iso = Content.isoPath(rom);
		Assumptions.assumeTrue(iso != null && Files.isRegularFile(iso), "ISO 없음");
		try (IsoImage img = new IsoImage(iso)) {
			SystemCnf kr = SystemCnf.read(img);
			assertEquals("SCKA_300.02", kr.executable());
			assertTrue(kr.region().startsWith("한국"));
		}
	}

	@Test
	void languageSuffixesAndSetupSettingsMerge() throws Exception {
		List<String> sfx = Region.suffixes(List.of("R_PERM.WAD", "R_PERMF.WAD", "R_PERMK.WAD", "R_PERMK.WAD", "R_SHELLK.WAD", "X.WAD"));
		assertEquals(List.of("", "F", "K"), sfx);
		Map<String, String> s = devple.gow.tools.disc.Cli.settings(Path.of("a.iso"), Path.of("rom2"), sfx, new Properties());
		assertEquals(Map.of("rom", "rom2", "iso", "a.iso", "region", "K"), s);
		assertEquals("-", devple.gow.tools.disc.Cli.settings(Path.of("a.iso"), Path.of("r"), List.of("", "F"), new Properties()).get("region"));
		Properties has = new Properties();
		has.setProperty("region", "F");
		assertFalse(devple.gow.tools.disc.Cli.settings(Path.of("a.iso"), Path.of("r"), sfx, has).containsKey("region"), "이미 고른 region 은 둔다");
		assertFalse(devple.gow.tools.disc.Cli.settings(Path.of("a.iso"), Path.of("r"), List.of("E"), new Properties()).containsKey("region"),
				"하나면 자동 감지");

		// gow.properties 합치기: 주석·다른 키 유지, 같은 키는 전부 고침, 없는 키는 끝에
		Path f = tmp.resolve("gow.properties");
		Files.writeString(f, "# 내 설정\nmods=a,b\nrom = old\nrom=old2\n");
		assertEquals(List.of("rom", "iso"), GameConfig.update(f, s.entrySet().stream().filter(e -> !e.getKey().equals("region"))
				.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> x, java.util.LinkedHashMap::new))));
		assertEquals("# 내 설정\nmods=a,b\nrom=rom2\nrom=rom2\niso=a.iso\n", Files.readString(f));
		assertEquals(List.of(), GameConfig.update(f, Map.of("rom", "rom2")), "같으면 쓰지 않음");
		assertEquals(List.of("a", "b"), GameConfig.load(f).mods()); // rom 은 테스트의 -Dgow.rom 이 이기므로 mods 로 확인
	}

	@Test
	void wadGetPutRoundTripMatchesModPatch() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		Path src = Content.file(rom, "R_RDEAD0.WAD");
		Assumptions.assumeTrue(Files.exists(src), "gow-rom 없음");
		WadFile w = devple.gow.tools.wad.Cli.raw(src);
		// 한 번만 나오는 데이터 리소스 태그 하나
		WadTag t = w.tags().stream().filter(x -> x.tag() == WadTagType.RESOURCE && x.hasData()
				&& devple.gow.tools.wad.Cli.dataTags(w, x.name()).size() == 1).findFirst().orElseThrow();
		byte[] orig = devple.gow.tools.wad.Cli.get(w, t.name());
		assertEquals(t.size(), orig.length);

		// 같은 데이터로 put = 원본과 바이트 동일
		byte[] same = bytes(devple.gow.tools.wad.Cli.put(w, t.name(), orig));
		assertArrayEquals(Files.readAllBytes(src), same);

		// 길이가 다른 데이터: get 이 그대로 돌려주고, 다른 태그는 보존
		byte[] repl = Arrays.copyOf(orig, orig.length + 37);
		repl[repl.length - 1] = 0x5A;
		byte[] patched = bytes(devple.gow.tools.wad.Cli.put(w, t.name(), repl));
		WadFile p = new WadFile(w.name(), ByteBuffer.wrap(patched));
		assertArrayEquals(repl, devple.gow.tools.wad.Cli.get(p, t.name()));
		assertEquals(w.tags().size(), p.tags().size());
		for (int i = 0; i < w.tags().size(); i++) {
			assertEquals(w.tags().get(i).name(), p.tags().get(i).name());
			if (!w.tags().get(i).name().equals(t.name())) {
				assertEquals(w.tags().get(i).data(), p.tags().get(i).data());
			}
		}

		// 모드 계층의 wad/<WAD>/<태그>.bin 으로 적재한 결과 = 오프라인 put
		Path mod = Files.createDirectories(tmp.resolve("m/wad/R_RDEAD0"));
		Files.write(mod.resolve(t.name() + ".bin"), repl);
		Content.install(Content.of(new GameConfig(new Properties()), List.of(tmp.resolve("m"))));
		assertArrayEquals(patched, bytes(WadFile.load(src).bytes()));
	}

	@Test
	void modNewSkeletonKeepsExistingFiles() throws Exception {
		Path d = devple.gow.tools.mod.Cli.create(tmp.resolve("mods"), "mymod");
		assertTrue(Files.isDirectory(d.resolve("wad")) && Files.isDirectory(d.resolve("rom")) && Files.isDirectory(d.resolve("plugins")));
		assertTrue(Files.readString(d.resolve("README.md")).contains("mods=mymod"));
		Files.writeString(d.resolve("ai.properties"), "min.health=1\n");
		devple.gow.tools.mod.Cli.create(tmp.resolve("mods"), "mymod");
		assertEquals("min.health=1\n", Files.readString(d.resolve("ai.properties")));
		assertThrows(IllegalArgumentException.class, () -> devple.gow.tools.mod.Cli.create(tmp.resolve("mods"), "../x"));
	}

	private static byte[] bytes(ByteBuffer b) {
		byte[] o = new byte[b.remaining()];
		b.duplicate().get(o);
		return o;
	}
}
