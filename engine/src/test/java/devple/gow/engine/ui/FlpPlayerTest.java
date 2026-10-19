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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FLP 런타임 (GL 없이 VM/타임라인만). 원본 데이터가 있을 때만 실행. */
class FlpPlayerTest {

	/** gow.rom 이 없으면 상위 폴더에서 gow-rom 을 찾는다 (워크트리 실행용). */
	static Path rom() {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).toAbsolutePath();
		for (Path d = p.getParent(); !Files.exists(p.resolve("R_PERMK.WAD")) && d != null; d = d.getParent()) {
			p = d.resolve("gow-rom");
		}
		return p;
	}

	private static FlpPlayer load(String wad, String flp, UiVariables vars) throws Exception {
		Path dir = rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve(wad)), "gow-rom 없음");
		WadFile w = WadFile.load(dir.resolve(wad));
		WadTag t = w.tags().stream().filter(x -> x.name().equals(flp) && x.hasData()).findFirst().orElseThrow();
		MessageTable msgs = MessageTable.fromWad(WadFile.load(dir.resolve("R_PERMK.WAD"))).orElseThrow();
		return new FlpPlayer(new FlpFile(t.data()), vars, msgs);
	}

	private static void ticks(FlpPlayer p, int n) {
		for (int i = 0; i < n; i++) {
			p.pad(0);
			p.tick();
		}
	}

	@Test
	void shellMenuCursor() throws Exception {
		UiVariables v = new UiVariables();
		List<String> sounds = new ArrayList<>();
		v.addListener((n, val) -> {
			if (n.startsWith("PS2_Sound")) {
				sounds.add(val);
			}
		});
		FlpPlayer p = load("R_SHELLK.WAD", "FLP_ShellK", v);
		ticks(p, 30);
		assertEquals(7, p.frameOf("/"), "MainWait 에서 정지");
		assertTrue(p.frameOf("/NewGame") > 0, "새 게임 항목 표시");
		assertEquals("새 게임", p.getVariable("PS2_4517"));
		// 방향키 ↓ → OpeningDown → 다음 항목(불러오기)
		p.pad(UiPad.DOWN);
		p.tick();
		ticks(p, 30);
		assertTrue(p.frameOf("/Load") > 0, "불러오기 항목으로 이동");
		// ○ 선택 → MainOff → ShellMenu_State 에 따라 PS2_Done
		p.pad(UiPad.CIRCLE);
		p.tick();
		ticks(p, 30);
		assertTrue(sounds.contains("SND_MM_SELECT"), "선택 효과음 요청: " + sounds);
	}

	@Test
	void hudHealthMeter() throws Exception {
		UiVariables v = new UiVariables();
		FlpPlayer p = load("R_PERMK.WAD", "FLP_HUDK", v);
		ticks(p, 2);
		v.set("PS2_HealthMeter_Value", 100).set("PS2_HealthMeter_Level", 1).set("PS2_MagicMeter_Value", 90).set("PS2_MagicMeter_Level", 2);
		v.event("PS2_MeterBar_Event", 1).event("PS2_HealthMeter_Event", 1).event("PS2_MagicMeter_Event", 2);
		ticks(p, 1);
		v.event("PS2_HealthMeter_Event", 1).event("PS2_MagicMeter_Event", 2);
		ticks(p, 10);
		assertEquals(50, p.frameOf("/MainMeter/HealthMeter"), "프레임 = 1 + 100/2 (1 기반)");
		assertEquals(45, p.frameOf("/MainMeter/MagicMeter"));
		assertEquals("-1", v.get("PS2_HealthMeter_Event"), "이벤트 소비");
		assertEquals(4, p.frameOf("/MainMeter"), "AnimateOn 후 정지");
	}
}
