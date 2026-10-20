package devple.gow.engine.combat;

import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 생물 밀어내기 규칙 (32 문서 §생물끼리 밀어내기 — 밀기 끔 비트·관절 상자·선분 기준점·날기·보스).
 */
class BodyPushRulesTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		return rom;
	}

	/** 고정 몸 (월드 단위). */
	record Fixed(Vector3f feet, float bodyRadius, float bodyHeight, float bodyBase, boolean airborne) implements BodyPush.Body {
		public boolean solid() {
			return true;
		}
	}

	private static float flat(Vector3f a, Vector3f b) {
		return (float) Math.hypot(a.x - b.x, a.z - b.z);
	}

	/**
	 * 선분 기준점 (0x1E2E44 / 0x1E29B4): 자기 = 위치 − 바닥, 상대 = 위치 + 바닥 — 크라토스 (r 9.6, 키 35.2, 바닥 6.4) 와 사이클롭스 (r 48, 키 96).
	 * 크라토스가 밀릴 때 수평 경계 √(57.6² − 35.2²) = 45.6, 사이클롭스가 밀릴 때 √(57.6² − 9.6²) = 56.8.
	 */
	@Test
	void selfSegmentIsBaseLowerOtherBaseHigher() {
		Fixed cyclops = new Fixed(new Vector3f(), 48, 96, 6.4f, false);
		BodyPush.Body hero = BodyPush.hero(new Vector3f(30, 0, 0), () -> false, null);
		Vector3f p = new Vector3f(30, 0, 0);
		for (int i = 0; i < 200; i++) {
			BodyPush.push(hero, p, List.of(cyclops), null);
			p.y = 0; // 지상 이동은 밀린 뒤 바닥에 붙는다
		}
		assertEquals(45.6f, flat(p, cyclops.feet()), 0.2f, "크라토스 쪽 경계");
		Fixed heroOther = new Fixed(new Vector3f(), 9.6f, 35.2f, 6.4f, false);
		Vector3f q = new Vector3f(30, 0, 0);
		for (int i = 0; i < 200; i++) {
			BodyPush.push(cyclops, q, List.of(heroOther), null);
			q.y = 0;
		}
		assertEquals(56.8f, flat(q, heroOther.feet()), 0.2f, "사이클롭스 쪽 경계");
	}

	/** 질의 콜백은 0 (맞음·계속) 을 돌려 다음 상대도 본다 (0x11EA48: 0xC 만 끝냄) — 두 상대 사이에 끼면 둘 다에서 밀린다. */
	@Test
	void pushAccumulatesOverAllOthers() {
		Fixed a = new Fixed(new Vector3f(-15, 0, 0), 11.2f, 44.8f, 6.4f, false);
		Fixed b = new Fixed(new Vector3f(0, 0, 15), 11.2f, 44.8f, 6.4f, false);
		BodyPush.Body hero = BodyPush.hero(new Vector3f(), () -> false, null);
		Vector3f p = new Vector3f();
		assertTrue(BodyPush.push(hero, p, List.of(a, b), null));
		assertTrue(p.x > 0 && p.z < 0, "두 상대 모두에서 밀림 " + p);
	}

	/** 관절 상자 0x1E6DF0: 반지름은 Sold 값 아래로 안 줄고, 지상은 변위 반 + 6.4 (키/2 상한), 기준점 = 변위 반. 동기 기술은 변위 전체·이동 없음. */
	@Test
	void jointBoxGrowsAndShifts() {
		BodyPush.JointBox b = new BodyPush.JointBox();
		b.update(9.6f, 35.2f, 2, -4, 0, false, true);
		assertEquals(9.6f, b.radius, 1e-5f, "작은 변위: Sold 반지름");
		assertEquals(1f, b.offX, 1e-5f);
		assertEquals(-2f, b.offZ, 1e-5f);
		b.update(9.6f, 35.2f, 0, -16, 0, false, true);
		assertEquals(14.4f, b.radius, 1e-4f, "8 + 6.4");
		b.update(9.6f, 35.2f, 0, -60, 0, false, true);
		assertEquals(17.6f, b.radius, 1e-4f, "키/2 상한");
		b.update(9.6f, 35.2f, 0, -16, 0, true, false);
		assertEquals(17.6f, b.radius, 1e-4f, "동기: min(16 + 6.4, 17.6)");
		assertEquals(0f, b.offZ, 1e-6f, "동기: 기준 이동 없음");
		b.update(9.6f, 35.2f, 0, -16, (float) Math.PI / 2, false, true);
		assertEquals(-8f, b.offX, 1e-4f, "요 90°: 로컬 −z → 월드 −x");
		b.update(9.6f, 35.2f, 0, -16, 0, false, false);
		assertEquals(9.6f, b.radius, 1e-6f, "그 밖 (수영·붙잡기): Sold 상자");
	}

	/** Sold +0x1C 비트 1 (owner+0x2D8 비트 31) = 자기 이동에서 밀지 않음 — 상대에게는 여전히 단단함. */
	@Test
	void noPushBitSkipsOwnPushOnly() {
		List<BodyPush.Body> crowd = new ArrayList<>();
		EnemyNavTest.Mob a = new EnemyNavTest.Mob(0, new Vector3f(100, 0, 200), crowd), b = new EnemyNavTest.Mob(0, new Vector3f(104, 0, 200), crowd);
		a.nav.setBody(new AiData.Body(0.7f, 2.8f, 0.4f, 6, null), 1f); // 비트 1
		assertTrue(a.nav.noPush());
		a.tick(new Vector3f(), null);
		assertEquals(100f, a.pos.x, 1e-4f, "밀기 끔: 겹친 채");
		b.tick(new Vector3f(), null);
		assertTrue(b.pos.x > 104 + 10, "상대는 a 에게 밀림 (a 는 여전히 단단함) " + b.pos);
	}

	/** 데이터: 밀기 끔 비트·관절 이름 (Sold +0x1C / +0xA4). */
	@Test
	void soldPushFlagsAndBoxJoints() throws Exception {
		Path rom = rom();
		AiData cy = AiData.load(WadFile.load(rom.resolve("R_CY2.WAD")));
		assertFalse(cy.body(41).noPush(), "사이클롭스 4 = 밀기");
		assertNull(cy.body(41).joint(), "일반 적: 관절 없음 (루트)");
		assertEquals(48f, cy.body(41).half() * 16, 1e-4f);
		AiData perm = AiData.load(WadFile.load(rom.resolve("R_PERMK.WAD")));
		assertEquals("head", perm.body(0).joint(), "크라토스 +0xA4 = head");
		assertFalse(perm.body(0).noPush());
		Assumptions.assumeTrue(Files.exists(rom.resolve("ATHN01E.WAD")));
		AiData hy = AiData.load(WadFile.load(rom.resolve("ATHN01E.WAD")));
		assertTrue(hy.body(250).noPush(), "히드라 머리 250 (0x0E) = 밀기 끔");
		assertTrue(hy.body(253).noPush());
		assertEquals("BHA01JOCHEA1", hy.body(250).joint());
		AiData pg = AiData.load(WadFile.load(rom.resolve("PAND03G.WAD")));
		assertFalse(pg.body(200).noPush(), "BMI 의 Sold 는 4 — 밀기 끔은 Think 0x1C0A48 이 매 프레임");
		assertEquals(136f, pg.body(200).half() * 16, 1e-3f);
	}

	/** 날기 능력 (하피, AI1 Fly 0x8) = 시작 상태 0x40 → 공중: 지상 영웅은 하피에게 밀리지 않는다 (0x1E2B80). */
	@Test
	void flyingHarpyDoesNotPushGroundHero() throws Exception {
		Path rom = rom();
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HAR.WAD")));
		EnemyFactory.Kind k = EnemyFactory.load(rom.resolve("R_HAR.WAD"), WadFile.load(rom.resolve("R_PERMK.WAD")), -1);
		EnemyFighter har = k.spawn();
		assertNotEquals(0, har.nav().caps() & AiTemplate.CAP_FLY);
		assertTrue(har.airborne(), "날기 0x40");
		BodyPush.Body hero = BodyPush.hero(new Vector3f(5, 0, 0), () -> false, null);
		Vector3f p = new Vector3f(har.pos).add(5, 0, 0);
		assertFalse(BodyPush.push(hero, p, List.of(har), null), "지상 영웅은 공중 상대를 무시");
		// 하피 쪽 (공중) 은 단단한 영웅에게서 자기를 n × 깊이 전부 민다
		Vector3f hp = new Vector3f(har.pos);
		assertTrue(BodyPush.push(har, hp, List.of(new Fixed(new Vector3f(har.pos).add(5, 0, 0), 9.6f, 35.2f, 6.4f, false)), null));
		assertTrue(hp.x < har.pos.x, "하피가 밀려남");
	}
}
