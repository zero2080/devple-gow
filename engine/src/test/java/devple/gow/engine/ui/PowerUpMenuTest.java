package devple.gow.engine.ui;

import devple.gow.engine.game.GameRules;
import devple.gow.engine.game.PlayerState;
import devple.gow.engine.game.Weapon;
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

import static org.junit.jupiter.api.Assertions.*;

/** 원작 파워업 메뉴 스크립트로 블레이드 업그레이드: 붉은 오브 2000 → 버튼 유지로 투자 → 레벨 1, 남은 오브 = 2000 − 1500. */
class PowerUpMenuTest {

	@Test
	void investInBlades() throws Exception {
		Path dir = FlpPlayerTest.rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve("R_PERMK.WAD")), "gow-rom 없음");
		WadFile perm = WadFile.load(dir.resolve("R_PERMK.WAD"));
		WadTag t = perm.tags().stream().filter(x -> x.name().equals("FLP_HUDK") && x.hasData()).findFirst().orElseThrow();
		UiVariables v = new UiVariables();
		List<String> log = new ArrayList<>();
		v.addListener((n, val) -> {
			if (!n.startsWith("PS2_Sound")) {
				log.add(n + "=" + val);
			}
		});
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(perm).orElseThrow());
		PlayerState ps = new PlayerState(GameRules.load(perm));
		ps.redOrbs = 2000;
		HudBinding hb = new HudBinding();
		for (int i = 0; i < 30; i++) {
			hb.update(ps, v);
			p.pad(0);
			p.tick();
		}
		HudBinding.write(ps, v);
		v.event("PS2_PSMMenu_Event", 1);
		for (int i = 0; i < 60; i++) {
			p.pad(0);
			p.tick();
		}
		// 투자: ○ 유지 (빠른 투자 PowerUp_QuickDrain 은 다른 버튼일 수 있어 충분히 길게)
		for (int i = 0; i < 1200 && v.number("PS2_Blades_Level", 0) < 1; i++) {
			p.pad(UiPad.CIRCLE);
			p.tick();
		}
		for (int i = 0; i < 30; i++) {
			p.pad(0);
			p.tick();
		}
		// 레벨업 화면: 연출이 끝나(EnableButtons=1) ○ 누름(mask 0x20, cond 5) → PowerUp_LevelUp_Check = 0
		for (int k = 0; k < 10 && !"0".equals(p.getVariable("_level0/:PowerUp_LevelUp_Check")); k++) {
			for (int i = 0; i < 90; i++) {
				p.pad(0);
				p.tick();
			}
			p.pad(UiPad.CIRCLE);
			p.tick();
		}
		// 메뉴 닫기: △ 뗌(mask 0x10, cond 9) → Animate_Off → PS2_PSMDone = 1
		for (int k = 0; k < 4 && !log.contains("PS2_PSMDone=1"); k++) {
			for (int i = 0; i < 30; i++) {
				p.pad(0);
				p.tick();
			}
			p.pad(UiPad.TRIANGLE);
			p.tick();
			for (int i = 0; i < 90; i++) {
				p.pad(0);
				p.tick();
			}
		}
		for (String n : new String[]{"_level0/:PSMPause_Check", "_level0/:EnableButtons", "_level0/:PS2_PowerUp_FirstTime",
				"_level0/:PowerUp_LevelUp_Check", "_level0/:Pause_CheckRelease", "Pause_CheckRelease", "_level0/:PausePowerUp_State"}) {
			System.out.println("  " + n + " = " + p.getVariable(n));
		}
		System.out.println("파워업 로그: " + log.stream().filter(s -> s.contains("Blades") || s.contains("PowerOrb_Count") || s.contains("PSM")).distinct().limit(20).toList());
		assertEquals(1, (int) v.number("PS2_Blades_Level", -1), "블레이드 레벨");
		assertEquals(500, (int) (v.number("PS2_PowerOrb_Count", -1) + v.number("PS2_BladesOrb_Count", 0)), "남은 오브 + 이월");
		assertTrue(log.contains("PS2_PSMDone=1"), "메뉴 종료 신호: " + log);
		assertEquals(0, ps.level.get(Weapon.BLADES), "진행도 반영은 게임 씬이 PSMDone 때 수행");
	}
}
