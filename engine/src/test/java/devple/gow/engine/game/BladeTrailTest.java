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
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
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

/** 블레이드 휘두르기 궤적 띠 (0x12DE70 / 0x240A88 / 0x12E8F0, 45 문서 §3.4, 43 §35). */
class BladeTrailTest {

	private static final float DT = 1 / 60f;

	@Test
	void ringSizeLifetimeAndTexCoords() {
		// 0x12DE70: 0.32·60 → 19 프레임 × 10 = 190, 패킷당 (0xF5 − 2) / (6·2) = 20 → 10 패킷 → 200 칸
		assertEquals(200, BladeTrail.CAPACITY);
		assertEquals(0.32f, BladeTrail.LIFETIME, 0f);
		assertEquals(3, BladeTrail.POINTS);
		assertArrayEquals(new float[]{0, 0.5f, 1}, BladeTrail.S, 0f);
		assertEquals(10, BladeTrail.MAX_STEPS);
		// 색 + 텍스처 (qword 3) 라도 패킷당 (245 − 3)/(6·3) = 13 → 15 패킷 → 195
		assertEquals(195, BladeTrail.capacity(0.32f, 3, 3));
	}

	@Test
	void matrixHistoryIsQuadraticThroughThreeFrames() {
		BladeTrail.MatrixHistory h = new BladeTrail.MatrixHistory();
		// 원소별 P(s) = c + l·s + q·s² (s = 초)
		float[] c0 = new float[16], l = new float[16], q = new float[16];
		for (int i = 0; i < 16; i++) {
			c0[i] = i * 0.5f - 3;
			l[i] = (i % 3) * 10f - 7;
			q[i] = (i % 5) * 40f - 60;
		}
		// 첫 push: 셋 다 같고 t1 = t2 = 누적 (0x12EDA8 첫 분기, +0x8C 는 그대로)
		h.tick(DT);
		h.push(at(c0, l, q, 0));
		assertEquals(DT, h.t1, 0f);
		assertEquals(DT, h.t2, 0f);
		assertEquals(DT, h.acc, 0f, "첫 push 는 +0x8C 를 0 으로 돌리지 않는다");
		h.tick(DT);
		h.push(at(c0, l, q, 2 * DT));
		assertEquals(DT, h.t1, 0f);
		assertEquals(2 * DT, h.t2, 1e-9f, "둘째 push: +0x88 ← +0x8C (= 2/60)");
		assertEquals(0f, h.acc, 0f);
		h.tick(DT);
		h.push(at(c0, l, q, 3 * DT));
		// C = 0 초, A = 2/60, B = 3/60 → t1 = 2/60, t2 = 1/60
		assertEquals(2 * DT, h.t1, 1e-9f);
		assertEquals(DT, h.t2, 1e-9f);
		h.fit();
		float[] out = new float[16];
		for (float u : new float[]{0, 0.25f, 0.5f, 1}) {
			h.eval(u, out);
			float[] want = at(c0, l, q, 2 * DT + u * DT);
			for (int i = 0; i < 16; i++) {
				assertEquals(want[i], out[i], 2e-3f, "u " + u + " 원소 " + i);
			}
		}
		// 하위 표본 수 (0x12F288): 이동 2.6 → 4·2.6 = 10.4 → 10, Y 축 90° 회전만 → 4·√4 = 8
		BladeTrail.MatrixHistory m = new BladeTrail.MatrixHistory();
		m.push(new Matrix4f().get(new float[16]));
		m.push(new Matrix4f().translation(2.6f, 0, 0).get(new float[16]));
		assertEquals(10, m.steps(BladeTrail.ROT_WEIGHT, BladeTrail.MOVE_WEIGHT));
		m.push(new Matrix4f().translation(2.6f, 0, 0).rotateY((float) Math.PI / 2).get(new float[16]));
		assertEquals(8, m.steps(BladeTrail.ROT_WEIGHT, BladeTrail.MOVE_WEIGHT));
		m.push(new Matrix4f().translation(2.6f, 0.2f, 0).rotateY((float) Math.PI / 2).get(new float[16]));
		assertEquals(0, m.steps(BladeTrail.ROT_WEIGHT, BladeTrail.MOVE_WEIGHT), "4·0.2 = 0.8 → 0 (버림)");
	}

	private static float[] at(float[] c, float[] l, float[] q, float s) {
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) {
			m[i] = c[i] + l[i] * s + q[i] * s * s;
		}
		return m;
	}

	@Test
	void pointHistoryIsQuadraticWithUnitSpacing() {
		BladeTrail.PointHistory h = new BladeTrail.PointHistory();
		// P(s) = (s², 3s, −s² + 2) 을 s = 0, 1, 2 에서 넣고 (첫 push 는 셋 다 같음 → 한 번 더 넣어 밀어냄)
		h.push(pts(0));
		assertEquals(h.p0[1].x, h.p2[1].x, 0f);
		h.push(pts(0));
		h.push(pts(1));
		h.push(pts(2));
		h.fit();
		Vector3f[] out = {new Vector3f(), new Vector3f(), new Vector3f()};
		for (float u : new float[]{0, 0.3f, 1}) {
			h.eval(u, out); // s = 1 + u
			Vector3f[] want = pts(1 + u);
			for (int i = 0; i < 3; i++) {
				assertEquals(want[i].x, out[i].x, 1e-4f);
				assertEquals(want[i].y, out[i].y, 1e-4f);
				assertEquals(want[i].z, out[i].z, 1e-4f);
			}
		}
	}

	private static Vector3f[] pts(float s) {
		return new Vector3f[]{new Vector3f(s * s, 3 * s, -s * s + 2), new Vector3f(s, 0, 1), new Vector3f(0, 2 * s * s - s, s)};
	}

	/** 블레이드가 +X 로 프레임당 v 만큼 곧게 움직이는 띠 (점 = 블레이드 로컬 (0,0,0) · (0,0,−10) · (0,0,−30)). */
	private static void move(BladeTrail.Side t, float x, boolean on) {
		Matrix4f blade = new Matrix4f().translation(x, 5, 0);
		Vector3f[] w = {new Vector3f(x, 5, 0), new Vector3f(x, 5, -10), new Vector3f(x, 5, -30)};
		t.update(blade, w, on, DT);
	}

	@Test
	void stripsAgeOutAndCarryTextureT() {
		BladeTrail tr = new BladeTrail();
		BladeTrail.Side t = tr.sides[0];
		float x = 0;
		for (int f = 0; f < 3; f++) { // 꺼진 채로 이력만
			move(t, x, false);
			x += 3;
		}
		assertEquals(0, t.size());
		for (int f = 0; f < 30; f++) {
			move(t, x, true);
			x += 3;
			// 4·3 = 12 → 10: 하위 9 + 지금 1
			assertEquals(9, t.lastSubSamples);
		}
		assertTrue(t.size() >= 190 && t.size() <= 193, "0.32 초 = 19.2 프레임 × 10 띠 안팎이 살아 있다 (시각 < 지금 − 0.32 인 꼬리만 빠짐): " + t.size());
		BladeTrail.Strip newest = t.strip(t.size() - 1), oldest = t.strip(0);
		assertEquals(1f, newest.tNext, 1e-6f, "새 표본 T = 1 (텍스처 아래 줄)");
		assertEquals(0f, oldest.tPrev, 0f, "꼬리 띠의 옛 정점 T = 0");
		float prev = -1;
		for (int k = 0; k < t.size(); k++) {
			BladeTrail.Strip s = t.strip(k);
			assertTrue(s.tNext >= prev, "꼬리 → 머리로 T 증가");
			assertTrue(s.tNext >= 0 && s.tNext <= 1);
			if (k > 0) {
				assertEquals(t.strip(k - 1).tNext, s.tPrev, 0f, "옛 정점 T = 이전 띠의 새 T");
				assertEquals(t.strip(k - 1).next[2].x, s.prev[2].x, 0f, "옛 정점 = 이전 띠의 새 정점");
			}
			prev = s.tNext;
		}
		// 곧은 등속 이동: 하위 표본은 이전 → 지금 사이를 1/10 씩 (이차식이 직선으로 떨어짐)
		BladeTrail.Strip a = t.strip(t.size() - 2);
		assertEquals(newest.next[0].x - 0.3f, a.next[0].x, 1e-3f);
		assertEquals(-30f, newest.next[2].z, 1e-4f, "점 2 (칼끝) 그대로");
		// 끄면 새 띠 없이 0.32 초 안에 모두 빠진다 (끈 뒤에도 남은 띠는 그려짐)
		int frames = 0;
		while (t.size() > 0) {
			move(t, x, false);
			x += 3;
			frames++;
			assertTrue(frames <= 21, "0.32 초 + 한 프레임 안에 사라짐");
		}
		assertTrue(frames >= 19, "곧바로 사라지지 않고 수명 동안 남음: " + frames);
	}

	@Test
	void firstStripAfterTrailWasOffHasNoArea() {
		BladeTrail tr = new BladeTrail();
		BladeTrail.Side t = tr.sides[0];
		float x = 0;
		for (int f = 0; f < 5; f++) {
			move(t, x, true);
			x += 1;
		}
		move(t, x, false);
		x += 1;
		int before = t.size();
		move(t, x, true); // 0x240D78: +0x15C = 0 이면 이번 첫 띠의 옛 정점 = 새 정점
		int added = t.size() - before;
		assertEquals(4, added, "4·1 = 4 → 하위 3 + 지금 1");
		BladeTrail.Strip first = t.strip(before);
		for (int i = 0; i < 3; i++) {
			assertEquals(first.next[i].x, first.prev[i].x, 0f, "면적 0");
		}
		BladeTrail.Strip second = t.strip(before + 1);
		assertEquals(first.next[0].x, second.prev[0].x, 0f, "둘째부터는 이어짐");
		assertNotEquals(second.prev[0].x, second.next[0].x);
		// 순간 이동 (0x12E768): 띠·이력 지움
		tr.reset();
		assertEquals(0, t.size());
		move(t, x + 500, true);
		assertEquals(0, t.lastSubSamples, "이력이 새로 시작해 하위 표본 없음 (500 앞으로 뛰어도 줄을 긋지 않음)");
		assertEquals(1, t.size());
	}

	@Test
	void comboLeavesWideArcFromChainToTip() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HERO0.WAD")), "gow-rom 없음");
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		float bladeLen = tw.f("/Player/Costume 0/", "Weapon Length", 0.7f) * 16f;
		BladeChain chain = BladeChain.fromTweaks(tw);
		BladeTrail trail = new BladeTrail();
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
		for (int i = 0; i < 3; i++) {
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
		float maxWidth = 0;
		int maxStrips = 0, subSamples = 0, offFrames = 0;
		boolean sawOn = false, emptiedAfterOff = false;
		for (int f = 0; f < 200; f++) {
			in.advance(DT);
			c.update(in, DT);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, DT, dummy.pos);
			} else {
				ctl.update(in, 0, DT);
			}
			var m = c.move();
			chain.update(joints, c.active(), m != null && (m.flags() & 0x80) != 0, DT);
			boolean on = c.trail(0);
			trail.update(chain, new boolean[]{on, c.trail(1)}, bladeLen, DT);
			BladeTrail.Side t = trail.sides[0];
			if (on) {
				sawOn = true;
				offFrames = 0;
				subSamples += t.lastSubSamples;
				// 머리 띠의 새 정점 = 이번 틱 세 점 (베지어 0.2 · 0.7 · 칼끝)
				Vector3f[] p = BladeTrail.points(chain.sides[0], bladeLen);
				BladeTrail.Strip head = t.strip(t.size() - 1);
				for (int i = 0; i < 3; i++) {
					assertEquals(0f, head.next[i].distance(p[i]), 1e-3f);
				}
				assertEquals(BladeChain.bezier(chain.sides[0].ctrl, 0.2f, new Vector3f()).x, p[0].x, 1e-4f);
			} else if (sawOn) {
				offFrames++;
				if (t.size() == 0 && !emptiedAfterOff) {
					emptiedAfterOff = true;
					assertTrue(offFrames <= 21, "끈 뒤 0.32 초 안에 사라짐: " + offFrames);
				}
			}
			maxStrips = Math.max(maxStrips, t.size());
			for (int k = 0; k < t.size(); k++) {
				BladeTrail.Strip s = t.strip(k);
				maxWidth = Math.max(maxWidth, s.next[0].distance(s.next[2]));
			}
		}
		assertTrue(sawOn, "□ 콤보의 이벤트 17 TRAIL_LEFT");
		assertTrue(subSamples > 0, "빠른 휘두르기는 프레임 사이를 하위 표본으로 채운다");
		assertTrue(maxStrips > 60, "띠가 여러 프레임 쌓인다: " + maxStrips);
		assertTrue(maxWidth > bladeLen + 10, "폭 = 손 쪽 사슬 (t 0.2) → 칼끝, 칼 길이보다 넓다: " + maxWidth);
		assertTrue(emptiedAfterOff, "콤보가 끝나면 띠가 사라진다");
	}
}
