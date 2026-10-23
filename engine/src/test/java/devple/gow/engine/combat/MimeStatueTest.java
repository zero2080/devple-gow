package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 말하는 석상 (ATHN02A 부두 아테나 aoXSA, AI1 307, SpawnEnemy shipGroup-topDeck-AthenaMime): 몸 CDV 가 없어 원작에서는 맞지도 잡히지도 않는다.
 * R2 (1024) → AthenaCam + 1019 0/1 (A0/A1 AOXSA1A02MI03/04) + 메시지 218/322 (○ 를 기다림) → 1040 → quitMime 1019 −1.
 */
class MimeStatueTest {

	private static EnemyFactory.Kind load(String wad, int ai1, String sk) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, wad)), "gow-rom 없음");
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		return EnemyFactory.load(Content.file(rom, wad), perm, ai1, sk);
	}

	@Test
	void athenaStatueHasNoBodyAndCannotBeHit() throws Exception {
		EnemyFactory.Kind k = load("ATHN02A.WAD", 307, "aoxsa");
		EnemyFighter athena = k.spawn(307);
		assertNull(athena.combat().hitVolumes(), "ATHN02A 에 CDV_aoXSA 없음");
		assertTrue(athena.bodiless);
		assertTrue(athena.invulnerable(), "접촉 불가 → 영웅 공격·잡기 대상 아님");
		assertEquals(20f, athena.hp, "HP 20 은 그대로 (예전엔 정면 구 근사로 맞아 쓰러짐)");
		// 대사 동작: 스크립트 1019 0 / 1 (인자2 0 → 요청) 이 기술 0·1 로 간다
		assertTrue(athena.combat().request(0));
		assertTrue(athena.combat().request(1));
		athena.combat().stop(); // quitMime 1019 −1
		assertFalse(athena.combat().active());
	}

	@Test
	void bedTwinsKeepTheirBody() throws Exception {
		EnemyFighter twins = load("ATHN01F.WAD", 305, "aoxpt").spawn(305);
		assertNotNull(twins.combat().hitVolumes(), "CDV_aoXPT — ○ 잡기 (침대 미니게임) 대상");
		assertFalse(twins.bodiless);
		assertFalse(twins.invulnerable());
	}
}
