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

		/** 영웅 클립 길이 (초) — 시험이 정한다. */
		final java.util.Map<String, Float> clips = new java.util.HashMap<>();

		@Override
		public float clipLength(String a) {
			return clips.getOrDefault(a, -1f);
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
		b.pos.set(0, 0, -8); // 지상 벽 질의는 정면 14.4 (0x1E48E4) — 게임 몸 구 반지름 12 만큼 벽에서 떨어진 자리
		tick(nav, b, w, in);
		assertEquals(NavState.WALL_CLIMB, nav.state(), "벽 쪽 스틱 + × → 벽 오르기");
		assertEquals(-20 + HeroNavigator.WALL_OFF, b.pos.z, 0.01f);
		for (int i = 0; i < 600 && nav.state() == NavState.WALL_CLIMB; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		// 원작 0x1C78FC: 올라가다 손 높이에 턱 (0x1E3A80) → 턱 매달리기 (0x1C75F8), 발 = 턱 − 35.2
		assertEquals(NavState.LEDGE_HANG, nav.state(), "꼭대기 턱에 매달림");
		assertEquals(100 - HeroNavigator.HANG, b.pos.y, 0.01f);
		in.ly = 1;
		tick(nav, b, w, in);
		assertEquals(NavState.GROUND, nav.state(), "꼭대기에서 올라섬");
		assertEquals(100, b.pos.y, 0.01f);
		assertTrue(b.pos.z < -20, "벽 너머 위 바닥 " + b.pos);
		assertTrue(b.played.contains("wClimbBlend0") && b.played.contains("WallhangCatch") && b.played.contains("wallpullup"), b.played.toString());
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
		b.pos.set(0, 4, -10); // 손 (발 + 35.2) 이 턱 40 을 막 지남, 턱 탐색 점 = 정면 12.8 (벽 너머 2.8)
		tick(nav, b, w, in);
		assertEquals(NavState.LEDGE_HANG, nav.state(), "하강 중 손 높이 턱 → 매달림");
		assertEquals(-20 + HeroNavigator.WALL_OFF, b.pos.z, 0.01f);
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
		b.pos.set(0, 0, -8);
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
		// 2 초 × 24 = 48 오르지만 그동안 영웅 무게 (0 → 100, 0x217C48) 로 줄이 늘어나 ≈ 15 처짐 (RopeSim)
		assertTrue(b.pos.y > y0 + 25, "줄 오르기 " + y0 + " → " + b.pos.y);
		assertEquals("ropeUp", b.anim);
		in.ly = 0;
		// 흔들기 = ○ 누르고 있기 (+0x310 0x10) + 정면 (스틱 +x): 움직이는 쪽으로, 아래쪽 반에서만 500 × 거리 (0x1C6DD4)
		float maxX = 0;
		for (int i = 0; i < 300; i++) {
			in.lx = 1;
			in.down.add(PadButton.CIRCLE);
			tick(nav, b, w, in);
			maxX = Math.max(maxX, Math.abs(b.pos.x));
		}
		assertTrue(maxX > 20, "흔들기 " + maxX);
		assertEquals("ropeswingidle", b.anim);
		// ○ 를 놓으면 감쇠 (Global Damping) 로 흔들림이 준다
		for (int i = 0; i < 600; i++) {
			in.lx = 0;
			tick(nav, b, w, in);
		}
		float late = 0;
		for (int i = 0; i < 120; i++) {
			tick(nav, b, w, in);
			late = Math.max(late, Math.abs(b.pos.x));
		}
		assertTrue(late < maxX * 0.5f, "놓으면 줄어듦 " + late + " / " + maxX);
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
		assertTrue(b.played.contains("treadswim"), b.played.toString());
	}

	/** 좁은 턱 (Narrow 벽): 앞으로 걸어 들어가면 벽 보고 걷기 (0x1C7DE8 → 0x1C7280 0x100000), 좌우 Sold+0x168 1.25 m/s, 턱 끝에서 떨어짐. */
	@Test
	void narrowWallWalkSidewaysThenDropsAtEnd() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -300, -20, 300, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, 0, 100, SurfaceAttr.NARROW);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		b.pos.set(0, 0, -8);
		in.ly = 1;
		tick(nav, b, w, in);
		assertEquals(NavState.WALL_WALK, nav.state(), "앞으로 걸어 좁은 턱 벽에 붙음 (× 불필요)");
		assertFalse(nav.wallBack());
		assertEquals(-20 + HeroNavigator.WALL_OFF, b.pos.z, 0.01f);
		assertEquals(0, b.pos.y, 0.01f, "발 = 질의가 맞춘 바닥");
		assertEquals("wallFrontI", b.anim);
		for (int i = 0; i < 60; i++) {
			in.lx = 0.4f; // 스틱 크기 무관
			tick(nav, b, w, in);
		}
		assertEquals(NavState.WALL_WALK, nav.state());
		assertEquals(20f, HeroNavigator.LEDGE_WALK_SPEED, 1e-4f);
		assertEquals(20, b.pos.x, 0.05f, "1 초에 20 (0x1C8CBC)");
		assertEquals(-20 + HeroNavigator.WALL_OFF, b.pos.z, 0.01f);
		assertEquals("wallFrontR", b.anim);
		for (int i = 0; i < 600 && nav.state() == NavState.WALL_WALK; i++) {
			in.lx = 1;
			tick(nav, b, w, in);
		}
		assertNotEquals(NavState.WALL_WALK, nav.state(), "턱 끝 (오른쪽 선분이 벽을 벗어남) → 떨어짐");
		assertTrue(b.pos.x > 100 - HeroNavigator.WALL_SPREAD - 1 && b.pos.x < 100, "끝 x " + b.pos.x);
	}

	/** BackPress 벽: 착지한 프레임에 등 뒤 질의 (0x1C7ED0) → 등 대고 걷기 (0x200000, ANIWallBack*), 좌우는 몸 기준. */
	@Test
	void backPressWallOnLanding() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -300, -20, 300, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, 0, 60, SurfaceAttr.BACK_PRESS);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		b.yaw = (float) Math.PI; // 정면 +z = 벽 반대
		b.grounded = false;
		b.pos.set(0, 1, -8);
		b.vy = -100;
		tick(nav, b, w, in);
		assertTrue(b.grounded);
		tick(nav, b, w, in);
		assertEquals(NavState.WALL_WALK, nav.state());
		assertTrue(nav.wallBack());
		assertEquals("wallBackI", b.anim);
		assertEquals(Math.PI, Math.abs(b.yaw), 1e-4, "등을 벽에 (정면 = 법선 +z)");
		for (int i = 0; i < 30; i++) {
			in.lx = 1; // 카메라 기준 +x → 몸 기준 왼쪽 (정면 +z, 오른쪽 −x)
			tick(nav, b, w, in);
		}
		assertEquals(10, b.pos.x, 0.05f);
		assertEquals("wallBackL", b.anim);
	}

	/** 지상에서 앞으로 걸어 Climbable 벽에 붙음 (0x1C7DE8, × 불필요), 옆 끝에서 막힘 (반환 3 → 0x100, wallClimbAL) → × = 90° 옆 점프 (위 192 · 앞 160). */
	@Test
	void climbWallEdgeBlocksThenSideJump() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -300, -20, 300, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -50, 50, 0, 200, SurfaceAttr.CLIMBABLE);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		b.pos.set(0, 0, -8);
		in.ly = 1;
		tick(nav, b, w, in);
		assertEquals(NavState.WALL_CLIMB, nav.state(), "스틱만으로 벽 오르기");
		for (int i = 0; i < 20; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		for (int i = 0; i < 300 && nav.wallEdge() == 0; i++) {
			in.lx = -1;
			in.ly = 0;
			tick(nav, b, w, in);
		}
		float y = b.pos.y;
		assertEquals(-1, nav.wallEdge(), "왼쪽 끝");
		float x = b.pos.x;
		// 왼쪽 선분 (o → o − 오른쪽 × 8 + 정면 × 14.4) 은 벽면 (9.6 앞) 에서 옆으로 8 × 9.6 / 14.4 = 5.33
		float reach = HeroNavigator.WALL_SPREAD * HeroNavigator.WALL_OFF / HeroNavigator.WALL_PROBE;
		assertEquals(-50 + reach, x, 0.5f, "끝 x");
		for (int i = 0; i < 10; i++) {
			in.lx = -1;
			in.ly = 0.5f;
			tick(nav, b, w, in);
		}
		assertEquals(x, b.pos.x, 1e-4f, "막힌 쪽으로 밀면 그 자리 (대각선도)");
		assertEquals(y, b.pos.y, 1e-4f);
		assertEquals("wallClimbAL", b.anim);
		in.down.add(PadButton.CROSS);
		tick(nav, b, w, in);
		assertEquals(NavState.AIR, nav.state());
		assertEquals(192f, HeroNavigator.EDGE_JUMP_UP, 1e-4f);
		assertEquals(HeroNavigator.EDGE_JUMP_UP, b.vy, 1e-3f);
		assertEquals(-HeroNavigator.EDGE_JUMP_OUT, b.launch.x, 1e-3f, "막힌 쪽 (−x) 으로 160");
		assertEquals(0, b.launch.z, 1e-3f);
		assertEquals(-1, HeroNavigator.forward(b.yaw).x, 1e-4f);
	}

	/** 깊은 물 (수면 0, 바닥 −300) + 동쪽 물가 없음. */
	static NavWorld deepPool(SurfaceAttr... surface) {
		List<LevelCollision.Tri> t = new ArrayList<>();
		SurfaceAttr[] at = surface.length == 0 ? new SurfaceAttr[] {SurfaceAttr.WATER} : surface;
		floor(t, -400, -400, 400, 400, 0, at);
		floor(t, -400, -400, 400, 400, -300, SurfaceAttr.GROUND);
		return new NavWorld(LevelCollision.of(t));
	}

	/** 물 위 떠 있기 (0x80): 목표 Sold+0x1D8 4.5 m/s, × = 점프 (수직 244), 얕은 물 (수면 아래 28.16 안 바닥) 은 걷는다. */
	@Test
	void treadSpeedJumpAndShallowWater() {
		NavWorld w = deepPool();
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		tick(nav, b, w, in);
		assertEquals(NavState.SWIM, nav.state());
		assertEquals(HeroNavigator.Water.TREAD, nav.water());
		assertEquals(-HeroNavigator.SWIM_DEPTH, b.pos.y, 1e-3f, "떠 있는 발 = 수면 − 키 × 0.8 (0x1E57D8)");
		for (int i = 0; i < 120; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		assertEquals(72f, HeroNavigator.TREAD_SPEED, 1e-3f);
		assertEquals(HeroNavigator.TREAD_SPEED, nav.swimSpeed(), 1e-3f, "물 위는 7.5 가 아니라 4.5 m/s");
		assertEquals("treadswim", b.anim);
		in.down.add(PadButton.CROSS);
		tick(nav, b, w, in);
		assertEquals(NavState.AIR, nav.state(), "물 위에서 × = 점프 (0x1E9C2C)");
		assertEquals(HeroNavigator.JUMP, b.vy, 1e-3f);
		assertEquals("jumpAir", b.anim);
		// 얕은 물: 수면 0 아래 20 에 바닥 → 서서 걷는다
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -100, -100, 100, 100, 0, SurfaceAttr.WATER);
		floor(t, -100, -100, 100, 100, -20, SurfaceAttr.GROUND);
		NavWorld sw = new NavWorld(LevelCollision.of(t));
		Body b2 = new Body();
		HeroNavigator nav2 = new HeroNavigator(b2, sw, null);
		tick(nav2, b2, sw, new Pad());
		assertEquals(NavState.GROUND, nav2.state());
	}

	/** 물에 떨어짐 (0x1CA098): 떠 있는 높이까지 공중 낙하 → 수직 속도 max(× 0.4, −64) → 부력 15 m/s² 로 떠올라 TREAD. */
	@Test
	void fallIntoWaterSinksThenFloatsUp() {
		NavWorld w = deepPool();
		Body b = new Body();
		b.grounded = false;
		b.pos.set(0, 100, 0);
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		for (int i = 0; i < 120 && nav.state() != NavState.SWIM; i++) {
			tick(nav, b, w, in);
		}
		assertEquals(NavState.SWIM, nav.state());
		assertEquals(HeroNavigator.Water.SINK, nav.water());
		float lowest = b.pos.y;
		boolean damped = false;
		for (int i = 0; i < 240 && nav.water() != HeroNavigator.Water.TREAD; i++) {
			tick(nav, b, w, in);
			lowest = Math.min(lowest, b.pos.y);
			if (b.pos.y < -HeroNavigator.SWIM_DEPTH && !damped) {
				damped = true;
				assertTrue(nav.waterVy() >= HeroNavigator.ENTRY_MIN_VY - 1e-3f, "들어갈 때 −64 로 자름: " + nav.waterVy());
			}
		}
		assertTrue(damped);
		assertEquals(HeroNavigator.Water.TREAD, nav.water());
		assertEquals(-HeroNavigator.SWIM_DEPTH, b.pos.y, 1e-3f);
		// 판정 높이를 지나는 프레임은 낙하 속도 그대로 (원작도 이동 뒤 질의, ≤ 450/60 = 7.5) + −64 에서 부력 240 → 64² / 480 ≈ 8.5
		assertTrue(lowest < -HeroNavigator.SWIM_DEPTH - 8 && lowest > -HeroNavigator.SWIM_DEPTH - 8.5f - 8, "가장 깊은 발 " + lowest);
		assertEquals(240f, HeroNavigator.BUOYANCY, 1e-3f);
	}

	/** 잠수 (0x200): □ 아래 / △ 위 (Sold+0x1D4 7.5 m/s), 수면에 60 이상으로 닿으면 물 밖 도약 (0x1C9750: × min(2, 366 / vy)). */
	@Test
	void diveThenLeapOutOfWater() {
		NavWorld w = deepPool();
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		tick(nav, b, w, in);
		for (int i = 0; i < 90; i++) {
			in.down.add(PadButton.SQUARE);
			tick(nav, b, w, in);
		}
		assertEquals(HeroNavigator.Water.DIVE, nav.water());
		assertEquals(-HeroNavigator.SWIM_SPEED, nav.waterVy(), 1e-3f, "아래 7.5 m/s");
		assertTrue(b.pos.y < -100, "잠수 깊이 " + b.pos.y);
		assertTrue(b.played.contains("swim"), b.played.toString());
		for (int i = 0; i < 600 && nav.state() == NavState.SWIM; i++) {
			in.down.add(PadButton.TRIANGLE);
			tick(nav, b, w, in);
		}
		assertEquals(NavState.AIR, nav.state(), "물 밖 도약");
		assertEquals(HeroNavigator.SWIM_SPEED * 2, b.vy, 1e-3f, "min(2, 244 × 1.5 / 120) = 2 배");
		assertTrue(nav.divingIn());
		assertEquals("swimdive", b.anim);
		// 천천히 올라오면 (위 버튼 없이 부력 없음 → 수직 0) 잠수 상태 그대로, 수면에 닿으면 TREAD
		Body b2 = new Body();
		HeroNavigator nav2 = new HeroNavigator(b2, w, null);
		tick(nav2, b2, w, in);
		in.down.add(PadButton.SQUARE);
		tick(nav2, b2, w, in);
		assertEquals(HeroNavigator.Water.DIVE, nav2.water());
		for (int i = 0; i < 120; i++) {
			tick(nav2, b2, w, in);
		}
		assertEquals(HeroNavigator.Water.DIVE, nav2.water(), "잠수 중 부력 없음");
		assertEquals(0, nav2.waterVy(), 1e-3f);
	}

	/** 잠수 돌진 (R1, 0x1C9E70): 1 초 충전 → 22 m/s (352), 조종 시간 0.1 + 1 초 뒤 끝. 0.5 초 이하 충전은 돌진 없음. */
	@Test
	void underwaterDashChargesAndReleases() {
		NavWorld w = deepPool();
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		tick(nav, b, w, in);
		in.down.add(PadButton.SQUARE);
		tick(nav, b, w, in);
		for (int i = 0; i < 20; i++) {
			tick(nav, b, w, in);
		}
		for (int i = 0; i < 25; i++) {
			in.down.add(PadButton.R1);
			tick(nav, b, w, in);
		}
		tick(nav, b, w, in);
		assertFalse(nav.dashing(), "0.4 초 충전은 돌진 없음");
		for (int i = 0; i < 61; i++) {
			in.down.add(PadButton.R1);
			tick(nav, b, w, in);
			assertTrue(nav.dashCharging());
		}
		assertEquals("swimDashPU", b.anim);
		tick(nav, b, w, in);
		assertTrue(nav.dashing());
		assertEquals(352f, HeroNavigator.DASH_MAX, 1e-3f);
		assertEquals(HeroNavigator.DASH_MAX, nav.swimSpeed(), 2f, "충전 1 초 → 22 m/s");
		assertEquals("swimdash", b.anim);
		for (int i = 0; i < 70; i++) {
			tick(nav, b, w, in);
		}
		assertFalse(nav.dashing(), "조종 시간 1.1 초 뒤 끝");
		assertTrue(nav.swimSpeed() < HeroNavigator.DASH_MAX - 100, "감속 " + nav.swimSpeed());
	}

	/** 물 위에서 앞으로 가다 Climbable 벽 → 벽 오르기 (0x1C7DA8), 높은 곳에서 깊은 물로 떨어지면 다이빙 자세 (0x1CA318), NoDiving 물은 안 함. */
	@Test
	void treadClimbsWallAndAirDivePose() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -20, 400, 400, 0, SurfaceAttr.WATER);
		floor(t, -400, -20, 400, 400, -300, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, -200, 100, SurfaceAttr.CLIMBABLE);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		b.pos.set(0, 0, 40);
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		for (int i = 0; i < 120 && nav.state() != NavState.WALL_CLIMB; i++) {
			in.ly = 1;
			tick(nav, b, w, in);
		}
		assertEquals(NavState.WALL_CLIMB, nav.state(), "물 위에서 벽 오르기");
		for (SurfaceAttr[] at : new SurfaceAttr[][] {{SurfaceAttr.WATER}, {SurfaceAttr.WATER, SurfaceAttr.NO_DIVING}}) {
			NavWorld pw = deepPool(at);
			Body d = new Body();
			d.grounded = false;
			d.pos.set(0, 600, 0);
			HeroNavigator dn = new HeroNavigator(d, pw, null);
			for (int i = 0; i < 240 && dn.state() != NavState.SWIM; i++) {
				tick(dn, d, pw, new Pad());
			}
			boolean noDive = at.length > 1;
			assertEquals(!noDive, d.played.contains("swimdive"), d.played.toString());
			assertEquals(NavState.SWIM, dn.state());
		}
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
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		assertEquals(NavState.PUSH, nav.state());
		for (int i = 0; i < 60; i++) {
			in.ly = 1; // camYaw 0: 스틱 위 = -z = 상자 쪽
			in.down.add(PadButton.USE); // 원작 밀기 = 사용 버튼을 누르고 있는 동안 (0x1C4258)
			tick(nav, b, w, in);
		}
		assertTrue(box.pos.z < -60, "상자 밀림 " + box.pos);
		assertEquals(box.pos.z + 40, moved.z, 0.01f);
		assertEquals(box.radius + HeroNavigator.BODY, b.pos.z - box.pos.z, 0.5f); // 영웅은 상자 뒤에서 같이 움직임
		in.ly = 0;
		tick(nav, b, w, in); // 사용 버튼을 뗌 → 놓기
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
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		for (int i = 0; i < 240; i++) {
			in.ly = 1;
			in.down.add(PadButton.USE);
			tick(nav, b, w, in);
		}
		assertTrue(box.pos.z > -70 + 16 * 0.8f - 1, "벽에서 멈춤 " + box.pos);
	}

	/** 원작 0x1C4258 / 0x215DF0: 쌍발차기 문 (CSM 44, 비트 #fb908478 = 0) 은 R1 한 번 — 영웅 doorKick 0.9 초 동안 붙어 있고 문 애니 0.9 초가 100 %. */
	@Test
	void csmDoorOnePressRunsWholeKick() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		b.clips.put("doorKick", 0.9f);
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item door = items.add(new Interactables.Item(Interactables.Kind.CSM, "gotwinkickdoor", v(0, 0, -30), 44, 206, "doorKick", null,
				"HydraKickDoor", 20));
		door.length = 0.9f;
		nav.setItems(items);
		List<String> log = new ArrayList<>();
		List<int[]> progress = new ArrayList<>();
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void taskProgress(Interactables.Item it, int pct) {
				progress.add(new int[]{pct, it.id});
			}

			@Override
			public void pushAttach(Interactables.Item it) {
				log.add("1048 " + it.id);
			}

			@Override
			public void pushDetach(Interactables.Item it) {
				log.add("1049 " + it.id);
			}

			@Override
			public void interactDone(Interactables.Item it) {
				log.add("done");
			}
		});
		Pad in = new Pad();
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		assertEquals(NavState.INTERACT, nav.state());
		int ticks = 1;
		while (nav.state() == NavState.INTERACT && ticks < 200) {
			tick(nav, b, w, in); // 더 누르지 않는다
			ticks++;
		}
		assertTrue(door.done, "한 번 누름으로 끝까지");
		assertEquals(54, ticks, 1, "doorKick 0.9 초 = 54 틱 붙어 있음");
		assertEquals(List.of("1048 206", "done", "1049 206"), log, "붙잡기·놓기도 과제 id 로 (0x1E17F0 모드 0x40 → +0x1CC)");
		assertEquals("doorKick", b.played.getFirst());
		assertTrue(progress.stream().anyMatch(p -> p[0] >= 30 && p[1] == 206), "TwinKickDoor 조건 (≥ 30 %, 과제 206)");
		assertEquals(100, progress.getLast()[0]);
		assertEquals(1, progress.stream().filter(p -> p[0] == 100).count());
		// 끝난 오브젝트는 다시 잡히지 않는다
		for (int i = 0; i < 30; i++) {
			tick(nav, b, w, in);
		}
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		assertEquals(NavState.GROUND, nav.state());
	}

	/** 상자 (비트 #fb908478 = 1): chestGrab 동안 R1 을 누르고 있어야 붙어 있다. 떼면 놓고 −4 로 되감기며, 0 에 닿을 때까지 다시 잡을 수 없다. */
	@Test
	void chestNeedsHoldAndRewindsWhenReleased() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		b.clips.put("chestGrab", 1.967f);
		b.clips.put("chestOpen", 0.633f);
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item chest = items.addChest("chest1", v(0, 0, -30), 0, 1, null);
		assertTrue(chest.hold);
		nav.setItems(items);
		List<String> log = new ArrayList<>();
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void pushDetach(Interactables.Item it) {
				log.add("1049");
			}

			@Override
			public void interactDone(Interactables.Item it) {
				log.add("done");
			}
		});
		Pad in = new Pad();
		for (int i = 0; i < 60; i++) { // 1 초 누르고 있음
			in.down.add(PadButton.USE);
			tick(nav, b, w, in);
		}
		assertEquals(NavState.INTERACT, nav.state());
		assertEquals(1.967f, chest.length, 1e-4f, "상자 길이 = 영웅 chestGrab");
		float half = chest.progress;
		assertTrue(half > 0.45f && half < 0.55f, "1 / 1.967 초: " + half);
		tick(nav, b, w, in); // 뗌
		assertEquals(NavState.GROUND, nav.state());
		assertEquals(List.of("1049"), log);
		for (int i = 0; i < 6; i++) {
			tick(nav, b, w, in);
		}
		assertTrue(chest.progress < half - 0.1f, "−4 로 되감김");
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		assertEquals(NavState.GROUND, nav.state(), "되감는 동안은 못 잡음");
		for (int i = 0; i < 60; i++) {
			tick(nav, b, w, in);
		}
		assertEquals(0, chest.progress);
		// 이번엔 끝까지 누르고 있음 → chestGrab 끝에 100 % → chestOpen 0.633 초 뒤 놓음
		int ticks = 0;
		do {
			in.down.add(PadButton.USE);
			tick(nav, b, w, in);
			ticks++;
		} while (nav.state() == NavState.INTERACT && ticks < 400);
		assertTrue(chest.done);
		assertEquals(List.of("1049", "done", "1049"), log);
		assertEquals((1.967f + 0.633f) * 60, ticks, 2);
		assertEquals("chestOpen", b.anim);
	}

	/** 크랭크 (Push Type 1): R1 을 누른 채 스틱으로 돌림 — 속도 += Acc·dt (최대), 스틱을 놓으면 −Dec·dt, R1 을 떼면 놓고 되감기. */
	@Test
	void crankTurnsWhileHeldWithStick() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item crank = items.add(new Interactables.Item(Interactables.Kind.LEVER, "gocagetriggerhandle", v(0, 0, -30), 13, 230, "crankIdle",
				null, "Crank (30F/20B)", 20));
		crank.crank = true;
		crank.length = 12;
		crank.acc = 3;
		crank.dec = 1;
		crank.maxSpeed = 1.5f;
		crank.rewindAcc = 20;
		crank.maxBack = 15;
		nav.setItems(items);
		Pad in = new Pad();
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		for (int i = 0; i < 30; i++) { // 스틱 없이 누르고만 있음 → 안 돎
			in.down.add(PadButton.USE);
			tick(nav, b, w, in);
		}
		assertEquals(0, crank.task().time);
		for (int i = 0; i < 60; i++) {
			in.down.add(PadButton.USE);
			in.ly = 1;
			tick(nav, b, w, in);
		}
		assertEquals(1.5f, crank.task().speed, 1e-4f, "0.5 초 만에 최대 속도 1.5");
		float at = crank.task().time;
		in.ly = 0;
		for (int i = 0; i < 30; i++) {
			in.down.add(PadButton.USE);
			tick(nav, b, w, in);
		}
		assertEquals(1.0f, crank.task().speed, 0.02f, "스틱을 놓으면 −Dec · dt");
		assertTrue(crank.task().time > at);
		tick(nav, b, w, in); // R1 뗌
		assertEquals(NavState.GROUND, nav.state());
		float r = crank.task().time;
		for (int i = 0; i < 10; i++) {
			tick(nav, b, w, in);
		}
		assertTrue(crank.task().time < r && crank.task().speed < 0, "되감기 (가속 20, 최대 −15)");
	}

	/** TAndF 들어 올리는 문: R1 을 누를 때마다 속도 +Acc (최대), TF Time 동안 안 누르면 −Dec. 다 들면 놓음. */
	@Test
	void liftDoorRisesPerPress() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item door = items.add(new Interactables.Item(Interactables.Kind.LIFT, "goliftdoor1", v(0, 0, -30), 50, 200, "liftdoor2",
				null, "HydraLiftDoor", 20));
		door.acc = 1;
		door.dec = 20;
		door.maxSpeed = 1.5f;
		door.maxBack = 20;
		door.tfTime = 0.25f;
		nav.setItems(items);
		List<String> log = new ArrayList<>();
		nav.setListener(new HeroNavigator.Listener() {
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
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		tick(nav, b, w, in);
		assertEquals(1f, door.task().speed, 1e-4f, "붙잡을 때 한 번 +Acc");
		for (int i = 0; i < 30; i++) { // 0.5 초 누르지 않음 → TF Time 뒤 −Dec
			tick(nav, b, w, in);
		}
		assertEquals(0, door.task().time, "내려와 0 에서 멈춤");
		assertEquals(NavState.INTERACT, nav.state(), "R1 을 떼도 붙어 있음");
		int ticks = 0;
		while (!door.done && ticks < 600) {
			if (ticks % 6 == 0) { // 초당 10 번
				in.down.add(PadButton.USE);
			}
			tick(nav, b, w, in);
			ticks++;
		}
		assertTrue(door.done, "연타로 다 들어 올림");
		assertEquals(NavState.GROUND, nav.state(), "ANITandF3 없음 → 바로 놓음");
		assertEquals(List.of("1048 200", "1049 200"), log);
	}

	/** 레버 (PushPull Type 5): 붙잡기 1048 → leverpull 이 끝날 때까지 붙어 있음 (원작 0x1ECB24) → 레버 애니 끝에서 1023 100 (속도 0, 0x2127B0) → 1049. PAND00C 엘리베이터 레버 id 111. */
	@Test
	void leverPostsTaskProgressWithItsId() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		b.clips.put("leverpull", 0.6f);
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
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		int ticks = 1;
		while (nav.state() == NavState.INTERACT && ticks < 120) {
			tick(nav, b, w, in);
			ticks++;
		}
		assertTrue(lever.done, log.toString());
		assertEquals(36, ticks, 1, "leverpull 0.6 초 동안 붙어 있음 (레버 0.5 초에 끝나도)");
		assertEquals("1048 111", log.getFirst());
		assertEquals("1049 111", log.getLast());
		assertTrue(log.contains("1023 100 111 0"), "완료 100 % (ElevatorLever-LeverProgress 조건): " + log);
		assertEquals(NavState.GROUND, nav.state());
	}

	/** 표면 레코드가 붙은 사각형 (WallHangTimer 시험용). */
	static void quadInfo(List<LevelCollision.Tri> out, Vector3f a, Vector3f b, Vector3f c, Vector3f d, float hangTimer, SurfaceAttr... at) {
		long m = 0;
		for (SurfaceAttr x : at) {
			m |= x.bit();
		}
		devple.gow.tools.level.SheetSurface info = new devple.gow.tools.level.SheetSurface("t", m, 0, 8, hangTimer, 0, 0);
		for (Vector3f[] tri : new Vector3f[][] {{a, b, c}, {a, c, d}}) {
			Vector3f n = new Vector3f(tri[1]).sub(tri[0]).cross(new Vector3f(tri[2]).sub(tri[0])).normalize();
			out.add(new LevelCollision.Tri(tri[0], tri[1], tri[2], n, 0, m, info));
		}
	}

	/** 원작 0x1E47E0: 벽 오르기 = 발 + 6.4 에서 좌우 ±8 · 정면 14.4 두 선분이 모두 붙는 면, 머리 (+27.8) 선분도 붙는 면, 위치 = 벽 + 9.6. */
	@Test
	void wallProbeNeedsBothRaysAndHeadAndSnapsToHalfDepth() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -200, 200, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, 0, 200, SurfaceAttr.CLIMBABLE);          // 넓은 벽
		wallZ(t, -60, 300, 306, 0, 200, SurfaceAttr.CLIMBABLE);            // 폭 6 띠 (한 선분만 맞음)
		wallZ(t, -60, 500, 600, 0, 20, SurfaceAttr.CLIMBABLE);             // 머리 높이 아래에서 끝나는 벽
		wallZ(t, -60, 500, 600, 20, 200, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		HeroNavigator nav = new HeroNavigator(new Body(), w, null);
		Vector3f into = v(0, 0, -1);
		HeroNavigator.WallProbe p = nav.probeWall(v(5, 0, -8), into, null);
		assertNotNull(p);
		assertEquals(-20 + 9.6f, p.feet().z, 1e-3f, "벽 + 상자 반 깊이 9.6");
		assertEquals(5, p.feet().x, 1e-3f);
		assertEquals(0, p.feet().y, 1e-3f);
		assertEquals(1, p.n().z, 1e-4f);
		assertNull(nav.probeWall(v(5, 0, -3), into, null), "정면 14.4 밖 (벽까지 17)");
		assertNull(nav.probeWall(v(303, 0, -48), into, null), "폭 6 띠: ±8 선분 중 하나만 맞음");
		assertNull(nav.probeWall(v(550, 0, -48), into, null), "머리 높이 (발 + 34.2) 가 Climbable 아님");
		assertEquals(9.6f, HeroNavigator.WALL_OFF, 1e-4f);
		assertEquals(35.2f, HeroNavigator.HANG, 1e-4f);
		assertEquals(28.8f, HeroNavigator.REACH, 1e-4f);
	}

	/** 원작 0x1E3A80: 턱 = 정면 12.8 · 좌우 ±4.8 수직 선분 둘이 손 높이 ± d 에서 바닥 (법선 y &gt; 0.85, Slide·NoWallHang 아님) 에 맞을 때. */
	@Test
	void ledgeProbeRules() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -20, 200, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 300, 0, 40, SurfaceAttr.GROUND);
		floor(t, -100, -300, 100, -20, 40, SurfaceAttr.GROUND);
		floor(t, 100, -300, 200, -20, 40, SurfaceAttr.GROUND, SurfaceAttr.SLIDE);
		quadInfo(t, v(200, 40, -300), v(200, 40, -20), v(300, 40, -20), v(300, 40, -300), 60, SurfaceAttr.GROUND, SurfaceAttr.NO_WALL_HANG);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Vector3f f = v(0, 0, -1);
		HeroNavigator.Ledge l = nav.probeLedge(v(0, 4, -10), v(0, 4.5f, -10), f, null);
		assertNotNull(l, "하강 중 손 (발 + 35.2 = 39.2) 이 턱 40 아래");
		assertEquals(40, l.y(), 1e-3f);
		assertEquals(40 - 35.2f, l.feet().y, 1e-3f);
		assertEquals(-20 + 9.6f, l.feet().z, 1e-3f);
		assertNull(nav.probeLedge(v(0, 6, -10), v(0, 6.5f, -10), f, null), "하강 중인데 손 (41.2) 이 아직 턱 위");
		assertNotNull(nav.probeLedge(v(0, 6, -10), v(0, 5.5f, -10), f, null), "올라가는 중엔 창 (±3.2) 안이면 잡음");
		assertNull(nav.probeLedge(v(0, -2, -10), v(0, -1.5f, -10), f, null), "손이 창 밖 (턱 아래 6.8)");
		assertNull(nav.probeLedge(v(0, 4, -3), v(0, 4.5f, -3), f, null), "턱 탐색 점 (정면 12.8) 이 아직 벽 앞");
		assertNull(nav.probeLedge(v(150, 4, -10), v(150, 4.5f, -10), f, null), "Slide 턱");
		assertNull(nav.probeLedge(v(250, 4, -10), v(250, 4.5f, -10), f, null), "NoWallHang 턱");
		assertEquals(-20 + 9.6f, nav.probeLedge(v(0, 4, -12.5f), v(0, 4.5f, -12.5f), f, null).feet().z, 1e-3f, "벽 + 9.6 으로 맞춤");
	}

	/** 원작 0x1C772C / 0x1C7B04: 매달리기 시간 = 턱 면 WallHangTimer, 다 쓰면 떨어지고 땅에 닿기 전엔 다시 못 잡음. */
	@Test
	void wallHangTimerDropsAndBlocksRegrabUntilLanding() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -200, -20, 200, 200, 0, SurfaceAttr.GROUND);
		wallZ(t, -20, -100, 100, 0, 40, SurfaceAttr.GROUND);
		quadInfo(t, v(-100, 40, -300), v(-100, 40, -20), v(100, 40, -20), v(100, 40, -300), 0.5f, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Pad in = new Pad();
		b.grounded = false;
		b.vy = -10;
		b.pos.set(0, 4, -10);
		tick(nav, b, w, in);
		assertEquals(NavState.LEDGE_HANG, nav.state());
		assertEquals(0.5f, nav.hangTimer(), 1e-4f);
		int n = 0;
		while (nav.state() == NavState.LEDGE_HANG && n < 100) {
			tick(nav, b, w, in);
			n++;
		}
		assertEquals(30, n, 1, "0.5 초 매달린 뒤 떨어짐");
		assertEquals(0, nav.hangTimer());
		// 떨어지는 동안 손이 턱을 다시 지나도 (타이머 0) 못 잡음
		b.pos.set(0, 4, -10);
		b.vy = -10;
		b.grounded = false;
		tick(nav, b, w, in);
		assertNotEquals(NavState.LEDGE_HANG, nav.state());
		for (int i = 0; i < 120 && !b.grounded; i++) {
			tick(nav, b, w, in);
		}
		assertTrue(b.grounded);
		tick(nav, b, w, in);
		assertEquals(Float.MAX_VALUE, nav.hangTimer(), "착지 → 초기화 (0x1C78A0)");
	}

	/** 원작 0x1C44E0: 붙은 상자를 미는 영웅 속도 = Sold+0x188[무게] × 스틱 (최소 +0x17C), 상자 최대 #95b8bfb6. */
	@Test
	void pushSpeedFollowsWeightClass() {
		assertEquals(1.9f * 16, HeroNavigator.pushSpeedMax(0), 1e-3f);
		assertEquals(1.5f * 16, HeroNavigator.pushSpeedMax(1), 1e-3f);
		assertEquals(1.2f * 16, HeroNavigator.pushSpeedMax(2), 1e-3f);
		assertEquals(0.5f * 16, HeroNavigator.pushSpeedMin(2), 1e-3f);
		assertEquals(9 * 16, HeroNavigator.kickSpeed(2, 2), 1e-3f);
		assertEquals(4.5f * 16, HeroNavigator.kickSpeed(2, 1), 1e-3f);
		assertEquals(24 * 16, HeroNavigator.kickSpeed(0, 5), 1e-3f, "충전은 2 초에서 포화");
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item box = items.add(new Interactables.Item(Interactables.Kind.PUSH, "gopbox", v(0, 0, -40), 225, 503, "pushIdle", null,
				"Pandora's Box", 16));
		box.weight = 2;
		nav.setItems(items);
		Pad in = new Pad();
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		for (int i = 0; i < 60; i++) {
			in.ly = 1;
			in.down.add(PadButton.USE);
			tick(nav, b, w, in);
		}
		assertEquals(-40 - 19.2f, box.pos.z, 0.05f, "무게 2 = 1.2 m/s");
		float z = box.pos.z;
		for (int i = 0; i < 60; i++) {
			in.ly = 0.35f; // 스틱 조금 (0.35 × 1.2 = 0.42 m/s) → 최소 0.5 m/s
			in.down.add(PadButton.USE);
			tick(nav, b, w, in);
		}
		assertEquals(z - 8, box.pos.z, 0.05f);
		assertEquals("Push", b.anim);
	}

	/** 원작 0x1C4928: 밀기 중 × 누르고 있으면 충전 (KickBox1 → KickBox3), 떼면 0x41D + 상자 속도 += 정면 × 차기 속도, 상자는 마찰 (9.82·16) 로 멈춤. */
	@Test
	void kickBoxChargesThenSlidesWithFriction() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		Interactables items = new Interactables();
		Interactables.Item box = items.add(new Interactables.Item(Interactables.Kind.PUSH, "gopbox", v(0, 0, -40), 225, 503, "pushIdle", null,
				"Pandora's Box", 16));
		box.weight = 2;
		nav.setItems(items);
		b.clips.put("KickBox1", 0.3f);
		b.clips.put("KickBox2", 0.5f);
		List<String> ev = new ArrayList<>();
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void boxKicked(Interactables.Item it) {
				ev.add("kick " + it.id);
			}

			@Override
			public void pushDetach(Interactables.Item it) {
				ev.add("detach");
			}
		});
		Pad in = new Pad();
		in.down.add(PadButton.USE);
		tick(nav, b, w, in);
		for (int i = 0; i < 61; i++) {
			in.down.add(PadButton.USE);
			in.down.add(PadButton.CROSS);
			tick(nav, b, w, in);
		}
		assertTrue(b.played.contains("KickBox1") && b.played.contains("KickBox3"), b.played.toString());
		assertEquals(-40, box.pos.z, 1e-3f, "충전 중엔 상자 그대로");
		in.down.add(PadButton.USE);
		tick(nav, b, w, in); // × 뗌 → 차기
		assertEquals(List.of("kick 503"), ev);
		assertEquals("KickBox2", b.anim);
		in.down.clear();
		tick(nav, b, w, in); // 사용 버튼도 뗌 → 놓고 상자는 혼자 미끄러짐
		for (int i = 0; i < 120; i++) {
			tick(nav, b, w, in);
		}
		assertEquals(0, box.vel.length(), 1e-4f, "마찰로 멈춤");
		// 차기 속도 4.5 m/s (충전 1 초) = 72 → 미끄러짐 ≈ 72² / (2 · 157.12) = 16.5
		assertEquals(-40 - 16.5f, box.pos.z, 1.5f);
		assertEquals(List.of("kick 503", "detach"), ev);
	}
}
