package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
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
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
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

	/** CDV 구를 가진 고정 몸 (구는 월드 x, y, z, r). */
	record Balls(Vector3f feet, float bodyRadius, float bodyHeight, float bodyBase, List<float[]> pushSpheres) implements BodyPush.Body {
		public boolean airborne() {
			return false;
		}

		public boolean solid() {
			return true;
		}
	}

	/**
	 * 밀기 후보 = 질의 구 (기준점 + 위 × 키/2, 반지름 키/2 + R) 와 겹치는 상대 CDV 구 (0x11E8D0 → 0x175648) [확실]:
	 * 캡슐이 겹쳐도 구가 질의 구에 닿지 않으면 콜백이 불리지 않고, 닿는 구 수만큼 콜백이 불린다.
	 */
	@Test
	void pushCandidatesAreOverlappingCdvSpheres() {
		// 영웅 (r 9.6, 키 35.2, 바닥 6.4) 발 (0,0,0) → 기준점 y −6.4, 질의 구 중심 (0, 11.2, 0), 반지름 27.2
		BodyPush.Body hero = BodyPush.hero(new Vector3f(), () -> false, null);
		// 상대 캡슐 r 20 은 겹치지만 CDV 구는 머리 위 높이 (y 100) 뿐 → 후보 아님
		Balls tallHead = new Balls(new Vector3f(15, 0, 0), 20, 120, 0, List.<float[]>of(new float[]{15, 100, 0, 10}));
		assertEquals(0, BodyPush.candidates(0, 11.2f, 0, 27.2f, tallHead));
		Vector3f p = new Vector3f();
		assertFalse(BodyPush.push(hero, p, List.of(tallHead), null), "CDV 구가 질의 구에 안 닿음 → 밀지 않음");
		assertEquals(0f, p.x, 0f);
		// 허리 높이 구 둘 → 후보 2 (콜백 두 번), 첫 번에 경계까지 밀려 결과는 한 번과 같다
		Balls waist = new Balls(new Vector3f(15, 0, 0), 20, 120, 0, List.of(new float[]{15, 20, 0, 10}, new float[]{15, 30, 0, 10}));
		assertEquals(2, BodyPush.candidates(0, 11.2f, 0, 27.2f, waist));
		assertTrue(BodyPush.push(hero, p, List.of(waist), null));
		Vector3f once = new Vector3f();
		BodyPush.push(hero, once, List.of(new Balls(new Vector3f(15, 0, 0), 20, 120, 0, List.<float[]>of(new float[]{15, 20, 0, 10}))), null);
		assertEquals(once.x, p.x, 1e-3f, "두 번째 콜백은 이미 경계 (R + r = 29.6) 라 그대로");
		assertTrue(p.x < -14, "밀림 " + p);
		// 구 정보 없는 몸 (시험 몸) 은 늘 후보
		assertEquals(1, BodyPush.candidates(0, 0, 0, 1, new Fixed(new Vector3f(1000, 0, 0), 1, 1, 0, false)));
	}

	/** 해골 (R_SKS 020): 밀기 후보 구 = 자기 CDV 구 (첫 포즈 뒤), 관절 상자 없음 (Sold 상자 r 11.2 × 스케일). */
	@Test
	void skeletonCandidatesAreItsCdvSpheres() throws Exception {
		Path rom = rom();
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "R_SKS.WAD"), WadFile.load(Content.file(rom, Region.of(rom).perm())), -1);
		EnemyFighter f = k.spawn(20);
		f.holdAi = true;
		f.update(1 / 60f);
		List<float[]> sp = f.pushSpheres();
		assertNotNull(sp);
		assertFalse(sp.isEmpty());
		assertEquals(f.nav().bodyRadius(), f.bodyRadius(), 0f, "적은 Sold 상자");
		// 몸 옆 영웅 질의 구 (반지름 27.2) 는 구에 닿고, 멀리 있으면 후보가 아님
		assertTrue(BodyPush.candidates(f.pos.x + 15, f.pos.y + 11.2f, f.pos.z, 27.2f, f) > 0);
		assertEquals(0, BodyPush.candidates(f.pos.x + 500, f.pos.y + 11.2f, f.pos.z, 27.2f, f));
	}

	/** 서 있는 생물 (지상·속도 0·변위 0) 은 자기를 밀지 않는다 (0x1EBB38–0x1EBBF4 → 0x1EA5F0 대신 0x1E9470) — 움직이면 민다 [확실]. */
	@Test
	void standingCreatureDoesNotPushItself() {
		List<BodyPush.Body> crowd = new ArrayList<>();
		EnemyNavTest.Mob a = new EnemyNavTest.Mob(0, new Vector3f(100, 0, 200), crowd), b = new EnemyNavTest.Mob(0, new Vector3f(400, 0, 200), crowd);
		for (int i = 0; i < 3; i++) { // 스폰 직후 공중 → 착지
			a.tick(new Vector3f(), null);
			b.tick(new Vector3f(), null);
		}
		assertEquals(EnemyNav.Mode.GROUND, b.nav.mode());
		b.pos.set(a.pos).add(4, 0, 0);
		for (int i = 0; i < 10; i++) {
			a.tick(new Vector3f(), null);
			b.tick(new Vector3f(), null);
		}
		assertEquals(4f, b.pos.x - a.pos.x, 1e-4f, "둘 다 서 있으면 겹친 채");
		b.nav.rootMoved = true; // 기술 루트 모션 변위가 있는 틱
		b.tick(new Vector3f(), null);
		assertTrue(b.pos.x - a.pos.x > 20, "움직이는 b 는 a 밖으로 자기를 민다 " + b.pos);
		b.nav.rootMoved = false;
		b.pos.set(a.pos).add(4, 0, 0);
		b.tick(new Vector3f(0, 0, 1), null); // 스틱 → 속도 ≠ 0
		assertTrue(b.pos.x - a.pos.x > 20, "걸으면 민다 " + b.pos);
	}

	/**
	 * 관절 상자 0x1E6DF0 의 조건 비트 [확실]: 동기 → 변위 전체 / 지상 (붙잡기 0x4000·착지 직후 0x800000 아님) → 관절 /
	 * 공중은 기술 중이고 다이빙 0x100000·물 0x300·착지 직후 아닐 때만 관절, 아니면 5 번까지 이전 상자 유지 (0x29F0CC) 뒤 Sold / 물 0x380 은 유지.
	 */
	@Test
	void jointBoxConditionBits() {
		BodyPush.JointBox b = new BodyPush.JointBox();
		float r = 9.6f, h = 35.2f;
		// 인자: hasMove, sync, ground, air, attached, water, landed, diving
		assertEquals(1, b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(false, false, true, false, false, false, false, false)));
		assertEquals(14.4f, b.radius, 1e-4f, "지상: 관절 (기술 없어도)");
		assertEquals(0, b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(false, false, true, false, true, false, false, false)));
		assertEquals(9.6f, b.radius, 1e-6f, "붙잡기 0x4000: Sold");
		assertEquals(0, b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(false, false, true, false, false, false, true, false)), "착지 직후: Sold");
		assertEquals(2, b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(true, true, true, false, true, false, true, false)), "동기는 비트 무관");
		assertEquals(17.6f, b.radius, 1e-4f);
		// 공중 + 기술 → 관절
		assertEquals(1, b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(true, false, false, true, false, false, false, false)));
		assertEquals(14.4f, b.radius, 1e-4f);
		// 공중 + 기술 없음 → 5 번 유지 뒤 Sold
		BodyPush.JointBox.State airIdle = new BodyPush.JointBox.State(false, false, false, true, false, false, false, false);
		for (int i = 0; i < 5; i++) {
			assertEquals(3, b.step(r, h, 0, -16, 0, airIdle), "유지 " + i);
			assertEquals(14.4f, b.radius, 1e-4f);
		}
		assertEquals(0, b.step(r, h, 0, -16, 0, airIdle));
		assertEquals(9.6f, b.radius, 1e-6f, "6 번째: Sold");
		// 다이빙 중 공중 기술 → 관절 아님 (카운터 5 그대로라 바로 Sold)
		assertEquals(0, b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(true, false, false, true, false, false, false, true)));
		// 지상으로 돌아오면 카운터 0 → 다시 공중 5 번 유지
		b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(false, false, true, false, false, false, false, false));
		assertEquals(3, b.step(r, h, 0, 0, 0, airIdle));
		assertEquals(14.4f, b.radius, 1e-4f, "공중 첫 프레임은 지상 관절 상자 유지");
		// 물: 그대로 (줄이지 않음)
		assertEquals(3, b.step(r, h, 0, 0, 0, new BodyPush.JointBox.State(false, false, false, false, false, true, false, false)));
		assertEquals(14.4f, b.radius, 1e-4f);
		// 벽·턱·사다리·줄 (지상도 공중도 아님) → Sold
		assertEquals(0, b.step(r, h, 0, -16, 0, new BodyPush.JointBox.State(true, false, false, false, false, false, false, false)));
		assertEquals(9.6f, b.radius, 1e-6f);
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
		AiData cy = AiData.load(WadFile.load(Content.file(rom, "R_CY2.WAD")));
		assertFalse(cy.body(41).noPush(), "사이클롭스 4 = 밀기");
		assertNull(cy.body(41).joint(), "일반 적: 관절 없음 (루트)");
		assertEquals(48f, cy.body(41).half() * 16, 1e-4f);
		AiData perm = AiData.load(WadFile.load(Content.file(rom, Region.of(rom).perm())));
		assertEquals("head", perm.body(0).joint(), "크라토스 +0xA4 = head");
		assertFalse(perm.body(0).noPush());
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "ATHN01E.WAD")));
		AiData hy = AiData.load(WadFile.load(Content.file(rom, "ATHN01E.WAD")));
		assertTrue(hy.body(250).noPush(), "히드라 머리 250 (0x0E) = 밀기 끔");
		assertTrue(hy.body(253).noPush());
		assertEquals("BHA01JOCHEA1", hy.body(250).joint());
		AiData pg = AiData.load(WadFile.load(Content.file(rom, "PAND03G.WAD")));
		assertFalse(pg.body(200).noPush(), "BMI 의 Sold 는 4 — 밀기 끔은 Think 0x1C0A48 이 매 프레임");
		assertEquals(136f, pg.body(200).half() * 16, 1e-3f);
	}

	/** 날기 능력 (하피, AI1 Fly 0x8) = 시작 상태 0x40 → 공중: 지상 영웅은 하피에게 밀리지 않는다 (0x1E2B80). */
	@Test
	void flyingHarpyDoesNotPushGroundHero() throws Exception {
		Path rom = rom();
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HAR.WAD")));
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "R_HAR.WAD"), WadFile.load(Content.file(rom, Region.of(rom).perm())), -1);
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
