package devple.gow.engine.game;

import devple.gow.tools.content.Content;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §50.1-3: 영웅 물속 거품 (원작 0x1FE518 누적 · 0x1FF3E0 콜백). 영상 4 4428–4545 (PAND04E 물길) 처럼 헤엄치는 크라토스 손발에서 흰 거품이 오른다 —
 * 예전 엔진은 이 영웅 부품이 없어 물속에서 거품이 전혀 없었다.
 */
class HeroBubblesTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void accumulatorFollowsTheOriginalRate() {
		// 0x1FE96C: 프레임마다 0.5 + |v| · 0.5 / 16 → 멈춰 있으면 초당 30, 초당 32 로 헤엄치면 초당 90
		assertEquals(0.5f, HeroBubbles.perFrame(0), 1e-6f);
		assertEquals(1.5f, HeroBubbles.perFrame(32), 1e-6f);
		HeroBubbles b = new HeroBubbles();
		int total = 0;
		for (int i = 0; i < 60; i++) {
			total += b.take(true, false, 0, 1 / 60f);
		}
		assertEquals(30, total, 1);
		total = 0;
		for (int i = 0; i < 60; i++) {
			total += b.take(true, false, 32, 1 / 60f);
		}
		assertEquals(90, total, 1);
		// 물 밖이면 누적을 버린다
		assertEquals(0, b.take(false, false, 64, 1 / 60f));
		// 잠수 돌진이 켜진 프레임 +100 (0x1FE9B4) — 한 틱에 최대 SLOTS, 남은 것은 다음 틱들
		assertEquals(HeroBubbles.SLOTS, b.take(true, true, 0, 1 / 60f));
		assertEquals(HeroBubbles.SLOTS, b.take(true, true, 0, 1 / 60f), "계속 돌진 중이면 다시 더하지 않지만 남은 누적");
	}

	@Test
	void spawnPointsCrowdTowardTheHandsAndFeet() {
		// 위치 = E + (R − E) · r² (0x1FF73C – 0x1FF758): 끝점 E (손목 · 발) 쪽에 몰린다
		Vector3f e = new Vector3f(0, 0, 0), r = new Vector3f(0, 40, 0);
		assertEquals(10f, HeroBubbles.spawnPoint(e, r, 0.5f).y, 1e-4f);
		assertEquals(40f, HeroBubbles.spawnPoint(e, r, 1f).y, 1e-4f);
		assertEquals(0f, HeroBubbles.spawnPoint(e, r, 0f).y, 1e-4f);
	}

	@Test
	void bubblePartShapeIsInThePermanentWad() throws Exception {
		Path p = Content.file(ROM, "R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		FxResources fx = new FxResources(WadFile.load(p));
		FxResources.Shape sh = fx.shape("BubpartShape", Integer.MAX_VALUE);
		assertNotNull(sh, "PTC_Bubpart");
		assertEquals(1f, sh.lifespan(), 1e-6f, "수명 1 초");
		assertEquals("MAT_bubble2TX", sh.material());
		assertNotNull(sh.vu(), "VU 식 (색 · 크기 · 알파)");
	}
}
