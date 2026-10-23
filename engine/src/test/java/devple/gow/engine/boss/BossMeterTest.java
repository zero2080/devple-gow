package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.ui.EnemyMeterBinding;
import devple.gow.engine.ui.FlpPlayer;
import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 보스 체력 미터: 스크립트 1055(HealthMeterOn) → 슬롯 = AI1 +0x9B + 1 → 레벨 FLP_Athn01E 의 HydraMeter{n} (37 문서 §4.3). */
class BossMeterTest {

	@Test
	void babyHydraMetersFollowHp() throws Exception {
		BossRig r = HydraFightTest.spawned();
		r.hydraZone(2);
		r.run(10f); // baby*Ent-HealthMeterOn: Delay 4
		Map<Integer, EnemyFighter> m = r.bridge.meterTargets();
		// 슬롯 = AI1 +0x9B + 1 (0x25B720 → 0x1D0FD4): 258 → 2, 253 → 3 (1 은 보스 몫). 예전엔 1055 순서대로 1, 2
		assertEquals(java.util.Set.of(2, 3), m.keySet(), "아기 히드라 258/253 → 슬롯 2, 3");

		var dir = java.nio.file.Path.of(System.getProperty("gow.rom", "../gow-rom"));
		WadFile w = WadFile.load(dir.resolve("ATHN01E.WAD"));
		var t = w.tags().stream().filter(x -> x.name().equals("FLP_Athn01E") && x.hasData()).findFirst().orElseThrow();
		UiVariables v = new UiVariables();
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(WadFile.load(dir.resolve("R_PERMK.WAD"))).orElseThrow());
		EnemyMeterBinding b = new EnemyMeterBinding();
		EnemyFighter f = m.get(2);
		f.hp = f.maxHp() * 0.5f;
		for (int i = 0; i < 40; i++) {
			b.update(v, m);
			p.tick();
		}
		assertEquals(9, p.frameOf("/HydraMeter2"), "Wait (표시 유지)");
		int expect = Math.round(0.5f * 99);
		assertEquals(1 + expect, p.frameOf("/HydraMeter2/HydraMeter"), "프레임 = 2 + 값 (1 기반)");
		f.hp = 0;
		for (int i = 0; i < 20; i++) {
			b.update(v, m);
			p.tick();
		}
		assertTrue(p.frameOf("/HydraMeter2") >= 10 || p.frameOf("/HydraMeter2") == 0, "사망 → Off: " + p.frameOf("/HydraMeter2"));
	}
}
