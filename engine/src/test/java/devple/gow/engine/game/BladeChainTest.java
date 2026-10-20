package devple.gow.engine.game;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.combat.CombatOwner;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.TrainingDummy;
import devple.gow.engine.fx.MagicFx;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.level.SurfaceAttr;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 블레이드 사슬·궤적 기하 (45 문서 §3, 0x1FB460 / 0x1FC070 / 0x1FC870). */
class BladeChainTest {

	private static Vector3f[] straight(float len) {
		Vector3f[] p = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
		BladeChain.controlPoints(new Vector3f(0, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, 0, -len), new Vector3f(0, 0, 1), p);
		return p;
	}

	@Test
	void bezierControlPoints() {
		Vector3f[] p = straight(30);
		// L = 0.33 · 30 = 9.9: P1 = P0 − Z·9.9, P2 = P3 + Z·9.9
		assertEquals(-9.9f, p[1].z, 1e-4f);
		assertEquals(-20.1f, p[2].z, 1e-4f);
		assertEquals(0f, BladeChain.bezier(p, 0, new Vector3f()).distance(p[0]), 1e-5f);
		assertEquals(0f, BladeChain.bezier(p, 1, new Vector3f()).distance(p[3]), 1e-5f);
		// 직선이면 길이 근사 = 현
		assertEquals(30f, BladeChain.arcLength(p), 1e-4f);
		// 0x1FC070 의 번스타인 계수 (t = 0.2 → 0.512, 0.384, 0.096, 0.008)
		Vector3f[] q = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, 0)};
		Vector3f b = BladeChain.bezier(q, 0.2f, new Vector3f());
		assertEquals(0.512f, b.x, 1e-5f);
		assertEquals(0.384f, b.y, 1e-5f);
		assertEquals(0.096f, b.z, 1e-5f);
	}

	@Test
	void chainSamplesAndSegments() {
		BladeChain c = new BladeChain();
		Vector3f[] p = straight(30);
		// 마디 = 30 / (0.25 × 16) = 7.5 → t = 1, 1−1/7.5, … , 0 → 표본 9
		float n = c.segments(p);
		assertEquals(7.5f, n, 1e-4f);
		Vector3f eye = new Vector3f(100, 0, -15);
		List<float[]> s = BladeChain.samples(p, new Vector3f(0, 0, 1), eye, n);
		assertEquals(9, s.size());
		assertEquals(1f, s.getFirst()[6], 1e-6f);
		assertEquals(0f, s.getLast()[6], 1e-6f);
		for (float[] v : s) {
			Vector3f side = new Vector3f(v[3], v[4], v[5]);
			assertEquals(1f, side.length(), 1e-4f);
			assertEquals(0f, side.z, 1e-4f, "옆 벡터 ⟂ 접선 (Z)");
			assertEquals(0f, side.dot(new Vector3f(v[0], v[1], v[2]).sub(eye).normalize()), 1e-4f, "옆 벡터 ⟂ 시선");
		}
		assertTrue(BladeChain.samples(p, new Vector3f(0, 0, 1), eye, 1f).isEmpty(), "마디 ≤ 1 → 숨김 (0x1FDA08)");
		assertEquals(0.46875f, BladeChain.glowLevel(n), 1e-6f);
		assertEquals(1f, BladeChain.glowLevel(40), 0f);
		Vector3f[] longP = straight(1000);
		assertEquals(BladeChain.MAX_SAMPLES, BladeChain.samples(longP, new Vector3f(0, 0, 1), eye, c.segments(longP)).size());
	}

	@Test
	void trailFan() {
		Vector3f[] p = {new Vector3f(0, 0, 0), new Vector3f(10, 0, 0), new Vector3f(20, 10, 0), new Vector3f(20, 20, 0)};
		float[][][] t = BladeChain.trail(p, new Vector3f(0, -1, 0), 11.2f);
		assertEquals(7, t[0].length);
		assertEquals(9, t[1].length);
		float[] u1 = {0, .125f, .25f, 0, .375f, 0, .5f};
		for (int i = 0; i < 7; i++) {
			assertEquals(u1[i], t[0][i][3], 1e-6f);
		}
		// A (t=0.2) 가 부채꼴 중심: 0, 3, 5 번이 같은 점
		assertArrayEquals(t[0][0], t[0][3], 0f);
		assertArrayEquals(t[0][0], t[0][5], 0f);
		Vector3f a = BladeChain.bezier(p, 0.2f, new Vector3f());
		assertEquals(a.x, t[0][0][0], 1e-5f);
		// 칼끝 = P3 − Z · 길이, u = 1
		float[] tip = t[1][8];
		assertEquals(20f, tip[0], 1e-5f);
		assertEquals(31.2f, tip[1], 1e-4f);
		assertEquals(1f, tip[3], 0f);
		// f = |I − E| / (|I − E| + 길이)
		Vector3f e = BladeChain.bezier(p, 0.7f, new Vector3f());
		float ie = e.distance(p[3]);
		assertEquals(.5f + ie / (ie + 11.2f) * .5f, t[1][6][3], 1e-5f);
		assertEquals(12, BladeChain.stripTriangles(7).size() + BladeChain.stripTriangles(9).size(), "띠 7 → 5, 띠 9 → 7");
	}

	@Test
	void modeSwitch() {
		float near = 2, far = 30;
		assertEquals(BladeChain.IN_HAND, BladeChain.nextMode(BladeChain.BACK, true, near, far));
		assertEquals(BladeChain.OUT_HAND, BladeChain.nextMode(BladeChain.IN_HAND, true, far, far));
		assertEquals(BladeChain.BACK, BladeChain.nextMode(BladeChain.OUT_HAND, true, far, near));
		// 기술 없음: 등은 그대로, 손 밖 → 손 (손이나 등이 가까우면), 손 → 등 (등이 가까우면)
		assertEquals(BladeChain.BACK, BladeChain.nextMode(BladeChain.BACK, false, near, far));
		assertEquals(BladeChain.IN_HAND, BladeChain.nextMode(BladeChain.OUT_HAND, false, far, near));
		assertEquals(BladeChain.OUT_HAND, BladeChain.nextMode(BladeChain.OUT_HAND, false, far, far));
		assertEquals(BladeChain.BACK, BladeChain.nextMode(BladeChain.IN_HAND, false, far, near));
		// Blade Damping 0.4 / 프레임: 1/60 초면 그대로, 2 프레임이면 1 − 0.6²
		assertEquals(0.4f, BladeChain.step(0.4f, 1), 1e-6f);
		assertEquals(0.64f, BladeChain.step(0.4f, 2), 1e-6f);
		assertEquals(1f, BladeChain.step(1f, 0.5f), 0f);
	}

	@Test
	void wallQueryMaskAndSweepPoints() {
		// 0x3309F4 = 0x0B130000 (0x1FB2F0)
		long m = BladeChain.WALL_EXCLUDE;
		for (SurfaceAttr a : new SurfaceAttr[]{SurfaceAttr.AI_BLOCK, SurfaceAttr.CLIMB_GUIDE, SurfaceAttr.NO_PLAYER_COLLISION,
				SurfaceAttr.GENERAL_GUIDE, SurfaceAttr.NO_DIVING, SurfaceAttr.COMBAT_GUIDE}) {
			assertTrue((m & a.bit()) != 0, a.name());
		}
		assertEquals(0, m & (SurfaceAttr.GROUND.bit() | SurfaceAttr.WATER.bit() | SurfaceAttr.DEATH.bit() | SurfaceAttr.CLIMBABLE.bit()));
		assertEquals(6, Long.bitCount(m));
		// 적 스윕 끝점: 6.4 이하면 지금 점 하나, 16 이면 s = 0.4 → t = 1, 0.6 (원작 루프: 다음 t − s ≥ 0 인 동안)
		List<Vector3f> out = new ArrayList<>();
		BladeChain.sweepPoints(new Vector3f(0, 0, 0), new Vector3f(5, 0, 0), out);
		assertEquals(1, out.size());
		assertEquals(5f, out.getFirst().x, 1e-6f);
		out.clear();
		BladeChain.sweepPoints(new Vector3f(0, 0, 0), new Vector3f(16, 0, 0), out);
		assertEquals(2, out.size());
		assertEquals(16f, out.get(0).x, 1e-5f);
		assertEquals(9.6f, out.get(1).x, 1e-4f);
		out.clear();
		BladeChain.sweepPoints(new Vector3f(0, 0, 0), new Vector3f(25.6f, 0, 0), out);
		assertEquals(4, out.size(), "s = 0.25 → t = 1, .75, .5, .25");
		assertEquals(6.4f, out.getLast().x, 1e-3f);
	}

	/** 합성 관절: 등·손 관절은 영웅 옆, lWeapOH 는 정면 (−Z) 60 → 기술 중이면 손 밖. 벽 x−z 평면 z = −30. */
	@Test
	void wallPullsBladeToHitPoint() {
		Vector3f oh = new Vector3f(0, 20, -60);
		BladeChain.Joints j = (name, out) -> {
			switch (name) {
				case "lWeapOH", "rWeapOH" -> out.translation(oh);
				case "lChainW", "rChainW" -> out.translation(oh.x, oh.y, oh.z + 3);
				case "Pelvis" -> out.translation(0, 20, 0);
				default -> out.translation(0, 20, 5);
			}
			return true;
		};
		float wallZ = -30;
		List<Long> masks = new ArrayList<>();
		BladeChain.Walls walls = (from, to, exclude) -> {
			masks.add(exclude);
			if (to.z >= wallZ) {
				return null;
			}
			float t = (wallZ - from.z) / (to.z - from.z);
			Vector3f h = new Vector3f(from).lerp(to, t);
			return new float[]{h.x, h.y, h.z, 0, 0, 1, 20};
		};
		BladeChain c = new BladeChain();
		Vector3f root = new Vector3f(0, 0, 0);
		float dt = 1 / 60f;
		c.update(j, true, false, dt, root, walls);
		BladeChain.Side s = c.sides[0];
		assertEquals(BladeChain.OUT_HAND, s.mode);
		boolean hit = false;
		for (int f = 0; f < 60; f++) {
			c.update(j, true, false, dt, root, walls);
			Vector3f pos = s.blade.getTranslation(new Vector3f());
			Vector3f point = new Vector3f(s.bladeZ).mul(-BladeChain.BLADE_POINT_BACK).add(pos);
			assertTrue(point.z >= wallZ - 1e-3f, "블레이드 점은 벽을 넘지 않는다: " + point.z);
			if (s.wallHit) {
				hit = true;
				assertEquals(wallZ, point.z, 1e-3f, "블레이드 점 = 충돌점 (0x1FB370)");
				assertEquals(0f, s.wallPoint.distance(point), 1e-3f);
				assertEquals(20, s.wallSheetFx);
				assertTrue(s.pull.z > 0, "당김 = 충돌점 − 블레이드 점 (+0x50)");
				assertEquals(20f, s.sweepFrom.y, 1e-5f, "시작점 높이 = 골반");
				assertFalse(s.sweepTo.isEmpty());
			}
		}
		assertTrue(hit, "벽 너머로 던진 블레이드가 막힘");
		assertTrue(masks.stream().allMatch(x -> x == BladeChain.WALL_EXCLUDE));
		// 벽이 없으면 로케이터까지 따라간다
		BladeChain free = new BladeChain();
		for (int f = 0; f < 61; f++) {
			free.update(j, true, false, dt, root, null);
		}
		assertFalse(free.sides[0].wallHit);
		assertEquals(-60f, free.sides[0].blade.getTranslation(new Vector3f()).z, 0.1f);
		// 손 밖이 아니면 질의 없음
		masks.clear();
		BladeChain idle = new BladeChain();
		idle.update(j, false, false, dt, root, walls);
		assertTrue(masks.isEmpty());
		assertTrue(idle.sides[0].sweepTo.isEmpty());
	}

	@Test
	void comboThrowsBladesOnChain() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HERO0.WAD")), "gow-rom 없음");
		WadFile heroWad = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		BladeChain chain = BladeChain.fromTweaks(tw);
		assertEquals(0.4f, chain.damping, 1e-6f, "/Player/Blade Damping");
		assertEquals(0.25f, chain.segmentLength, 1e-6f);
		assertEquals(0.13f, chain.linkDiameter, 1e-4f);
		assertEquals(0.18f, chain.glowDiameter, 1e-4f);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		Animator animator = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(animator);
		TrainingDummy dummy = new TrainingDummy(new Vector3f(0, 0, -50), 1000, 10);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 2; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return -1; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), animator, owner);
		c.setTarget(dummy);
		List<ScriptedInput.Step> steps = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			steps.add(new ScriptedInput.Step(0.3f + i * 0.3f, 0.05f, PadButton.SQUARE));
		}
		ScriptedInput in = new ScriptedInput(0, steps);
		BladeChain.Joints joints = (name, out) -> {
			int i = MagicFx.joint(sk, name);
			if (i < 0) {
				return false;
			}
			ctl.world(out, 1f).mul(new Matrix4f().set(animator.pose().world(sk)[i]));
			return true;
		};
		float dt = 1 / 60f;
		// 같은 콤보를 정면 (−Z) 25 앞 벽이 있는 사슬로도 (0x1FAFC8)
		BladeChain walled = BladeChain.fromTweaks(tw);
		float wallZ = -25;
		BladeChain.Walls wall = (from, to, exclude) -> {
			if (to.z >= wallZ || from.z < wallZ) {
				return null;
			}
			Vector3f h = new Vector3f(from).lerp(to, (wallZ - from.z) / (to.z - from.z));
			return new float[]{h.x, h.y, h.z, 0, 0, 1, 20};
		};
		int wallHits = 0;
		boolean sawOut = false, sawTrail = false, sawLag = false;
		float maxSeg = 0;
		Matrix4f oh = new Matrix4f();
		for (int f = 0; f < 150; f++) {
			in.advance(dt);
			c.update(in, dt);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, dummy.pos);
			} else {
				ctl.update(in, 0, dt);
			}
			var m = c.move();
			chain.update(joints, c.active(), m != null && (m.flags() & 0x80) != 0, dt);
			walled.update(joints, c.active(), m != null && (m.flags() & 0x80) != 0, dt, ctl.position, wall);
			for (BladeChain.Side ws : walled.sides) {
				Vector3f bp = new Vector3f(ws.bladeZ).mul(-BladeChain.BLADE_POINT_BACK).add(ws.blade.getTranslation(new Vector3f()));
				if (ws.wallHit) {
					wallHits++;
					assertEquals(wallZ, bp.z, 1e-2f, "막힌 블레이드 점 = 벽 위 충돌점");
				}
			}
			if (f == 0) {
				assertEquals(BladeChain.BACK, chain.sides[0].mode, "처음엔 등 (0x1FA5B0)");
				assertFalse(chain.sides[0].chainVisible);
			}
			BladeChain.Side s = chain.sides[0];
			assertTrue(s.valid());
			sawTrail |= c.trail(0);
			if (s.mode == BladeChain.OUT_HAND) {
				sawOut = true;
				assertTrue(s.chainVisible);
				maxSeg = Math.max(maxSeg, chain.segments(s.ctrl));
				joints.world("lWeapOH", oh);
				if (s.blade.getTranslation(new Vector3f()).distance(oh.getTranslation(new Vector3f())) > 1f) {
					sawLag = true; // 손 밖에서는 로케이터를 0.4 비율로 따라간다
				}
				// 사슬 끝 P3 은 블레이드 행렬에 붙은 lChainW (블레이드 원점에서 몇 단위 안)
				assertTrue(s.ctrl[3].distance(s.blade.getTranslation(new Vector3f())) < 6f);
			}
		}
		assertTrue(sawTrail, "콤보 기술의 이벤트 17 TRAIL_LEFT");
		assertTrue(sawOut, "□ 콤보 중 왼쪽 블레이드가 손 밖 (사슬 투척)");
		assertTrue(sawLag, "블레이드가 무기 로케이터를 늦게 따라감 (Blade Damping)");
		assertTrue(maxSeg > 4, "던진 사슬은 여러 마디: " + maxSeg);
		assertTrue(wallHits > 0, "던진 블레이드가 앞 벽에 막힘 (골반 관절 조회 포함)");
	}
}
