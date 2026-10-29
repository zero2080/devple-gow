package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.nav.Platforms;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §55: PAND05A 판도라의 상자 gopbox 는 GO 원점 (CDV 바닥) 이 탁자 gotablegrpreflect 충돌 윗면 (−1233.9) 보다 17.9 위에 놓여 있다 — 원작은
 * 처음 틱부터 중력으로 탁자에 내려앉고 (HeroNavigator.settleBoxes), 크랭크 (globeHandle 100 %) 뒤 tableGrpReflect-AnimTable 이 탁자를
 * 엘리베이터 바닥 높이 (−1279.9) 로 내리면 상자도 함께 내려온다 (영상 5 화 5394–5402: 상자가 엘리베이터 위 탁자에 있고 크라토스가 옆에서 잡는다).
 */
class PandoraBoxTableRideTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void theSettledBoxRidesTheLoweringTableDownToTheElevator() throws Exception {
		Path p = Content.file(ROM, "PAND05A.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(p));
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		Interactables items = Interactables.of(lv, tw);
		Interactables.Item box = items.byOwner("gopbox");
		assertEquals(Interactables.Kind.PUSH, box.kind);
		Platforms ps = Platforms.of(lv, Set.of("gotablegrpreflect"), SceneNav.bodyOwners(items));
		ps.update(0);
		NavWorld w = new NavWorld(new LevelCollision(lv.sheets));
		w.setPlatforms(ps);
		Set<String> self = w.family("gopbox");
		assertNull(w.floor(box.pos.x, box.pos.y + 16, box.pos.z, 32, self), "관절 0 ±16 에 받침 없음 (restDy 0 = GO 원점이 바닥)");
		NavWorld.Floor f = w.floor(box.pos.x, box.pos.y + 1, box.pos.z, 40, self);
		assertNotNull(f);
		assertEquals("gotablegrpreflect", f.platform().body().owner);
		assertEquals(17.9f, box.pos.y - f.y(), 0.3f, "상자 바닥 ↔ 탁자 충돌 윗면");

		float y0 = f.y();
		box.pos.y = y0; // 내려앉은 자리 (settleBoxes)
		SceneNav.rideBoxes(items, ps, null);
		assertEquals(y0, box.pos.y, 1e-3f, "탁자가 서 있으면 그대로");
		// 크랭크 손잡이 가짜 goglobehandlefake (윗면 −1259) 는 같은 때 globeHandleFake-AnimDown 으로 내려가고 6 초 뒤 VisOFF — 이 시험은 탁자만 돌리므로 끈다
		ps.setSolid("goglobehandlefake", false);
		ps.setSolid("goglobehandle", false); // globeHandle-VisOFF
		assertTrue(ps.play("gotablegrpreflect", "!0", 8, 1, 0), "tableGrpReflect-AnimTable (\"!0\", 모드 8)");
		for (int i = 0; i < 1200; i++) { // 20 초
			ps.update(1f / 60f);
			SceneNav.rideBoxes(items, ps, null);
		}
		assertEquals(-1279.9f, box.pos.y, 1f, "탁자와 함께 엘리베이터 높이로: " + box.pos);
		assertEquals(2832.1f, box.pos.x, 1f);
		assertEquals(-5120.1f, box.pos.z, 1f);
	}
}
