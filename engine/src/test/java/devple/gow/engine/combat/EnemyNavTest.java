package devple.gow.engine.combat;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 적 자신의 이동 상태 ({@link EnemyNav}, 32 문서 §적 자신의 벽·줄 이동) — 데이터 없는 단위 테스트.
 * 세계: 바닥 y=0, z=0 의 Climbable 벽 (x ∈ [−200, 200], y ∈ [0, 300], 법선 +z), 수평 줄 (−200,130,60) → (200,130,60).
 */
class EnemyNavTest {

	static final float DT = 1 / 60f;
	static final AiData.Motion SKEL = new AiData.Motion(8, 80, 80, 8, 3, 0.5f, 2.17f, 3.7f, 2);

	/** 벽 꼭대기 높이 (테스트마다 바꿈). */
	static float wallTop = 300f;

	static final EnemyNav.World WORLD = new EnemyNav.World() {
		public float ground(float x, float y, float z, float drop) {
			return y >= 0 && y - drop <= 0 ? 0 : Float.NaN;
		}

		public EnemyNav.WallHit wall(Vector3f o, Vector3f dir, float dist) {
			// z = 0 평면 (양면), x ∈ [−200, 200], y ∈ [0, wallTop]
			if (Math.abs(dir.z) < 1e-4f || o.z <= 0) {
				return null;
			}
			Vector3f d = new Vector3f(dir).normalize();
			float t = -o.z / d.z;
			if (t < 0 || t > dist) {
				return null;
			}
			Vector3f p = new Vector3f(o).fma(t, d);
			if (Math.abs(p.x) > 200 || p.y < 0 || p.y > wallTop) {
				return null;
			}
			return new EnemyNav.WallHit(p, new Vector3f(0, 0, 1), true);
		}

		public EnemyNav.Rope rope(Vector3f p, float r) {
			EnemyNav.Rope rope = new EnemyNav.Rope(new Vector3f(-200, 130, 60), new Vector3f(200, 130, 60));
			Vector3f q = rope.point(rope.closest(p));
			return q.distance(p) <= r ? rope : null;
		}
	};

	private static EnemyNav nav(int caps) {
		wallTop = 300f;
		EnemyNav n = new EnemyNav(caps, SKEL, 1000);
		n.setWorld(WORLD);
		return n;
	}

	@Test
	void groundSpeedComesFromSoldWithAccel() {
		EnemyNav n = new EnemyNav(0, SKEL, 1000); // 세계 없음 = 지상만
		Vector3f p = new Vector3f();
		Vector3f stick = new Vector3f(1, 0, 0);
		n.update(p, stick, null, false, DT);
		// 가속 80 m/s² × 16 / 60 = 21.3 유닛/s per 프레임
		assertEquals(80 * 16 * DT, n.speed(), 1e-3);
		for (int i = 0; i < 60; i++) {
			n.update(p, stick, null, false, DT);
		}
		assertEquals(8 * 16f, n.speed(), 1e-3, "Sold+0x178 8 m/s = 128 유닛/s");
		// 스틱 0.5 (AI1 0x64 50 %) → 절반
		for (int i = 0; i < 60; i++) {
			n.update(p, new Vector3f(0.5f, 0, 0), null, false, DT);
		}
		assertEquals(64f, n.speed(), 1e-3);
		for (int i = 0; i < 60; i++) {
			n.update(p, new Vector3f(), null, false, DT);
		}
		assertEquals(0f, n.speed(), 1e-3);
		assertEquals(0, n.conditionState());
		assertEquals(0x2, n.combatMask());
	}

	@Test
	void noCapsFallsToGround() {
		EnemyNav n = nav(0);
		Vector3f p = new Vector3f(0, 150, 6);
		for (int i = 0; i < 240; i++) {
			n.update(p, new Vector3f(), null, false, DT);
		}
		assertEquals(EnemyNav.Mode.GROUND, n.mode());
		assertEquals(0f, p.y, 1e-3);
	}

	@Test
	void climberSpawnedNextToWallCatchesIt() {
		EnemyNav n = nav(AiTemplate.CAP_CLIMB);
		Vector3f p = new Vector3f(0, 150, 6);
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.WALL, n.mode());
		assertEquals(1, n.conditionState(), "조건 4: 벽 = 1");
		assertEquals(0x20, n.combatMask(), "분기 상태 마스크: 벽 0x20");
		assertEquals(EnemyNav.WALL_OFF, p.z, 1e-3);
		assertEquals(150f, p.y, 1e-3, "잡은 높이 유지");
		assertEquals(0f, n.faceYaw(), 1e-4, "벽(−z 법선 반대)을 바라봄");
	}

	@Test
	void climbsTowardGoalInWallPlaneAndStepsOffAtBottom() {
		EnemyNav n = nav(AiTemplate.CAP_CLIMB);
		Vector3f p = new Vector3f(0, 150, 6);
		n.update(p, new Vector3f(), null, false, DT);
		// 오른쪽 위 목표: 좌우 2.17×16, 상하 3.7×16 유닛/s 비율
		Vector3f goal = new Vector3f(100, 250, 40);
		float y0 = p.y;
		n.update(p, new Vector3f(), goal, false, DT);
		assertTrue(p.y > y0 && p.x > 0, "위·오른쪽으로");
		assertEquals(EnemyNav.WALL_OFF, p.z, 1e-3, "벽에 붙은 채");
		// 목표가 아래 바닥 (영웅이 갑판에) → 내려가서 지상
		Vector3f below = new Vector3f(0, 0, 60);
		int frames = 0;
		while (n.mode() == EnemyNav.Mode.WALL && frames++ < 600) {
			n.update(p, new Vector3f(), below, false, DT);
		}
		assertEquals(EnemyNav.Mode.GROUND, n.mode());
		assertEquals(0f, p.y, 1e-3);
		// 160 유닛을 ≈ 3.7×16 유닛/s 로 → 약 2.7 초
		assertTrue(frames > 120 && frames < 240, "내려오는 프레임 " + frames);
	}

	@Test
	void stuckAtWallTopJumpsOffAfterTemplateDelay() {
		EnemyNav n = nav(AiTemplate.CAP_CLIMB);
		wallTop = 160f;
		Vector3f p = new Vector3f(0, 120, 6);
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.WALL, n.mode());
		Vector3f up = new Vector3f(0, 1000, 40);
		int frames = 0;
		while (n.mode() == EnemyNav.Mode.WALL && frames++ < 600) {
			n.update(p, new Vector3f(), up, false, DT);
		}
		assertEquals(EnemyNav.Mode.AIR, n.mode(), "AI1 0x8A 1000 ms 뒤 점프로 놓음");
		assertTrue(frames > 60 && frames < 120, "프레임 " + frames);
		n.update(p, new Vector3f(), null, false, DT);
		assertNotEquals(EnemyNav.Mode.WALL, n.mode(), "놓은 직후 다시 잡지 않음");
	}

	@Test
	void ropeCatchMoveAlongAndDropAboveGoal() {
		EnemyNav n = nav(AiTemplate.CAP_ROPE);
		Vector3f p = new Vector3f(0, 105, 60); // 손 135 → 줄(130) 32 안
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(3, n.conditionState(), "조건 4: 줄 = 3");
		assertEquals(0x80, n.combatMask());
		assertEquals(130 - EnemyNav.HAND, p.y, 1e-3);
		// 목표도 줄 위 (영웅이 같은 줄) → 줄 따라 그쪽으로, 놓지 않음
		Vector3f onRope = new Vector3f(120, 100, 60);
		for (int i = 0; i < 120; i++) {
			n.update(p, new Vector3f(), onRope, false, DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(64f, p.x, 1.5f, "2 m/s × 16 × 2 s");
		// 목표가 줄 아래 바닥 → 줄 위 투영점 16 안에서 놓고 떨어진다
		Vector3f ground = new Vector3f(150, 0, 60);
		int frames = 0;
		while (n.mode() == EnemyNav.Mode.ROPE && frames++ < 600) {
			n.update(p, new Vector3f(), ground, false, DT);
		}
		assertEquals(EnemyNav.Mode.AIR, n.mode());
		assertEquals(150 - EnemyNav.ROPE_DROP, p.x, 2f);
		for (int i = 0; i < 120 && n.mode() != EnemyNav.Mode.GROUND; i++) {
			n.update(p, new Vector3f(), null, false, DT);
		}
		assertEquals(EnemyNav.Mode.GROUND, n.mode());
	}

	@Test
	void ropeOutOfReachIsNotCaught() {
		EnemyNav n = nav(AiTemplate.CAP_ROPE);
		Vector3f p = new Vector3f(0, 105, 60 + 40); // 줄에서 수평 40 > 32
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.AIR, n.mode());
	}

	@Test
	void dropFromWallFalls() {
		EnemyNav n = nav(AiTemplate.CAP_CLIMB);
		Vector3f p = new Vector3f(0, 150, 6);
		n.update(p, new Vector3f(), null, false, DT);
		n.drop();
		assertEquals(EnemyNav.Mode.AIR, n.mode());
		assertEquals(2, n.conditionState());
		assertEquals(0x4, n.combatMask());
	}
}
