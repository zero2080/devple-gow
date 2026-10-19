package devple.gow.engine.ui;

import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 35 문서 §6: FLP_HUDK 루트의 미니게임 아이콘 심볼(0x29DA40 표)을 이름으로 붙이고 뗀다. */
class MinigameIconTest {

	@Test
	void attachesIconSymbols() throws Exception {
		Path dir = FlpPlayerTest.rom();
		WadFile w = WadFile.load(dir.resolve("R_PERMK.WAD"));
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_HUDK") && x.hasData()).findFirst().orElseThrow();
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), new UiVariables(), MessageTable.fromWad(w).orElseThrow());
		for (int i = 0; i < 30; i++) {
			p.tick();
		}
		List<String> missing = new ArrayList<>();
		for (String n : MinigameIcon.CLIPS) {
			if (!p.attach(n)) {
				missing.add(n);
			}
			p.detach(n);
		}
		System.out.println("FLP_HUDK 에 없는 아이콘 심볼: " + missing);
		assertTrue(missing.size() <= 1, missing.toString());
		MinigameIcon icon = new MinigameIcon();
		icon.show(p, 2);
		assertTrue(p.attached("CircleButtonGreek"));
		for (int i = 0; i < 120; i++) {
			p.tick(); // 루트 타임라인이 지나가도 남는다
		}
		assertTrue(p.attached("CircleButtonGreek"));
		icon.show(p, MinigameIcon.BUTTON_PRESS);
		assertFalse(p.attached("CircleButtonGreek"));
		assertTrue(p.attached("XButtonPress"));
		icon.show(p, MinigameIcon.NONE);
		assertEquals(MinigameIcon.NONE, icon.current());
		assertFalse(p.attached("XButtonPress"));
	}
}
