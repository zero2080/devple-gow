package devple.gow.engine.game;

import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.nav.Platforms;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §50.1: 밀기 상자 CDV 는 영웅 몸을 막는다 — 붙어 있는 그 상자만 뺀다. PAND04EE 테트리스 정육면체 gopushbox1 (Push 447 "Square", 반 너비 16,
 * CDV entities_groundRock ±16) 를 예전 엔진은 그냥 걸어 지나가 (영상 4880 은 면에 서서 잡고 문까지 민다) 잡을 수 없었다.
 */
class PushBoxBodyTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void unheldPushBoxBlocksTheHeroBodyAndTheHeldOneDoesNot() throws Exception {
		Path p = Content.file(ROM, "PAND04EE.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(p));
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		Interactables items = Interactables.of(lv, tw);
		Interactables.Item box = items.byOwner("gopushbox1");
		assertNotNull(box);
		assertEquals(Interactables.Kind.PUSH, box.kind);
		assertEquals(16f, box.boxHalf, 1e-3f);
		Interactables.Item crank = items.byOwner("gocrankhandle");
		assertEquals(Interactables.Kind.LEVER, crank.kind);

		Platforms ps = Platforms.of(lv, Set.of(), SceneNav.bodyOwners(items));
		Set<String> levers = SceneNav.leverOwners(items);
		assertTrue(levers.contains("gocrankhandle"));
		assertFalse(levers.contains("gopushbox1"), "밀기 상자는 늘 빼지 않는다");

		// 서쪽 면 (x = 861.3 − 16) 에서 6 안쪽으로 들어간 몸 구 (발 + 20, 반지름 12) — 붙지 않았으면 면 밖 12 로 밀려난다
		Collection<String> free = SceneNav.bodySkip(levers, null);
		Vector3f c = new Vector3f(box.pos.x - 16 - 6, box.pos.y + 20, box.pos.z);
		assertTrue(ps.collideSphere(c, 12, free, NavWorld.HERO_BODY_EXCLUDE));
		assertEquals(box.pos.x - 16 - 12, c.x, 0.6f, "면 + 몸 구 반지름 " + c);

		// 붙어 있으면 (영웅 = 면 + 9.6, 0x1C43C0) 그 상자는 몸을 밀지 않는다
		Collection<String> held = SceneNav.bodySkip(levers, box);
		Vector3f h = new Vector3f(box.pos.x - 16 - 9.6f, box.pos.y + 20, box.pos.z);
		assertFalse(ps.collideSphere(h, 12, held, NavWorld.HERO_BODY_EXCLUDE));
		assertEquals(box.pos.x - 16 - 9.6f, h.x, 1e-4f);
	}
}
