package devple.gow.engine.nav;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.SurfaceAttr;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IO_TandF 미끄럼 문 (템플릿 +0x158 비트 0, 0x214400 → 0x214058 / 0x214180) — 43 문서 §19 (영상 6527–6535: ATHN04C 나무 게이트가 크라토스 손과 함께
 * 올라간다). 예전 엔진은 모든 TAndF 를 오브젝트 애니로만 움직여 (리프트 게이트 ANM 은 길이 0 act 하나) 게이트가 그대로 막고 있었다.
 */
class SlideDoorTest {

	static final float DT = 1 / 60f;
	/** 시험 몸의 synchJoint 높이 = 세운 클립 시간 × K (liftDoor2 5.467 초 → 33.8, R_HERO0 실제 값 근처). */
	static final float LIFT_LEN = 5.467f, K = 33.8f / LIFT_LEN;

	/** 영웅 몸: scrub 한 시간으로 synchJoint 모델 y 를 낸다. */
	static final class SlideBody implements NavBody {
		final Vector3f pos = new Vector3f(), launch = new Vector3f();
		float yaw, vy;
		boolean grounded = true;
		String anim = "";
		float scrubTime = -1;
		final List<Float> scrubs = new ArrayList<>();

		public Vector3f position() {
			return pos;
		}

		public float yaw() {
			return yaw;
		}

		public void setYaw(float y) {
			yaw = y;
		}

		public float vy() {
			return vy;
		}

		public void setVy(float v) {
			vy = v;
		}

		public boolean grounded() {
			return grounded;
		}

		public void setGrounded(boolean g) {
			grounded = g;
		}

		public Vector3f launch() {
			return launch;
		}

		public void play(String a, boolean restart) {
			if (!a.equals(anim) || restart) {
				scrubTime = -1;
			}
			anim = a;
		}

		public boolean animFinished() {
			return true;
		}

		@Override
		public float clipLength(String a) {
			return "liftdoor2".equals(a) ? LIFT_LEN : -1f;
		}

		public void locomotion(String w, String idle, float s) {
		}

		@Override
		public void scrub(String a, float t) {
			play(a, false);
			scrubTime = t;
			scrubs.add(t);
		}

		@Override
		public float jointModelY(String joint) {
			assertEquals(Interactables.SYNCH_JOINT, joint);
			return "liftdoor2".equals(anim) && scrubTime >= 0 ? scrubTime * K : 0;
		}
	}

	private static HeroNavigator nav(SlideBody b, Interactables items) {
		List<LevelCollision.Tri> t = new ArrayList<>();
		HeroNavigatorTest.floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		HeroNavigator nav = new HeroNavigator(b, new NavWorld(LevelCollision.of(t)), null);
		nav.setItems(items);
		return nav;
	}

	/** ATHN04C "Slow Lift Gate" (TAndF 007) 값. */
	private static Interactables.Item gate(Interactables items) {
		Interactables.Item g = items.add(new Interactables.Item(Interactables.Kind.LIFT, "goliftdoor13", new Vector3f(0, 0, -30), 7, 504,
				"liftdoor2", null, "Slow Lift Gate", 20));
		g.acc = 1;
		g.dec = 20;
		g.maxSpeed = 1;
		g.maxBack = 20;
		g.tfTime = 0.2f;
		g.slide = true;
		g.liftHeight = 80;
		return g;
	}

	private static void tick(HeroNavigator nav, HeroNavigatorTest.Pad in) {
		nav.update(in, 0, DT);
		in.down.clear();
	}

	/** 붙은 동안 문 y = 처음 + synchJoint (영웅 애니 시간 = 위치 × 길이로 세움), 다 들면 중력 ×16 으로 80 까지 올라가 멈춤. */
	@Test
	void gateFollowsSynchJointThenRisesToItsHeight() {
		SlideBody b = new SlideBody();
		Interactables items = new Interactables();
		Interactables.Item g = gate(items);
		HeroNavigator nav = nav(b, items);
		HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
		in.down.add(PadButton.USE);
		tick(nav, in);
		assertEquals(NavState.INTERACT, nav.state());
		int ticks = 0;
		while (!g.done && ticks < 900) {
			if (ticks % 6 == 0) {
				in.down.add(PadButton.USE); // 초당 10 번 연타 (TF Time 0.2 안)
			}
			tick(nav, in);
			ticks++;
			if (!g.done) {
				float pos = g.task().time;
				assertEquals(pos * LIFT_LEN, b.scrubTime, 1e-3f, "ANITandF2 = 위치 × 길이로 세움 (0x1ECA44)");
				assertEquals(pos * LIFT_LEN * K, g.slideY, 1e-2f, "문 y = 처음 + synchJoint (0x214058)");
			}
		}
		assertTrue(g.done, "연타로 다 들어 올림");
		// 최대 속도 1 → 위치 += dt/4 → 4 초 넘게 (Slow Lift Gate)
		assertTrue(ticks * DT >= 4f - 0.05f, "최소 4 초: " + ticks * DT);
		assertEquals(33.8f, g.slideY, 0.05f, "완료 프레임: liftDoor2 끝 synchJoint");
		float prevVy = -1;
		int rise = 0;
		while (g.slideY < 80 && rise < 120) {
			tick(nav, in);
			if (g.slideY < 80) {
				assertTrue(g.slideVy > prevVy, "중력 50×16 으로 가속하며 오름 (0x214180)");
				prevVy = g.slideVy;
			}
			rise++;
		}
		assertEquals(80, g.slideY, 1e-4f, "높이 #8743c048 5 × 16");
		assertTrue(rise * DT < 0.4f, "33.8 → 80 은 0.35 초쯤: " + rise * DT);
		for (int i = 0; i < 30; i++) {
			tick(nav, in);
		}
		assertEquals(80, g.slideY, 1e-4f, "끝에서 멈춤");
		assertEquals(0, g.slideVy);
		assertNull(items.grabbable(b.pos, HeroNavigator.forward(b.yaw), 32), "다 든 문은 다시 잡히지 않음");
	}

	/** 다 들기 전에 놓으면 위치·속도가 곧바로 0 (0x2142B8) 이고 문은 중력으로 처음 자리까지 떨어진다. */
	@Test
	void releasedGateDropsBack() {
		SlideBody b = new SlideBody();
		Interactables items = new Interactables();
		Interactables.Item g = gate(items);
		HeroNavigator nav = nav(b, items);
		HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
		in.down.add(PadButton.USE);
		tick(nav, in);
		for (int i = 0; i < 90; i++) {
			if (i % 6 == 0) {
				in.down.add(PadButton.USE);
			}
			tick(nav, in);
		}
		float lifted = g.slideY;
		assertTrue(lifted > 3, "1.5 초 들었음: " + lifted);
		// R2 를 뗀 채 스틱을 문 반대쪽 (+z) 으로 → 놓음 (0x1C4B48)
		in.ly = -1;
		tick(nav, in);
		in.ly = 0;
		assertNotEquals(NavState.INTERACT, nav.state(), "놓음");
		tick(nav, in);
		assertEquals(0, g.task().time, "놓인 미끄럼 문은 위치 0");
		int fall = 0;
		while (g.slideY > 0 && fall < 120) {
			tick(nav, in);
			fall++;
		}
		assertEquals(0, g.slideY, 1e-4f, "처음 자리까지 내려옴");
		assertEquals(0, g.slideVy);
		float expect = (float) Math.sqrt(2 * lifted / HeroNavigator.SLIDE_GRAVITY);
		assertEquals(expect, fall * DT, 0.1f, "낙하 시간 ≈ √(2h/800)");
		assertFalse(g.done);
	}

	/** 스크립트 1015 (IO_TandF 메시지 0x266420 → 0x213E30): 위치 0 → 다 오른 문이 처음 자리로 떨어지고 다시 잡힌다. ATHN04C DoorVolume 이탈. */
	@Test
	void resetDropsRaisedGateAndMakesItGrabbable() {
		SlideBody b = new SlideBody();
		Interactables items = new Interactables();
		Interactables.Item g = gate(items);
		HeroNavigator nav = nav(b, items);
		g.task().time = 1;
		g.task().done = true;
		g.done = true;
		g.slideY = 80;
		HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
		nav.scriptReset(g);
		assertFalse(g.done);
		assertEquals(0, g.task().time);
		for (int i = 0; i < 60; i++) {
			tick(nav, in);
		}
		assertEquals(0, g.slideY, 1e-4f, "80 에서 떨어져 0.45 초 안에 처음 자리");
		assertNotNull(items.grabbable(b.pos, HeroNavigator.forward(b.yaw), 32), "다시 잡힘 (잡기 클래스 0x80200000)");
	}

	/** 데이터: 리프트 게이트는 TAndF 플래그 비트 0 (미끄럼), 함정문 HatchDoor 는 아님 (오브젝트 애니 trapDoorTandF/trapOpen). */
	@Test
	void liftGatesAreSlideDoorsInData() throws Exception {
		check("ATHN04C", "goliftdoor13", 7, true, 80);
		check("ATHN04A", "goliftdoor13", 400, true, 80);
		check("ATHN04A", "gotrapdoor2", 6, false, 48);
		check("ATHN01C", "goliftdoor1", 50, true, 80);
	}

	private static void check(String level, String owner, int template, boolean slide, float height) throws Exception {
		LevelData d = NavDataTest.level(level);
		Tweaks tw = new Tweaks();
		tw.load(d.wad);
		Interactables.Item it = Interactables.of(d, tw).byOwner(owner);
		assertNotNull(it, level + " " + owner);
		assertEquals(Interactables.Kind.LIFT, it.kind);
		assertEquals(template, it.template, owner);
		assertEquals(slide, it.slide, owner + " 플래그 비트 0");
		assertEquals(height, it.liftHeight, 1e-3f, owner + " 높이 #8743c048 × 16");
	}

	/** 영웅 liftDoor2 의 synchJoint 는 0 → 33.8 (끝), liftDoor3 끝 54.5 — 세운 애니는 update 로 진행하지 않는다 (0x100510 속도 0). */
	@Test
	void heroSynchJointAndScrub() throws Exception {
		WadFile w = WadFile.load(NavDataTest.rom().resolve("R_HERO0.WAD"));
		Skeleton sk = Skeleton.loadAll(w).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(w, "ANM_hero");
		Tweaks tw = new Tweaks();
		Animator a = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController h = new HeroController(a);
		float len = h.clipLength("liftDoor2");
		assertEquals(5.467f, len, 0.01f);
		h.scrub("liftDoor2", 0);
		for (int i = 0; i < 20; i++) {
			a.update(DT); // 크로스페이드가 끝나도록
		}
		assertEquals(0, a.time(), 1e-6f, "세운 클립은 시간이 흐르지 않음");
		assertEquals(-0.1f, h.jointModelY(Interactables.SYNCH_JOINT), 0.2f);
		h.scrub("liftDoor2", len);
		a.update(DT);
		assertEquals(len, a.time(), 1e-6f);
		assertEquals(33.8f, h.jointModelY(Interactables.SYNCH_JOINT), 0.2f, "다 든 순간 문 높이 (처음 + 33.8)");
		h.play("liftDoor3", true);
		for (int i = 0; i < 40; i++) {
			a.update(DT);
		}
		assertTrue(a.time() > 0.5f, "다른 클립은 다시 흐름");
		assertTrue(Set.of("liftDoor3").contains(a.current().name));
	}
}
