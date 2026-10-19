package devple.gow.engine.nav;

import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.level.RopeDef;
import devple.gow.tools.level.SurfaceAttr;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 합성 충돌 위에서 내비게이션 상태 기계 (38 문서 §2·§3) — GL·게임 데이터 없이. */
class HeroNavigatorTest {

	static final float DT = 1 / 60f;

	/** 시험용 패드: 스틱 고정 + 이번 틱 누른 버튼. */
	static final class Pad extends Input {
		float lx, ly;
		final Set<PadButton> down = EnumSet.noneOf(PadButton.class);

		Pad() {
			super(0);
		}

		@Override
		public boolean pressed(PadButton b) {
			return down.contains(b);
		}

		@Override
		public boolean held(PadButton b) {
			return down.contains(b);
		}

		@Override
		public float leftX() {
			return lx;
		}

		@Override
		public float leftY() {
			return ly;
		}
	}

	/** 몸 + 아주 단순한 지상/공중 물리 (게임 씬 대신). */
	static final class Body implements NavBody {
		final Vector3f pos = new Vector3f();
		final Vector3f launch = new Vector3f();
		float yaw, vy;
		boolean grounded = true;
		String anim = "";
		String walk;
		final List<String> played = new ArrayList<>();

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
				played.add(a);
			}
			anim = a;
		}

		public boolean animFinished() {
			return true;
		}

		public void locomotion(String w, String idle, float s) {
			walk = w;
		}
	}

	static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** 사각형 (a b c d 순, 법선 = (b-a)×(c-a)) → 삼각형 2. */
	static void quad(List<LevelCollision.Tri> out, Vector3f a, Vector3f b, Vector3f c, Vector3f d, SurfaceAttr... at) {
		out.add(LevelCollision.Tri.of(a, b, c, at));
		out.add(LevelCollision.Tri.of(a, c, d, at));
	}

	/** y 높이의 수평 바닥 (위 법선). */
	static void floor(List<LevelCollision.Tri> out, float x0, float z0, float x1, float z1, float y, SurfaceAttr... at) {
		quad(out, v(x0, y, z0), v(x0, y, z1), v(x1, y, z1), v(x1, y, z0), at);
	}

	/** z = zw 의 벽 (법선 +z), x0..x1, y0..y1. */
	static void wallZ(List<LevelCollision.Tri> out, float zw, float x0, float x1, float y0, float y1, SurfaceAttr... at) {
		quad(out, v(x0, y0, zw), v(x1, y0, zw), v(x1, y1, zw), v(x0, y1, zw), at);
	}

	/** 내비게이터 + 단순 물리 한 틱. */
	static void tick(HeroNavigator nav, Body b, NavWorld w, Pad in) {
		if (!nav.update(in, 0, DT) && !nav.active()) {
			Vector3f p = b.pos;
			if (b.grounded && in.pressed(PadButton.CROSS)) {
				b.vy = HeroNavigator.JUMP;
				b.grounded = false;
			}
			float mag = (float) Math.hypot(in.lx, in.ly);
			if (mag > 0.2f) {
				float dirYaw = (float) Math.atan2(-in.lx, in.ly);
				b.yaw = dirYaw;
				p.add(HeroNavigator.forward(dirYaw).mul(60 * DT));
			}
			p.fma(DT, b.launch);
			float g = w.groundHeight(p.x, p.y + 14, p.z, 4000);
			if (b.grounded && !Float.isNaN(g) && p.y - g < 16) {
				p.y = g;
			} else {
				b.vy -= HeroNavigator.GRAVITY * DT;
				p.y += b.vy * DT;
				b.grounded = false;
				if (!Float.isNaN(g) && p.y <= g) {
					p.y = g;
					b.vy = 0;
					b.grounded = true;
					b.launch.zero();
				}
			}
		}
		in.down.clear();
	}

	@Test
	void climbClimbableWallAndPullUpOnTop() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -20, 200, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, 0, 100, SurfaceAttr.CLIMBABLE);
		floor(t, -200, -300, 200, -20, 100, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		in.ly = 1;
		in.down.add(PadButton.CROSS);
		b.pos.set(0, 0, -2);
		tick(nav, b, w, in);
		assertEquals(NavState.WALL_CLIMB, nav.state(), "벽 쪽 스틱 + × → 벽 오르기");
		assertEquals(-20 + HeroNavigator.WALL_OFF, b.pos.z, 0.01f);
		for (int i = 0; i < 600 && nav.state() == NavState.WALL_CLIMB; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		assertEquals(NavState.GROUND, nav.state(), "꼭대기에서 올라섬");
		assertEquals(100, b.pos.y, 0.01f);
		assertTrue(b.pos.z < -20, "벽 너머 위 바닥 " + b.pos);
		assertTrue(b.played.contains("wClimbBlend0") && b.played.contains("wallpullup"), b.played.toString());
	}

	@Test
	void nonClimbableWallDoesNotClimb() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -200, 200, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, 0, 300, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		in.ly = 1;
		in.down.add(PadButton.CROSS);
		b.pos.set(0, 0, -2);
		assertFalse(nav.update(in, 0, DT));
		assertEquals(NavState.GROUND, nav.state());
	}

	@Test
	void ledgeGrabWhileFallingThenPullUp() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -20, 200, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, 0, 40, SurfaceAttr.GROUND);
		floor(t, -200, -300, 200, -20, 40, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		b.grounded = false;
		b.vy = -10;
		b.pos.set(0, 12, -4);
		tick(nav, b, w, in);
		assertEquals(NavState.LEDGE_HANG, nav.state(), "하강 중 손 높이 턱 → 매달림");
		assertEquals(40 - HeroNavigator.HANG, b.pos.y, 0.01f);
		assertEquals("WallhangCatch", b.anim);
		// 좌우 이동 (ANIWallHangR)
		in.lx = 1;
		float x0 = b.pos.x;
		for (int i = 0; i < 60; i++) {
			in.lx = 1;
			tick(nav, b, w, in);
		}
		assertTrue(b.pos.x > x0 + 10, "턱 따라 이동 " + b.pos);
		assertEquals("WallhangR", b.anim);
		in.lx = 0;
		in.down.add(PadButton.CROSS);
		tick(nav, b, w, in);
		assertEquals(NavState.GROUND, nav.state());
		assertEquals(40, b.pos.y, 0.01f);
	}

	@Test
	void ladderClimbsToTop() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -20, 200, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -30, 30, 0, 160, SurfaceAttr.LADDER);
		floor(t, -200, -300, 200, -20, 160, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		in.ly = 1;
		b.pos.set(0, 0, -4);
		tick(nav, b, w, in);
		assertEquals(NavState.LADDER, nav.state(), "사다리는 스틱만으로 붙는다");
		for (int i = 0; i < 900 && nav.state() == NavState.LADDER; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		assertEquals(NavState.GROUND, nav.state());
		assertEquals(160, b.pos.y, 0.01f);
		assertTrue(b.played.contains("ladderUp") && b.played.contains("dismount1"), b.played.toString());
	}

	@Test
	void ziplineCarriesHeroToTheLowEnd() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -100, -100, 600, 100, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		w.ropes().add(new NavRope("zip", RopeDef.Type.ZIPLINE, 3, 90, v(0, 200, 0), v(500, 40, 0)));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		b.grounded = false;
		b.pos.set(2, 200 - HeroNavigator.ROPE_HAND, 0);
		tick(nav, b, w, in);
		assertEquals(NavState.ZIPLINE, nav.state());
		assertEquals("zipline", b.anim);
		for (int i = 0; i < 1200 && nav.state() != NavState.GROUND; i++) {
			tick(nav, b, w, in);
			if (nav.state() == NavState.AIR && b.grounded) {
				break;
			}
		}
		assertTrue(b.grounded, "끝에서 놓고 착지");
		assertTrue(b.pos.x > 480, "끝점 근처 " + b.pos);
		assertEquals(0, b.pos.y, 0.01f);
	}

	@Test
	void hangingRopeClimbAndSwing() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		w.ropes().add(new NavRope("rope", RopeDef.Type.HANG, 0, 40, v(0, 300, 0), v(0, 20, 0)));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		b.pos.set(4, 0, 0);
		in.down.add(PadButton.CROSS);
		tick(nav, b, w, in);
		assertEquals(NavState.ROPE, nav.state(), "지상 + × 로 줄 잡기");
		float y0 = b.pos.y;
		for (int i = 0; i < 120; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		assertTrue(b.pos.y > y0 + 40, "줄 오르기 " + y0 + " → " + b.pos.y);
		assertEquals("ropeUp", b.anim);
		in.ly = 0;
		for (int i = 0; i < 180; i++) {
			in.lx = 1;
			tick(nav, b, w, in);
		}
		assertTrue(Math.abs(b.pos.x) > 20, "흔들기 " + b.pos);
		in.lx = 0;
		in.down.add(PadButton.CROSS);
		tick(nav, b, w, in);
		assertEquals(NavState.AIR, nav.state(), "뛰어내림");
	}

	@Test
	void narrowFloorIsBalanceWalkAndDeathSurfaceNotifies() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -10, -200, 10, 200, 0, SurfaceAttr.GROUND, SurfaceAttr.NARROW);
		floor(t, 100, -200, 200, 200, 0, SurfaceAttr.DEATH);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		boolean[] died = {false};
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void deathSurface() {
				died[0] = true;
			}
		});
		Pad in = new Pad();
		tick(nav, b, w, in);
		assertTrue(nav.balancing());
		assertEquals("balanceWblend1", b.walk);
		b.pos.set(150, 0, 0);
		tick(nav, b, w, in);
		assertFalse(nav.balancing());
		assertNull(b.walk);
		assertTrue(died[0]);
	}

	@Test
	void waterSwimsToShore() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -200, 100, 200, 0, SurfaceAttr.WATER);
		floor(t, 100, -200, 300, 200, -2, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		tick(nav, b, w, in);
		assertEquals(NavState.SWIM, nav.state());
		assertEquals(-HeroNavigator.SWIM_DEPTH, b.pos.y, 0.01f);
		// +x 로 (camYaw 0: 스틱 오른쪽 = +x)
		for (int i = 0; i < 600 && nav.state() == NavState.SWIM; i++) {
			in.lx = 1;
			tick(nav, b, w, in);
		}
		assertEquals(NavState.GROUND, nav.state(), "물가로 올라섬");
		assertEquals(-2, b.pos.y, 0.01f);
		assertTrue(b.played.contains("swim"));
	}

	@Test
	void pushBoxMovesAndPostsAttachDetach() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item box = items.add(new Interactables.Item(Interactables.Kind.PUSH, "gopbox", v(0, 0, -40), 225, 503, "pushIdle", null,
				"Pandora's Box", 16));
		nav.setItems(items);
		List<String> ev = new ArrayList<>();
		Vector3f moved = new Vector3f();
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void pushAttach(Interactables.Item it) {
				ev.add("attach " + it.id);
			}

			@Override
			public void pushDetach(Interactables.Item it) {
				ev.add("detach " + it.id);
			}

			@Override
			public void pushMoved(Interactables.Item it, Vector3f d) {
				moved.add(d);
			}
		});
		Pad in = new Pad();
		in.down.add(PadButton.R1);
		tick(nav, b, w, in);
		assertEquals(NavState.PUSH, nav.state());
		for (int i = 0; i < 60; i++) {
			in.ly = 1; // camYaw 0: 스틱 위 = -z = 상자 쪽
			tick(nav, b, w, in);
		}
		assertTrue(box.pos.z < -60, "상자 밀림 " + box.pos);
		assertEquals(box.pos.z + 40, moved.z, 0.01f);
		assertEquals(box.radius + HeroNavigator.BODY, b.pos.z - box.pos.z, 0.5f); // 영웅은 상자 뒤에서 같이 움직임
		in.ly = 0;
		in.down.add(PadButton.R1);
		tick(nav, b, w, in);
		assertEquals(List.of("attach 503", "detach 503"), ev);
		assertEquals(NavState.GROUND, nav.state());
	}

	@Test
	void pushBlockedByWall() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		wallZ(t, -70, -200, 200, 0, 100, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item box = items.add(new Interactables.Item(Interactables.Kind.PUSH, "box", v(0, 0, -40), 1, 1, "pushIdle", null, "box", 16));
		nav.setItems(items);
		Pad in = new Pad();
		in.down.add(PadButton.R1);
		tick(nav, b, w, in);
		for (int i = 0; i < 240; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		assertTrue(box.pos.z > -70 + 16 * 0.8f - 1, "벽에서 멈춤 " + box.pos);
	}

	@Test
	void csmRepeatedPressPostsTaskProgressUntilDone() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item door = items.add(new Interactables.Item(Interactables.Kind.CSM, "gotwinkickdoor", v(0, 0, -30), 44, 206, "doorKick", null,
				"HydraKickDoor", 20));
		nav.setItems(items);
		List<int[]> progress = new ArrayList<>();
		boolean[] done = {false};
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void taskProgress(Interactables.Item it, int pct) {
				progress.add(new int[]{pct, it.id});
			}

			@Override
			public void interactDone(Interactables.Item it) {
				done[0] = true;
			}
		});
		Pad in = new Pad();
		for (int i = 0; i < 400 && !done[0]; i++) {
			if (i % 10 == 0) {
				in.down.add(PadButton.R1);
			}
			tick(nav, b, w, in);
		}
		assertTrue(done[0]);
		assertEquals("doorKick", b.played.getFirst());
		assertTrue(progress.stream().anyMatch(p -> p[0] >= 30 && p[1] == 206), "TwinKickDoor 조건 (≥ 30 %, 과제 206)");
		assertEquals(100, progress.getLast()[0]);
		assertTrue(progress.stream().noneMatch(p -> p[0] == 100 && p != progress.getLast()));
		// 끝난 오브젝트는 다시 잡히지 않는다
		for (int i = 0; i < 200; i++) {
			tick(nav, b, w, in);
		}
		in.down.add(PadButton.R1);
		tick(nav, b, w, in);
		assertEquals(NavState.GROUND, nav.state());
	}

	@Test
	void csmProgressRewindsWhenIdle() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item it = items.add(new Interactables.Item(Interactables.Kind.CSM, "door", v(0, 0, -30), 1, 7, "doorKick", null, "d", 20));
		nav.setItems(items);
		Pad in = new Pad();
		in.down.add(PadButton.R1);
		tick(nav, b, w, in);
		in.down.add(PadButton.R1);
		tick(nav, b, w, in);
		float p0 = it.progress;
		for (int i = 0; i < 120; i++) {
			tick(nav, b, w, in);
		}
		assertTrue(it.progress < p0, "누르지 않으면 되감김");
	}

	/** 레버 (PushPull): 붙잡기 1048 → 레버 애니가 끝날 때까지 1023 (인자 1 = PushPull id, 원작 0x2118E8) → 놓기 1049. PAND00C 엘리베이터 레버 id 111. */
	@Test
	void leverPostsTaskProgressWithItsId() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item lever = items.add(new Interactables.Item(Interactables.Kind.LEVER, "goelevatorlever", v(0, 0, -30), 12, 111, "leverpull",
				null, "newLeever", 20));
		lever.length = 0.5f;
		nav.setItems(items);
		List<String> log = new ArrayList<>();
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void taskProgress(Interactables.Item it, TaskProgress.Event e) {
				log.add("1023 " + e.percent() + " " + e.taskId() + " " + e.speed100());
			}

			@Override
			public void pushAttach(Interactables.Item it) {
				log.add("1048 " + it.id);
			}

			@Override
			public void pushDetach(Interactables.Item it) {
				log.add("1049 " + it.id);
			}
		});
		Pad in = new Pad();
		in.down.add(PadButton.R1);
		tick(nav, b, w, in);
		for (int i = 0; i < 120 && !lever.done; i++) {
			tick(nav, b, w, in);
		}
		assertTrue(lever.done, log.toString());
		assertEquals("1048 111", log.getFirst());
		assertEquals("1049 111", log.getLast());
		assertTrue(log.contains("1023 100 111 100"), "완료 100 % (ElevatorLever-LeverProgress 조건): " + log);
		assertEquals(NavState.GROUND, nav.state());
	}
}
