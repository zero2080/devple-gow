package devple.gow.engine.ui;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.GameConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** font.properties 해석, 아틀라스 배치, stb_truetype 굽기 (49 문서 §7). GL 없이. */
class TtfFontOverrideTest {

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Content.install(null);
	}

	@Test
	void noFilePropertiesMeansNoOverride() {
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		assertNull(FontOverride.load());
	}

	@Test
	void ttfResolvedFromUpperLayerWithOptions() throws IOException {
		Path a = Files.createDirectories(tmp.resolve("a")), b = Files.createDirectories(tmp.resolve("b"));
		Files.createDirectories(a.resolve("fonts"));
		Files.createDirectories(b.resolve("fonts"));
		Files.writeString(a.resolve("fonts/F.ttf"), "a");
		Files.writeString(b.resolve("fonts/F.ttf"), "b");
		Files.writeString(a.resolve(FontOverride.FILE), "ttf=fonts/F.ttf\nscale=1.25\nyOffset=-0.1\nmode=ALL\n");
		Files.writeString(b.resolve(FontOverride.FILE), "bakeSize=9999\nindex=2\n");
		Content.install(Content.of(new GameConfig(new Properties()), List.of(a, b)));
		FontOverride o = FontOverride.load();
		assertNotNull(o);
		assertEquals(b.resolve("fonts/F.ttf"), o.ttf());
		assertEquals(1.25f, o.scale());
		assertEquals(-0.1f, o.yOffset(), 1e-6);
		assertTrue(o.all());
		assertEquals(48, o.bakeSize()); // 범위 밖 → 기본
		assertEquals(2, o.index());
	}

	@Test
	void missingTtfOrBadModeFallsBack() throws IOException {
		Path a = Files.createDirectories(tmp.resolve("a"));
		Files.writeString(a.resolve(FontOverride.FILE), "ttf=fonts/none.ttf\n");
		Content.install(Content.of(new GameConfig(new Properties()), List.of(a)));
		assertNull(FontOverride.load());
		Files.writeString(a.resolve("x.ttf"), "x");
		Files.writeString(a.resolve(FontOverride.FILE), "ttf=x.ttf\nmode=weird\nscale=abc\n");
		FontOverride o = FontOverride.load();
		assertFalse(o.all());
		assertEquals(1f, o.scale());
	}

	@Test
	void packerFillsShelvesWithoutOverlap() {
		GlyphPacker p = new GlyphPacker(64, 64, 1);
		Set<Long> used = new HashSet<>();
		int placed = 0;
		int[] at;
		while ((at = p.place(10, 12)) != null) {
			assertTrue(at[0] >= 1 && at[0] + 10 <= 63 && at[1] >= 1 && at[1] + 12 <= 63, "범위");
			for (int y = at[1]; y < at[1] + 12; y++) {
				for (int x = at[0]; x < at[0] + 10; x++) {
					assertTrue(used.add((long) y << 32 | x), "겹침");
				}
			}
			placed++;
		}
		assertEquals(5 * 4, placed); // (64−1)/11 = 5 열, (64−1)/13 = 4 줄
		assertNull(new GlyphPacker(64, 64, 1).place(63, 5));
	}

	@Test
	void bakesKoreanGlyphFromSystemFont() throws IOException {
		Path f = Path.of("/System/Library/Fonts/AppleSDGothicNeo.ttc");
		assumeTrue(Files.isRegularFile(f));
		try (TtfGlyphs g = new TtfGlyphs(f, 0, 32)) {
			assertTrue(g.has('가'));
			TtfGlyphs.Glyph ga = g.bake('가');
			assertTrue(ga.w() > 8 && ga.h() > 8 && ga.yoff() < 0 && ga.advance() > 16);
			assertEquals(ga.w() * ga.h(), ga.alpha().length);
			TtfGlyphs.Glyph sp = g.bake(' ');
			assertEquals(0, sp.w());
			assertTrue(sp.advance() > 0);
			assertNull(g.bake(0x10FFFD));
		}
	}
}
