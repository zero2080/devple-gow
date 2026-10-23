package devple.gow.engine.ui;

import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사망 메뉴 (FLP_HUDK DeadMenu): PS2_DeadMenu_Event 1 ("죽었습니다") → 3 (쉬움으로 다시 하기 포함) → ○ 확정 = PS2_Done 1 + Menu_Choice.
 * 3 의 첫 항목 RestartEasyMode → Menu_Choice 2 (GameScene 이 난이도 0 으로 체크포인트 재시작, 원작 0x29F484), 2 는 바로 0 (다시 하기).
 */
class DeadMenuTest {

	private static List<String> run(int event, int[] pads) throws Exception {
		Path dir = FlpPlayerTest.rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve("R_PERMK.WAD")), "gow-rom 없음");
		WadFile w = WadFile.load(dir.resolve("R_PERMK.WAD"));
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_HUDK") && x.hasData()).findFirst().orElseThrow();
		UiVariables v = new UiVariables();
		List<String> log = new ArrayList<>();
		v.addListener((n, val) -> log.add(n + "=" + val));
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(w).orElseThrow());
		for (int i = 0; i < 30; i++) {
			p.tick();
		}
		v.event("PS2_DeadMenu_Event", 1);
		for (int i = 0; i < 90; i++) {
			p.pad(0);
			p.tick();
		}
		v.event("PS2_DeadMenu_Event", event);
		for (int pad : pads) {
			for (int i = 0; i < 60; i++) {
				p.pad(0);
				p.tick();
			}
			p.pad(pad);
			p.tick();
		}
		for (int i = 0; i < 60; i++) {
			p.pad(0);
			p.tick();
		}
		return log;
	}

	@Test
	void easyOfferRestartsEasy() throws Exception {
		List<String> log = run(3, new int[]{UiPad.CIRCLE, UiPad.UP, UiPad.CIRCLE}); // ○ → 확인 화면 (PauseRestartEasyMode_State 2 = 아니오), ↑ = 예 (1), ○
		assertTrue(log.contains("PS2_Done=1"), log.toString());
		assertTrue(log.stream().anyMatch(s -> s.matches("PS2_Menu_Choice=2(\\.0)?")), "쉬움으로 = 2: " + log);
	}

	@Test
	void plainRestart() throws Exception {
		List<String> log = run(2, new int[]{UiPad.CIRCLE, UiPad.CIRCLE});
		assertTrue(log.contains("PS2_Done=1"), log.toString());
		assertTrue(log.stream().anyMatch(s -> s.matches("PS2_Menu_Choice=0(\\.0)?")), "다시 하기 = 0: " + log);
	}
}
