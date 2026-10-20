package devple.gow.engine.content;

import devple.gow.engine.game.GameFlow;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.GameConfig;
import devple.gow.tools.content.Stages;
import devple.gow.tools.content.WadPatches;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.video.MovieNames;
import devple.gow.tools.video.MovieSource;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 텍스트 덮어쓰기·스테이지 바꿈·동영상 바꿈·ESC 태그 교체 (49 문서 §8–§10). */
class ModTextStagesMoviesTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Content.install(null);
	}

	private void install(Path... layers) {
		Content.install(Content.of(new GameConfig(new Properties()), List.of(layers)));
	}

	private Path layer(String name) throws IOException {
		return Files.createDirectories(tmp.resolve(name));
	}

	private static void write(Path p, String s) throws IOException {
		Files.createDirectories(p.getParent());
		Files.writeString(p, s, StandardCharsets.UTF_8);
	}

	// ---- §8 텍스트 ----

	private static WadFile msgWad(String text) {
		byte[] d = (text + "\0").getBytes(StandardCharsets.UTF_8);
		ByteBuffer h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		h.putShort((short) MessageTable.RAW_FILE_TAG).putShort((short) 0).putInt(d.length).put("msgsK.txt".getBytes(StandardCharsets.ISO_8859_1));
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.writeBytes(h.array());
		o.writeBytes(d);
		o.writeBytes(new byte[((d.length + 15) & ~15) - d.length]);
		return new WadFile("R_PERMK.WAD", ByteBuffer.wrap(o.toByteArray()));
	}

	@Test
	void messageOverridesMergeOverWadTable() throws IOException {
		WadFile wad = msgWad("*4051*\n옵션\n*4052*\n[CircleButton] 뒤로\n*4053*\n원래\n");
		install();
		assertEquals("옵션", MessageTable.fromWad(wad).orElseThrow().get(4051));

		Path a = layer("a"), b = layer("b");
		write(a.resolve(MessageTable.OVERRIDE_FILE), "4051=OPTIONS\n4053=from a\n");
		write(b.resolve(MessageTable.OVERRIDE_FILE), "# 주석\n4053=첫 줄\\n둘째 줄 [CrossButton]\n90001=새 메시지\n");
		install(a, b);
		MessageTable t = MessageTable.fromWad(wad).orElseThrow();
		assertEquals("OPTIONS", t.get(4051));
		assertEquals("[CircleButton] 뒤로", t.get(4052));
		assertEquals("첫 줄\n둘째 줄 [CrossButton]", t.get(4053));
		assertEquals("새 메시지", t.get(90001));
		assertEquals("새 메시지", t.forVariable("PS2_90001"));
		assertEquals("옵션", MessageTable.fromWadRaw(wad).orElseThrow().get(4051), "원본 표는 그대로");

		// 통째 교체 (원본 *ID* 형식) 위에 properties
		write(a.resolve(MessageTable.REPLACE_FILE), "*4051*\n교체\n*7*\n일곱\n*8*\n여덟\n");
		install(a, b);
		t = MessageTable.fromWad(wad).orElseThrow();
		assertEquals("OPTIONS", t.get(4051), "properties 가 통째 교체보다 위");
		assertEquals("여덟", t.get(8));
		assertEquals("일곱", t.get(7));
		assertNull(t.get(4052));
		assertEquals("새 메시지", t.get(90001));
	}

	@Test
	void exportRoundTripsThroughProperties() throws IOException {
		Path perm = ROM.resolve("R_PERMK.WAD");
		MessageTable src = Files.exists(perm) ? MessageTable.fromWadRaw(WadFile.load(perm)).orElseThrow()
				: MessageTable.fromWadRaw(msgWad("*1*\n a\\b\n----\n[*1] 개\n")).orElseThrow();
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		devple.gow.tools.text.Cli.write(src, new PrintStream(bo, true, StandardCharsets.UTF_8));
		Properties p = new Properties();
		p.load(new StringReader(bo.toString(StandardCharsets.UTF_8)));
		assertEquals(src.all().size(), p.size());
		for (Map.Entry<Integer, String> e : src.all().entrySet()) {
			assertEquals(e.getValue(), p.getProperty(String.valueOf(e.getKey())), "ID " + e.getKey());
		}
	}

	// ---- §9 스테이지 ----

	@Test
	void stagesDefaultIsOriginal() {
		install();
		Path rom = tmp.resolve("rom");
		assertEquals("ATHN01A", Stages.start());
		assertEquals(GameFlow.START_LEVEL, GameFlow.startLevel());
		assertNull(Stages.startEntry());
		assertEquals("ATHN02A", Stages.wadName("Athn02a"));
		assertEquals(rom.resolve("ATHN02A.WAD"), Stages.wad(rom, "ATHN02A"));
	}

	@Test
	void stagesRedirectLoadsOtherWadUnderLogicalName() throws IOException {
		Path rom = layer("rom"), m = layer("m");
		Files.write(rom.resolve("ATHN02A.WAD"), new byte[0]);
		Files.write(rom.resolve("ATHN03A.WAD"), new byte[0]);
		Files.createDirectories(m.resolve("rom"));
		Files.write(m.resolve("rom/MYLVL01.WAD"), new byte[0]);
		write(m.resolve(Stages.FILE), "start=mylvl01\nstart.entry=StartHere\nredirect.ATHN02A=MYLVL01\nredirect.athn03a=NOPE.WAD\n");
		install(m);

		assertEquals("MYLVL01", Stages.start());
		assertEquals("MYLVL01", GameFlow.startLevel());
		assertEquals("StartHere", Stages.startEntry());
		assertEquals("MYLVL01", Stages.wadName("athn02a"));
		Path p = Stages.wad(rom, "Athn02a");
		assertEquals(m.resolve("rom/MYLVL01.WAD"), p);
		assertEquals("ATHN02A.WAD", WadFile.load(p).name(), "논리 이름 유지");
		assertTrue(Stages.exists(rom, "ATHN02A"));
		// 대상이 없으면 원래 WAD
		assertEquals("NOPE", Stages.wadName("ATHN03A"));
		assertEquals(rom.resolve("ATHN03A.WAD"), Stages.wad(rom, "ATHN03A"));
		// 바꿈 없는 레벨은 그대로
		assertEquals(rom.resolve("ATHN01A.WAD"), Stages.wad(rom, "ATHN01A"));
		assertFalse(Stages.exists(rom, "ATHN01A"));
	}

	// ---- §10 이벤트 씬 ----

	@Test
	void movieRemapAndSkip() throws IOException {
		Path rom = layer("rom"), m = layer("m");
		Files.write(rom.resolve("SEMO01.PSS"), new byte[16]);
		Files.write(rom.resolve("A01MP05.PSS"), new byte[16]);
		install();
		assertEquals("SEMO01", MovieNames.remap("Semo01"));
		try (MovieSource s = MovieSource.open("Semo01", rom, null, false)) {
			assertEquals("SEMO01.PSS", s.name());
		}

		Files.createDirectories(m.resolve("rom"));
		Files.write(m.resolve("rom/MYINTRO.PSS"), new byte[8]);
		Files.write(m.resolve("rom/A01MP05.PSS"), new byte[4]);
		write(m.resolve(MovieNames.FILE), "semo01.pss=MyIntro\na01Mp03=skip\n");
		install(m);
		assertEquals("MYINTRO", MovieNames.remap("Semo01"));
		assertTrue(MovieNames.skipped("A01MP03"));
		assertFalse(MovieNames.skipped("Semo01"));
		try (MovieSource s = MovieSource.open("Semo01", rom, null, false)) {
			assertEquals("MYINTRO.PSS", s.name());
			assertEquals(8, s.size());
		}
		assertNull(MovieSource.open("a01Mp03", rom, null, false));
		try (MovieSource s = MovieSource.open("a01mp05", rom, null, false)) {
			assertEquals(4, s.size(), "모드 rom/ 의 같은 이름 PSS 가 원본을 가린다");
		}
	}

	@Test
	void patchedEscTagReachesScriptLoader() throws IOException {
		Path lvl = ROM.resolve("ARENA06.WAD");
		Assumptions.assumeTrue(Files.exists(lvl), "gow-rom 없음");
		install();
		WadFile orig = WadFile.load(lvl);
		List<String> names = new ArrayList<>();
		WadTag esc = null;
		for (WadTag t : orig.resources()) {
			if (t.magic() == EscScript.MAGIC) {
				names.add(t.name());
				if (esc == null && t.name().startsWith("ESC_")) {
					esc = t;
				}
			}
		}
		assertNotNull(esc);
		final String escName = esc.name();
		ByteBuffer d = esc.data();
		byte[] bytes = new byte[d.remaining()];
		d.get(bytes);
		int entities = new EscScript(esc.data()).entities.size();

		Path m = layer("m");
		Files.createDirectories(m.resolve("wad/ARENA06"));
		Files.write(m.resolve("wad/ARENA06/" + escName + ".bin"), bytes);
		install(m);
		assertTrue(WadPatches.patchesFor("ARENA06.WAD").containsKey(escName));
		WadFile patched = WadFile.load(lvl);
		WadTag p = patched.resources().stream().filter(t -> t.name().equals(escName) && t.magic() == EscScript.MAGIC).findFirst().orElseThrow();
		assertEquals(entities, new EscScript(p.data()).entities.size());
		assertEquals(names, patched.resources().stream().filter(t -> t.magic() == EscScript.MAGIC).map(WadTag::name).toList());
		assertNotNull(new LevelData(patched).layout, "레벨이 그대로 적재된다");
	}
}
