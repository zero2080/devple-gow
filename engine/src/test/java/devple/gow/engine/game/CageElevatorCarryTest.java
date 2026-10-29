package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §50.1-2: PAND04C 우리 승강기 gocavecrank (Push "Pand04CElevs" Type 3 기중기형 크랭크) — 손잡이가 우리 바닥 위에 있어, 크랭크를 돌리는 영웅이 선
 * 바닥이 바로 그 우리 몸이다. 원작 영웅 갱신 0x1EBD78 은 지면 GO 실림 0x1E6248 을 붙잡기 0x1C4258 보다 먼저 늘 하므로, 진행 시간으로 우리가 오르면
 * 손잡이를 잡은 영웅도 같이 오른다 (영상 4 4010–4020: 우리 안에서 크랭크를 돌려 절벽 위 세이브 (−288, 752, −1408) 로). 예전 엔진은 붙잡은 동안
 * 실림을 건너뛰어 우리만 올라가고 크라토스는 구덩이 바닥 (219) 에서 빈 손으로 돌았다.
 */
class CageElevatorCarryTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void crankingTheCageLiftsTheHeroStandingOnIt() throws Exception {
		Path p = Content.file(ROM, "PAND04C.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(p));
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		Interactables items = Interactables.of(lv, tw);
		Interactables.Item crank = items.byOwner("gocavecrank");
		assertEquals(Interactables.Kind.LEVER, crank.kind);
		assertEquals(3, crank.pushType, "기중기형 크랭크");
		assertTrue(crank.length > 1f, "오브젝트 애니 = 승강 " + crank.length);

		Platforms ps = Platforms.of(lv, Set.of(), SceneNav.bodyOwners(items));
		NavWorld w = new NavWorld(new LevelCollision(lv.sheets));
		w.setPlatforms(ps);
		ps.poseAt("gocavecrank", null, 0f);
		ps.update(0); // prev = 지금 (정지 자세)
		Vector3f hero = new Vector3f(-217.6f, 219.1f, -1365f); // 손잡이 (−217.6, 236, −1374.9) 앞에 붙은 자리
		NavWorld.Floor f = w.floor(hero.x, hero.y + 4, hero.z, 8);
		assertNotNull(f);
		assertNotNull(f.platform(), "영웅 발밑 = 우리 바닥 (발판 몸)");
		assertEquals("gocavecrank", f.platform().body().owner);

		assertTrue(ps.poseAt("gocavecrank", null, crank.length));
		Platforms.carry(hero, f.platform());
		assertEquals(747f, hero.y, 6f, "우리 꼭대기 = 세이브 턱 높이 " + hero);
		NavWorld.Floor top = w.floor(hero.x, hero.y + 4, hero.z, 8);
		assertNotNull(top);
		assertEquals(hero.y, top.y(), 2f, "실린 뒤에도 우리 바닥 위");
	}
}
