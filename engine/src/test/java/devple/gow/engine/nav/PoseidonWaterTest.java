package devple.gow.engine.nav;

import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.SurfaceAttr;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static devple.gow.engine.nav.HeroNavigatorTest.Body;
import static devple.gow.engine.nav.HeroNavigatorTest.Pad;
import static devple.gow.engine.nav.HeroNavigatorTest.deepPool;
import static devple.gow.engine.nav.HeroNavigatorTest.floor;
import static devple.gow.engine.nav.HeroNavigatorTest.tick;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 포세이돈의 시험 물 구간 (QA vs PS2 영상 3화 3840–5760, 43 §42).
 * <ul>
 *   <li>잠수 · 돌진 능력 = 포세이돈의 삼지창 (아이템 9 → +0x2DC 0x200020)</li>
 *   <li>수면 질의는 머리 근처 (0x1E57D8) — 물 높이가 다른 웅덩이로 헤엄쳐 올라오면 그 수면</li>
 *   <li>스냅숏 되읽기는 물 상태를 잇는다 (0x1DC130)</li>
 *   <li>돌진 문: 공 CDV 가 몸을 막고, 18 m/s 넘는 돌진이 부순다 (0x1DE4D8)</li>
 * </ul>
 */
class PoseidonWaterTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static Elf exe() throws Exception {
		Path p = ROM.toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static String insn(Elf e, int pc) {
		R5900.Insn i = R5900.decode(e.u32(pc), pc);
		return i.mnem() + " " + i.ops();
	}

	/** AddItem 0x1D8CF0 의 점프 표 0x2B8190 [아이템 − 3]: 아이템 9 → 0x1D8FDC 가 +0x2DC |= 0x200020, 영웅 생성 0x1DC21C 는 보유 비트 0x100. */
	@Test
	void tridentGrantsDiveAndDash() throws Exception {
		Elf e = exe();
		assertEquals(0x1D8FDC, e.u32(0x2B8190 + 4 * (9 - 3)));
		assertTrue(insn(e, 0x1D8FE4).startsWith("lui") && insn(e, 0x1D8FE4).contains("0x20"), insn(e, 0x1D8FE4));
		assertTrue(insn(e, 0x1D8FF0).startsWith("ori") && insn(e, 0x1D8FF0).contains("0x20"), insn(e, 0x1D8FF0));
		assertTrue(insn(e, 0x1D9000).startsWith("sw") && insn(e, 0x1D9000).contains("0x2dc("), insn(e, 0x1D9000));
		assertTrue(insn(e, 0x1DC220).startsWith("andi") && insn(e, 0x1DC220).contains("0x100"), insn(e, 0x1DC220));
		assertEquals(0x100, devple.gow.engine.game.PlayerState.ITEM_BIT_TRIDENT);
	}

	/** 삼지창 없으면 □ 를 눌러도 잠수하지 않는다 (떠 있기 그대로), 있으면 잠수. R1 돌진도 능력이 있어야. */
	@Test
	void noDiveWithoutTrident() {
		NavWorld w = deepPool();
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		nav.diveAbility = false;
		Pad in = new Pad();
		tick(nav, b, w, in);
		for (int i = 0; i < 60; i++) {
			in.down.add(PadButton.SQUARE);
			tick(nav, b, w, in);
		}
		assertEquals(HeroNavigator.Water.TREAD, nav.water(), "삼지창 전: □ 잠수 없음");
		assertEquals(-HeroNavigator.SWIM_DEPTH, b.pos.y, 1e-3f);
		nav.diveAbility = true;
		for (int i = 0; i < 30; i++) {
			in.down.add(PadButton.SQUARE);
			tick(nav, b, w, in);
		}
		assertEquals(HeroNavigator.Water.DIVE, nav.water());
	}

	/**
	 * 수면 질의 0x1E57D8: 발 + 28.16 ± d 선분의 Water 면이 +0x230. 수면 0 인 웅덩이 A 로 들어가 잠수한 뒤 수면 −200 인 웅덩이 B 아래로 옮겨 위로 올라오면
	 * −200 에서 수면에 닿는다 — 예전엔 A 의 수면 (0) 만 따라가 B 위 공기 속을 −28 까지 헤엄쳤다.
	 */
	@Test
	void surfaceFollowsPoolAboveHead() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 0, 400, 0, SurfaceAttr.WATER); // 웅덩이 A 수면
		floor(t, 0, -400, 400, 400, -200, SurfaceAttr.WATER); // 웅덩이 B 수면
		floor(t, -400, -400, 400, 400, -600, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		b.pos.set(-200, 0, 0);
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		tick(nav, b, w, in);
		for (int i = 0; i < 150; i++) {
			in.down.add(PadButton.SQUARE);
			tick(nav, b, w, in);
		}
		assertEquals(HeroNavigator.Water.DIVE, nav.water());
		assertEquals(0, nav.waterSurface(), 1e-3f);
		assertTrue(b.pos.y < -250, "잠수 " + b.pos.y);
		b.pos.x = 200; // 물속으로 B 아래까지 헤엄쳐 옴
		float top = -Float.MAX_VALUE;
		for (int i = 0; i < 300 && nav.state() == NavState.SWIM; i++) {
			in.down.add(PadButton.TRIANGLE);
			tick(nav, b, w, in);
			top = Math.max(top, b.pos.y);
		}
		assertEquals(-200, nav.waterSurface(), 1e-3f, "B 수면");
		assertTrue(top <= -200 - HeroNavigator.SWIM_DEPTH + 1, "B 수면 위 공기로 헤엄쳐 오르지 않음: " + top);
	}

	/** 스냅숏 되읽기 0x1DC130: 이동 상태 +0x174 (물 0x200) 를 그대로 — 바닥까지 가라앉지 않고 잠수 상태, 수면 = 위쪽 첫 Water 면. */
	@Test
	void checkpointRestoreKeepsDive() {
		NavWorld w = deepPool();
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		b.pos.set(0, -150, 0);
		b.grounded = false;
		nav.reset();
		nav.restoreWater(HeroNavigator.Water.DIVE);
		assertEquals(NavState.SWIM, nav.state());
		assertEquals(HeroNavigator.Water.DIVE, nav.water());
		assertEquals(0, nav.waterSurface(), 1e-3f);
		Pad in = new Pad();
		for (int i = 0; i < 120; i++) {
			tick(nav, b, w, in);
		}
		assertEquals(HeroNavigator.Water.DIVE, nav.water());
		assertEquals(-150, b.pos.y, 1e-3f, "잠수 중 부력 · 중력 없음");
		// 삼지창이 없으면 잠수 상태로 되돌리지 않는다 (가라앉음 → 부력으로 뜬다)
		HeroNavigator nav2 = new HeroNavigator(b, w, null);
		nav2.diveAbility = false;
		nav2.restoreWater(HeroNavigator.Water.DIVE);
		assertEquals(HeroNavigator.Water.SINK, nav2.water());
	}

	/**
	 * PAND02D 돌진 문 godashdoorgrp* = Breakable (Brkb 0 HP 1) + 공 하나 CDV (반지름 56, 재질 Ground · TakeDamage): 몸 구를 밀어낸다.
	 * Breakable 이 아닌 공 CDV 는 몸이 아니다.
	 */
	@Test
	void dashDoorBallBlocksBody() throws Exception {
		Path p = ROM.resolve("PAND02D.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData d = new LevelData(WadFile.load(p));
		Platforms ps = Platforms.of(d, Set.of());
		int doors = 0;
		for (Platforms.Body body : ps.bodies()) {
			if (body.owner.startsWith("godashdoorgrp")) {
				doors++;
			}
		}
		assertEquals(3, doors);
		Vector3f c = new Vector3f(960, -199, -3380); // 첫 문 (중심 ≈ (960, −204, −3393)) 앞쪽에 파고든 몸
		assertTrue(ps.collideSphere(c, HeroNavigator.BODY, Set.of()));
		assertTrue(c.z > -3340, "문 앞으로 밀림: " + c);
		ps.setSolid("godashdoorgrp", false);
		Vector3f c2 = new Vector3f(960, -199, -3380);
		assertFalse(ps.collideSphere(c2, HeroNavigator.BODY, Set.of()), "부서진 뒤 통과");
	}

	/** 접촉 0x1DE4D8 의 공격 없는 경로: 돌진 (+0x2D8 0x80000) · |속도| / 16 &gt; Sold+0x214 → 1000 피해 (0x447A0000). 크라토스 Sold+0x214 = 18. */
	@Test
	void dashBreakThreshold() throws Exception {
		Elf e = exe();
		assertTrue(insn(e, 0x1DE600).startsWith("lw") && insn(e, 0x1DE600).contains("0x2d8("), insn(e, 0x1DE600));
		assertTrue(insn(e, 0x1DE640).startsWith("lwc1") && insn(e, 0x1DE640).contains("0x214("), insn(e, 0x1DE640));
		assertTrue(insn(e, 0x1DE654).startsWith("lui") && insn(e, 0x1DE654).contains("0x447a"), insn(e, 0x1DE654));
		assertEquals(18f, NavTuning.KRATOS.get(NavTuning.DASH_BREAK), 1e-6f);
		assertEquals(288f, HeroNavigator.DASH_BREAK, 1e-3f);
		// 반쯤 충전 (0.75 초 → 16.5 m/s) 은 못 부수고, 0.82 초 넘게 충전해야 18 m/s 를 넘는다
		float half = HeroNavigator.DASH_MIN + (HeroNavigator.DASH_MAX - HeroNavigator.DASH_MIN) * 0.5f;
		assertTrue(half < HeroNavigator.DASH_BREAK && HeroNavigator.DASH_MAX > HeroNavigator.DASH_BREAK);
	}
}
