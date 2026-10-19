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

/** 보스 체력 미터: 스크립트 1055(HealthMeterOn) → 슬롯 → 레벨 FLP_Athn01E 의 HydraMeter{n} (37 문서). */
class BossMeterTest {

	@Test
	void babyHydraMetersFollowHp() throws Exception {
		BossRig r = HydraFightTest.spawned();
		r.hydraZone(2);
		r.run(10f); // baby*Ent-HealthMeterOn: Delay 4
		Map<Integer, EnemyFighter> m = r.bridge.meterTargets();
		assertEquals(java.util.Set.of(1, 2), m.keySet(), "아기 히드라 2마리 → 슬롯 1, 2");

		var dir = java.nio.file.Path.of(System.getProperty("gow.rom", "../gow-rom"));
		WadFile w = WadFile.load(dir.resolve("ATHN01E.WAD"));
		var t = w.tags().stream().filter(x -> x.name().equals("FLP_Athn01E") && x.hasData()).findFirst().orElseThrow();
		UiVariables v = new UiVariables();
		FlpPlayer p = new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(WadFile.load(dir.resolve("R_PERMK.WAD"))).orElseThrow());
		EnemyMeterBinding b = new EnemyMeterBinding();
		EnemyFighter f = m.get(1);
		f.hp = f.maxHp() * 0.5f;
		for (int i = 0; i < 40; i++) {
			b.update(v, m);
			p.tick();
		}
		assertEquals(9, p.frameOf("/HydraMeter1"), "Wait (표시 유지)");
		int expect = Math.round(0.5f * 99);
		assertEquals(1 + expect, p.frameOf("/HydraMeter1/HydraMeterMain"), "프레임 = 2 + 값 (1 기반)");
		f.hp = 0;
		for (int i = 0; i < 20; i++) {
			b.update(v, m);
			p.tick();
		}
		assertTrue(p.frameOf("/HydraMeter1") >= 10 || p.frameOf("/HydraMeter1") == 0, "사망 → Off: " + p.frameOf("/HydraMeter1"));
	}
}
