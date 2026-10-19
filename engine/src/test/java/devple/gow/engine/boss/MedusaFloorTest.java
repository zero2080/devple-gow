package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.level.HostLevel;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 메두사 여왕 방 바닥 (37 문서 §8): ATHN02D 의 충돌 시트는 마법 획득 단상뿐이고 방 바닥은 숙주 레벨 ATHN02C 의 시트에 있다.
 * ATHN02C 스크립트가 LoadWad/LoadCheck("Athn02D") 로 이 레벨을 다른 슬롯에 올림 → {@link HostLevel}.
 */
class MedusaFloorTest {

	@Test
	void arenaFloorComesFromHostLevel() throws Exception {
		BossRig r = MedusaQueenFightTest.spawned();
		EnemyFighter b = r.fight.boss();
		ScriptEntity start = r.world.find("Start");
		assertNotNull(start);
		float[] s = start.position();
		assertEquals("ATHN02C", HostLevel.find(BossRig.ROM, "ATHN02D", s[0], s[1], s[2]), "숙주 = 이 레벨을 LoadWad 하는 ATHN02C");

		LevelCollision own = new LevelCollision(new LevelData(r.levelWad).sheets);
		LevelCollision host = new LevelCollision(new LevelData(WadFile.load(BossRig.ROM.resolve("ATHN02C.WAD"))).sheets);
		// 메두사 정면 / 둘레 / 시작 지점
		List<Vector3f> pts = new ArrayList<>();
		pts.add(new Vector3f(s[0], s[1], s[2]));
		for (float d : new float[]{40, 150}) {
			pts.add(new Vector3f(b.pos.x - (float) Math.sin(b.yaw) * d, b.pos.y, b.pos.z - (float) Math.cos(b.yaw) * d));
		}
		for (int k = 0; k < 8; k++) {
			double a = Math.PI * 2 * k / 8;
			pts.add(new Vector3f(b.pos.x + (float) Math.sin(a) * 100, b.pos.y, b.pos.z + (float) Math.cos(a) * 100));
		}
		int ownMiss = 0;
		for (Vector3f p : pts) {
			if (Float.isNaN(own.groundHeight(p.x, p.y + 32, p.z, 600))) {
				ownMiss++;
			}
			float g = host.groundHeight(p.x, p.y + 32, p.z, 600);
			assertFalse(Float.isNaN(g), "숙주 바닥 " + p);
			assertEquals(960f, g, 40f, "방 바닥 높이 " + p);
			// 영웅이 위에서 떨어져 바닥에 선다 (GameScene 물리와 같은 중력·스냅)
			float y = p.y + 30, vy = 0;
			for (int i = 0; i < 120; i++) {
				float gg = host.groundHeight(p.x, y + 14, p.z, 600);
				if (!Float.isNaN(gg) && y - gg <= 2 && vy <= 0) {
					y = gg;
					vy = 0;
				} else {
					vy -= 9.82f * 16 / 60;
					y += vy / 60;
					if (!Float.isNaN(gg) && y < gg) {
						y = gg;
						vy = 0;
					}
				}
			}
			assertEquals(g, y, 0.5f, "착지 " + p);
		}
		assertTrue(ownMiss >= pts.size() - 2, "ATHN02D 자기 시트에는 방 바닥이 없음 (" + ownMiss + "/" + pts.size() + ")");
	}

	@Test
	void hostNeedsScriptLink() throws Exception {
		assertTrue(HostLevel.loads(WadFile.load(BossRig.ROM.resolve("ATHN02C.WAD")), "ATHN02D"), "ATHN02C LoadWad2D/LoadCheckD");
		assertFalse(HostLevel.loads(WadFile.load(BossRig.ROM.resolve("ATHN02B.WAD")), "ATHN02D"), "ATHN02B 는 02D 를 올리지 않음");
		assertNull(HostLevel.find(BossRig.ROM, "ATHN02D", 0, -100000, 0), "바닥 없는 점 → 숙주 없음");
		assertTrue(HostLevel.addOn(WadFile.load(BossRig.ROM.resolve("ATHN02D.WAD"))), "ATHN02D: 레벨 적재 호출 없음 = 덧붙는 레벨");
		assertFalse(HostLevel.addOn(WadFile.load(BossRig.ROM.resolve("ATHN02C.WAD"))), "ATHN02C: LoadWad/LoadCheck 있음");
	}
}
