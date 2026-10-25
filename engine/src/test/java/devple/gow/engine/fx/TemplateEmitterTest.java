package devple.gow.engine.fx;

import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 컨텍스트 그룹 밖의 배치 = 템플릿 GO (43 §43): PAND03J 의 통나무 화살 golog · 명중 효과 gologshatter (TWK_Arrow_060 "GO Name" · "Hit Effect") 는
 * 원점에 놓여 있고 어느 CXT 그룹에도 없다 — 레벨 이미터로 만들면 이웃 PAND03A 홀 원점에서 불·검은 파편이 끝없이 나왔다.
 */
class TemplateEmitterTest {

	private static LevelData level(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	@Test
	void pand03jLogArrowTemplatesAreNotLevelEmitters() throws Exception {
		LevelData d = level("PAND03J");
		Set<String> templates = new TreeSet<>();
		boolean torch = false;
		for (LevelLayout.Placement pl : d.layout.placements()) {
			String n = pl.instance().name().toLowerCase();
			if (LevelEmitters.template(d, pl)) {
				templates.add(n);
				assertArrayEquals(new float[]{0, 0, 0}, pl.instance().pos(), 0f, n + " 원점");
			}
			if (n.equals("gotorchfire1")) {
				torch = !LevelEmitters.template(d, pl);
			}
		}
		assertEquals(Set.of("golog", "gologshatter"), templates);
		assertTrue(torch, "컨텍스트 안의 횃불은 그대로 레벨 이미터");
	}

	@Test
	void pand03aHasNoTemplates() throws Exception {
		LevelData d = level("PAND03A");
		for (LevelLayout.Placement pl : d.layout.placements()) {
			assertFalse(LevelEmitters.template(d, pl), pl.instance().name());
		}
	}
}
