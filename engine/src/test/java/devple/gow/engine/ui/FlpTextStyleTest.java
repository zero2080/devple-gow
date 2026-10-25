package devple.gow.engine.ui;

import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 글자 서체 태그 [*n] (43 §33): 원작 텍스트 배치 0x1494A8 이 '[' 다음 '*' 면 그 다음 글자 − '0' 을 서체 번호로 (']' 면 0), 그리기 0x148388 이
 * 1 = 고정 색 (0xDC, 0x6E, 0x00) · 기울임 0.2, 2 = (0x00, 0xDC, 0xDC) · 기울임 0.2 로 그린다. 책 126 (영상 3610–3634) 과 아르테미스의 검 카드 267
 * (영상 3998) 의 "[*1]" 줄이 붉은 기울임. 예전 엔진은 태그를 버렸다.
 */
class FlpTextStyleTest {

	private static Elf exe() throws Exception {
		Path p = FlpPlayerTest.rom().toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static R5900.Insn at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc);
	}

	@Test
	void styleTagsComeFromTheLayoutCodeAndTheConstantTable() throws Exception {
		Elf e = exe();
		// 0x149894 li v1, '*' → 0x1498AC bne (다음 글자 ≠ '*' 면 버튼 태그), 0x1498B8 li a0, ']' → 0x1498D4 s3 = 0, 0x1498D8 s3 = 글자 − '0'
		assertEquals(0x2a, at(e, 0x149894).imm(), at(e, 0x149894).text());
		assertEquals("bne", at(e, 0x1498ac).mnem());
		assertEquals(0x5d, at(e, 0x1498b8).imm(), at(e, 0x1498b8).text());
		assertTrue(at(e, 0x1498d4).text().contains("s3, zero"), at(e, 0x1498d4).text());
		assertEquals("addiu", at(e, 0x1498d8).mnem());
		assertEquals(-0x30, at(e, 0x1498d8).imm(), at(e, 0x1498d8).text());
		// 서체 1·2 의 색 바이트와 기울임 (0x29CCDC.. — 0x148388 이 읽는다)
		int[] rgb1 = {e.u8(0x29ccdc), e.u8(0x29ccdd), e.u8(0x29ccde)};
		int[] rgb2 = {e.u8(0x29cce4), e.u8(0x29cce5), e.u8(0x29cce6)};
		assertArrayEquals(FlpRenderer.STYLE_RGB[1], rgb1);
		assertArrayEquals(FlpRenderer.STYLE_RGB[2], rgb2);
		assertEquals(FlpRenderer.ITALIC, e.f32(0x29cce0), 1e-6f);
		assertEquals(FlpRenderer.ITALIC, e.f32(0x29cce8), 1e-6f);
	}

	@Test
	void tokenizerKeepsTheStyleNumber() {
		List<Object> t = FlpRenderer.tokenize("[*1]검[*]x[L1Button]");
		assertEquals(new FlpRenderer.Style(1), t.get(0));
		assertEquals('검', t.get(1));
		assertEquals(new FlpRenderer.Style(0), t.get(2));
		assertEquals('x', t.get(3));
		assertEquals("L1Button", t.get(4));
		assertEquals(5, t.size());
	}

	@Test
	void styleColourIsTheFixedBytesTimesTheLabelTransform() {
		float[] label = {0.1f, 0.2f, 0.3f, 0.5f};
		float[] col = {1f, 1f, 1f, 0.8f};
		assertSame(label, FlpRenderer.styleColor(0, label, col));
		float[] c1 = FlpRenderer.styleColor(1, label, col);
		assertArrayEquals(new float[]{221 / 256f, 111 / 256f, 1 / 256f, 0.8f}, c1, 1e-6f);
		float[] c2 = FlpRenderer.styleColor(2, label, new float[]{0.5f, 1f, 1f, 1f});
		assertArrayEquals(new float[]{0.5f / 256f, 221 / 256f, 221 / 256f, 1f}, c2, 1e-6f);
	}

	/** 출시 데이터: 책 126 의 본문 줄과 카드 267 의 첫 줄이 서체 1. */
	@Test
	void bookAndArtemisCardUseStyleOne() throws Exception {
		Path w = FlpPlayerTest.rom().resolve("R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(w), "R_PERMK 없음");
		MessageTable m = MessageTable.fromWad(WadFile.load(w)).orElseThrow();
		String book = m.get(126), card = m.get(267);
		assertNotNull(book);
		assertNotNull(card);
		assertTrue(card.startsWith("[*1]"), card);
		assertTrue(book.contains("[*1]"), book);
		assertEquals(new FlpRenderer.Style(1), FlpRenderer.tokenize(card.split("\n")[0]).getFirst());
	}
}
