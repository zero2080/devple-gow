package devple.gow.engine.content;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.GameConfig;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 콘텐츠 계층: rom 파일 교체·속성 합치기·WAD 태그 교체·지역판 감지 (49 문서 §3–§5). */
class ContentLayersTest {

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Content.install(null);
	}

	private Path layer(String name) throws IOException {
		return Files.createDirectories(tmp.resolve(name));
	}

	@Test
	void upperLayerWinsForRomFilesAndProperties() throws IOException {
		Path rom = layer("rom"), a = layer("a"), b = layer("b");
		Files.writeString(rom.resolve("X.WAD"), "base");
		Files.writeString(rom.resolve("Y.WAD"), "base");
		Files.createDirectories(a.resolve("rom"));
		Files.writeString(a.resolve("rom/X.WAD"), "a");
		Files.createDirectories(b.resolve("rom"));
		Files.writeString(b.resolve("rom/X.WAD"), "b");
		Files.writeString(a.resolve("keys.properties"), "CROSS=K\nCIRCLE=L\n");
		Files.writeString(b.resolve("keys.properties"), "CROSS=SPACE\n");
		Content.install(Content.of(new GameConfig(new Properties()), List.of(a, b)));

		assertEquals("b", Files.readString(Content.file(rom, "X.WAD")));
		assertEquals(rom.resolve("Y.WAD"), Content.file(rom, "Y.WAD"));
		Properties k = Content.properties("keys.properties");
		assertEquals("SPACE", k.getProperty("CROSS"));
		assertEquals("L", k.getProperty("CIRCLE"));
	}

	@Test
	void noLayersMeansOriginalPaths() {
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		Path rom = tmp.resolve("rom");
		assertEquals(rom.resolve("R_PERMK.WAD"), Content.file(rom, "R_PERMK.WAD"));
		assertTrue(Content.properties("keys.properties").isEmpty());
	}

	@Test
	void wadTagPatchReplacesDataAndKeepsOtherTags() throws IOException {
		Path rom = layer("rom"), m = layer("m");
		byte[] wad = wad(tag(0x1E, "TXR_a", "aaaa".getBytes()), tag(0x18, "P", null), tag(0x1E, "TXR_b", "bbbbbbbb".getBytes()));
		Files.write(rom.resolve("T.WAD"), wad);
		Files.createDirectories(m.resolve("wad/T"));
		byte[] repl = "replacement-longer-than-16-bytes".getBytes(StandardCharsets.US_ASCII);
		Files.write(m.resolve("wad/T/TXR_a.bin"), repl);
		Content.install(Content.of(new GameConfig(new Properties()), List.of(m)));

		WadFile w = WadFile.load(rom.resolve("T.WAD"));
		assertEquals(3, w.tags().size());
		ByteBuffer a = w.find("TXR_a").orElseThrow().data();
		byte[] got = new byte[a.remaining()];
		a.get(got);
		assertArrayEquals(repl, got);
		ByteBuffer b = w.find("TXR_b").orElseThrow().data();
		assertEquals(8, b.remaining());
		assertEquals('b', b.get(0));
	}

	@Test
	void regionSuffixDetectedFromPermWad() throws IOException {
		Path us = layer("us");
		Files.writeString(us.resolve("R_PERM.WAD"), "");
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		assertEquals("", Region.of(us).suffix());
		assertEquals("FLP_HUD", Region.of(us).flpHud());
		Path kr = layer("kr");
		Files.writeString(kr.resolve("R_PERMK.WAD"), "");
		Files.writeString(kr.resolve("R_PERME.WAD"), "");
		assertEquals("R_SHELLK.WAD", Region.of(kr).shell());
		Properties p = new Properties();
		p.setProperty("region", "E");
		Content.install(Content.of(new GameConfig(p), List.of()));
		assertEquals("R_PERME.WAD", Region.of(kr).perm());
	}

	private static byte[] tag(int type, String name, byte[] data) {
		ByteBuffer h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		h.putShort((short) type).putShort((short) 0).putInt(data == null ? 7 : data.length);
		byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
		h.put(n);
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.writeBytes(h.array());
		if (data != null) {
			o.writeBytes(data);
			o.writeBytes(new byte[((data.length + 15) & ~15) - data.length]);
		}
		return o.toByteArray();
	}

	private static byte[] wad(byte[]... tags) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		for (byte[] t : tags) {
			o.writeBytes(t);
		}
		return o.toByteArray();
	}
}
