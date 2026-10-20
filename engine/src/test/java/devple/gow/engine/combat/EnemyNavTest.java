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
	/** 줄 손 높이 = 기본 몸 (크라토스 Sold +0x238 2.2) 키 35.2 (0x1C5C68 / 0x1C66E4). */
	static final float HAND = AiData.Body.HERO.height() * 16f;
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

	/** 벽 높이는 정적 — 한 테스트가 낮춘 값이 다음 테스트로 새지 않게 (섞은 순서에서 Mob 테스트가 실패했다, 48 문서 §5). */
	@org.junit.jupiter.api.BeforeEach
	void resetWall() {
		wallTop = 300f;
	}

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

	/**
	 * 줄 손 높이 = 키 (Sold +0x238 × 16 × 개체 스케일 = 내비 +0x120.y) [확실: 잡기 0x1C5C68 / 몸 0x1C66E4]: 해골 Sold_020 2.8 × 스케일 0.87 = 38.976
	 * (전 근사 "발 + 30" 폐기).
	 */
	@Test
	void ropeHandHeightIsSoldHeight() {
		EnemyNav n = nav(AiTemplate.CAP_ROPE);
		n.setBody(new AiData.Body(0.7f, 2.8f, 0.4f, 0, ""), 0.87f);
		float hand = 2.8f * 16f * 0.87f;
		assertEquals(hand, n.hand(), 1e-4f);
		Vector3f p = new Vector3f(0, 130 - hand - 30, 60); // 손 (발 + 키) = 줄 − 30 → 잡기 반경 32 안
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(130 - hand, p.y, 1e-3f, "몸 = 줄 점 − 키");
	}

	@Test
	void ropeCatchMoveAlongAndDropAboveGoal() {
		EnemyNav n = nav(AiTemplate.CAP_ROPE);
		Vector3f p = new Vector3f(0, 105, 60); // 손 140.2 → 줄(130) 32 안
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(3, n.conditionState(), "조건 4: 줄 = 3");
		assertEquals(0x80, n.combatMask());
		assertEquals(130 - HAND, p.y, 1e-3);
		// 목표도 줄 위 (영웅이 같은 줄) → 줄 따라 그쪽으로, 놓지 않음
		Vector3f onRope = new Vector3f(120, 100, 60);
		for (int i = 0; i < 120; i++) {
			n.update(p, new Vector3f(), onRope, false, DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(64f, p.x, 1.5f, "2 m/s × 16 × 2 s");
		// 목표가 줄 아래 바닥 (발 기준 130 − 키 아래) → 줄 따라가다 목표 방향이 45° 보다 가파르면 (수평 < 100) 놓는다 (0x1CFD20: Δy < −16, dir.y < −cos45)
		Vector3f ground = new Vector3f(190, 0, 60);
		int frames = 0;
		while (n.mode() == EnemyNav.Mode.ROPE && frames++ < 600) {
			n.update(p, new Vector3f(), ground, false, DT);
		}
		assertEquals(EnemyNav.Mode.AIR, n.mode());
		assertEquals(190 - (130 - HAND), p.x, 3f, "놓는 곳 = 목표까지 수평 거리 = 높이 차 (발 = 줄 − 키)");
		for (int i = 0; i < 120 && n.mode() != EnemyNav.Mode.GROUND; i++) {
			n.update(p, new Vector3f(), null, false, DT);
		}
		assertEquals(EnemyNav.Mode.GROUND, n.mode());
	}

	@Test
	void ropeSubStatesGiveBranchMask() {
		// 0x207DC0: 줄은 하위 상태 0x20000 (양끝 고정 수평 줄) 일 때만 분기 마스크 0x80
		for (int sub : new int[]{0, EnemyNav.SUB_CHAIN, EnemyNav.SUB_TRAVERSE, EnemyNav.SUB_ZIPLINE}) {
			EnemyNav.Rope r = new EnemyNav.Rope(new Vector3f(-200, 130, 60), new Vector3f(200, 130, 60), sub);
			EnemyNav n = new EnemyNav(AiTemplate.CAP_ROPE, SKEL, 1000);
			n.setWorld(new EnemyNav.World() {
				public float ground(float x, float y, float z, float drop) {
					return WORLD.ground(x, y, z, drop);
				}

				public EnemyNav.WallHit wall(Vector3f o, Vector3f dir, float dist) {
					return null;
				}

				public EnemyNav.Rope rope(Vector3f p, float rr) {
					return r;
				}
			});
			Vector3f p = new Vector3f(0, 105, 60);
			n.update(p, new Vector3f(), null, false, DT);
			assertEquals(EnemyNav.Mode.ROPE, n.mode());
			assertEquals(3, n.conditionState());
			assertEquals(sub == EnemyNav.SUB_TRAVERSE ? 0x80 : 0, n.combatMask(), "하위 " + Integer.toHexString(sub));
			assertEquals(EnemyNav.ST_ROPE | sub, n.stateBits());
		}
	}

	@Test
	void hangingRopeReleasesTowardGoalAwayFromAnchor() {
		// 매단 줄: 목표가 아래이고 매단 점에서 수평 16 넘게 떨어져 있으면 점프 버튼, 바로 밑이면 매달려 있음 (0x1CFD90).
		// 점프 버튼 → 영웅과 같은 0x1C5488: 전역 0x29DBF8 > 0.5 일 때만 (적이 잡으면 0, 플레이어의 줄 갱신만 더함) [확실]
		EnemyNav.Rope hang = new EnemyNav.Rope(new Vector3f(0, 200, 60), new Vector3f(0, 60, 60));
		assertEquals(0, hang.sub(), "수직 = 매단 줄");
		float[] clock = {3f};
		EnemyNav n = new EnemyNav(AiTemplate.CAP_ROPE, SKEL, 1000);
		n.setWorld(new EnemyNav.World() {
			public float ground(float x, float y, float z, float drop) {
				return WORLD.ground(x, y, z, drop);
			}

			public EnemyNav.WallHit wall(Vector3f o, Vector3f dir, float dist) {
				return null;
			}

			public EnemyNav.Rope rope(Vector3f p, float r) {
				return hang;
			}

			public float ropeClock() {
				return clock[0];
			}

			public void ropeGrabbed() {
				clock[0] = 0;
			}
		});
		Vector3f p = new Vector3f(0, 100, 60);
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(0f, clock[0], "잡으면 0x29DBF8 = 0 (0x1C5D98)");
		assertEquals(0, n.combatMask());
		for (int i = 0; i < 30; i++) {
			n.update(p, new Vector3f(), new Vector3f(5, 0, 60), false, DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, n.mode(), "목표가 매단 점 바로 밑 → 매달림");
		float fx0 = (float) -Math.sin(n.ropeYaw());
		assertTrue(fx0 > 0.7f, "가까운 목표(+x) 쪽으로는 돌았지만 (정렬) 매단 점에서 16 안이라 놓지 않음");
		// 반대쪽 수평 80 > 16: 몸 정면(+x) 과 목표 방향(−x) 이 180° → 먼저 회전 입력 (1,0,0) 으로 돌고 (0x1CFEFC), 45° 안에 들면 점프 버튼 (0x1CFEE8)
		Vector3f goal = new Vector3f(-80, 0, 60);
		int aligned = -1;
		for (int frames = 1; frames <= 120; frames++) {
			n.update(p, new Vector3f(), goal, false, DT);
			assertEquals(0f, p.x, 1e-3, "매달린 동안 스틱 0 — 줄 위 제자리");
			if (aligned < 0 && -Math.sin(n.ropeYaw()) < -0.7f) {
				aligned = frames;
			}
		}
		// 매단 줄 위 몸 방향 = 줄 축 4 방향 (0x1C6858): +x → +z → −x 두 번 90° (번호 + 1), 한 번에 보간 22 프레임 (0.1 × 1.1111^k → 1, 0x1E0DC0)
		assertTrue(aligned > 36 && aligned < 50, "정렬까지 프레임 " + aligned);
		assertEquals(EnemyNav.Mode.ROPE, n.mode(), "0x29DBF8 = 0 (영웅이 줄에 없음) → × 가 먹지 않아 매달린 채");
		// 영웅이 다른 줄에 0.5 초 넘게 매달려 시계가 흐르면 같은 점프 버튼으로 뛰어내린다
		clock[0] = 0.6f;
		n.update(p, new Vector3f(), goal, false, DT);
		assertEquals(EnemyNav.Mode.AIR, n.mode(), "수평 80 > 16 → 뛰어내림");
		assertEquals(224f, n.speed(), 1e-3, "앞 = Sold+0x144 14 m/s (전 근사 40 폐기)");
		float fx = (float) -Math.sin(n.ropeYaw()), fz = (float) -Math.cos(n.ropeYaw());
		assertTrue(fx < -0.99f && Math.abs(fz) < 0.01f, "정면 = 줄 축 후보 (−x) " + fx + "," + fz);
		float y0 = p.y, top = p.y;
		for (int i = 0; i < 240 && n.mode() != EnemyNav.Mode.GROUND; i++) {
			n.update(p, new Vector3f(), null, false, DT);
			top = Math.max(top, p.y);
		}
		assertEquals(EnemyNav.Mode.GROUND, n.mode());
		// 위 Sold+0x140 14 m/s = 224 → 정점 ≈ 224² / (2 × 800) = 31.4 (전 근사: 점프 Sold+0xC4 17 → 272)
		assertEquals(224f * 224f / (2 * EnemyNav.GRAVITY), top - y0, 2f);
		assertTrue(p.x < -20f, "정면으로 뛰어내림 x=" + p.x);
	}

	@Test
	void traverseRopeEnemyHangsOnSharedSagSim() {
		// 양끝 고정 수평 줄 (SCR f1): 적도 영웅과 같은 줄 오브젝트 시뮬레이션에 매달린다 (0x218200 / 0x2184E8) — 무게 최대 10 으로 처지고 손 = 줄 위 점
		devple.gow.engine.nav.NavRope nr = new devple.gow.engine.nav.NavRope("gorope8", devple.gow.tools.level.RopeDef.Type.TRAVERSE, 0, 25f,
				new Vector3f(-200, 130, 60), new Vector3f(200, 130, 60));
		devple.gow.engine.nav.RopeSim sim = devple.gow.engine.nav.RopeSim.fixed(nr.top(), nr.end(), nr.joints());
		EnemyNav.Rope r = new EnemyNav.Rope(nr.top(), nr.end(), EnemyNav.SUB_TRAVERSE, nr.axis(), nr.dirs(), nr);
		float[] clock = {5f};
		EnemyNav n = new EnemyNav(AiTemplate.CAP_ROPE, SKEL, 1000);
		n.setWorld(new EnemyNav.World() {
			public float ground(float x, float y, float z, float drop) {
				return WORLD.ground(x, y, z, drop);
			}

			public EnemyNav.WallHit wall(Vector3f o, Vector3f dir, float dist) {
				return null;
			}

			public EnemyNav.Rope rope(Vector3f p, float rr) {
				Vector3f q = r.point(r.closest(p));
				return q.distance(p) <= rr ? r : null;
			}

			public devple.gow.engine.nav.RopeSim sim(devple.gow.engine.nav.NavRope x) {
				return x == nr ? sim : null;
			}

			public void ropeGrabbed() {
				clock[0] = 0;
			}
		});
		Vector3f p = new Vector3f(0, 130 - HAND - 4, 60);
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(0f, clock[0], "적이 잡아도 0x29DBF8 = 0");
		assertTrue(sim.attached(), "적이 줄 오브젝트에 매달림");
		for (int i = 0; i < 240; i++) {
			sim.update(DT);
			n.update(p, new Vector3f(), null, false, DT);
		}
		assertEquals(sim.maxWeight(), sim.weight(), 1e-3, "무게 → 최대 10 (양끝 고정, 0x216D84)");
		Vector3f hand = sim.pointAt(sim.dist(), new Vector3f()).mul(1);
		assertEquals(hand.y - HAND, p.y, 1e-3, "몸 = 시뮬레이션 줄 위 손 − 손 높이");
		assertTrue(p.y < 130 - HAND - 0.5f, "가운데가 처짐 y=" + p.y);
		// 목표가 가파르게 아래 → × (0x1CFCE0) → 수평 줄은 비트 31 이 아니라 시간 조건 없이 놓음 (0x1C552C → 0x1E9030(2))
		n.update(p, new Vector3f(), new Vector3f(p.x, -200, 60), false, DT);
		assertEquals(EnemyNav.Mode.AIR, n.mode());
		assertFalse(sim.attached(), "놓으면 매달림 해제 (0x218270)");
	}

	@Test
	void hangingRopeTargetAboveOrOnRopeKeepsHanging() {
		EnemyNav.Rope hang = new EnemyNav.Rope(new Vector3f(0, 200, 60), new Vector3f(0, 60, 60));
		EnemyNav n = new EnemyNav(AiTemplate.CAP_ROPE, SKEL, 1000);
		n.setWorld(ropeWorld(hang));
		Vector3f p = new Vector3f(0, 100, 60);
		n.update(p, new Vector3f(), null, false, DT);
		float y = p.y;
		for (int i = 0; i < 120; i++) {
			n.update(p, new Vector3f(), new Vector3f(200, 300, 60), false, DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, n.mode(), "목표가 위 → 스틱 0, 매달림 (오르지 않음)");
		assertEquals(y, p.y, 1e-3);
		for (int i = 0; i < 120; i++) {
			n.update(p, new Vector3f(), new Vector3f(200, 0, 60), Boolean.TRUE, false, DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, n.mode(), "대상도 줄 (조건 5) → 놓지 않음");
	}

	private static EnemyNav.World ropeWorld(EnemyNav.Rope r) {
		return new EnemyNav.World() {
			public float ground(float x, float y, float z, float drop) {
				return WORLD.ground(x, y, z, drop);
			}

			public EnemyNav.WallHit wall(Vector3f o, Vector3f dir, float dist) {
				return null;
			}

			public EnemyNav.Rope rope(Vector3f p, float rr) {
				Vector3f q = r.point(r.closest(p));
				return q.distance(p) <= rr ? r : null;
			}
		};
	}

	@Test
	void ziplineRidesDownToLowEndAtSoldSpeedAndLetsGo() {
		// 짚라인 0x40000: 스틱 무관, 낮은 끝으로 Sold+0x160 20 m/s (320) 까지 +0x164 10 m/s² (160) 가속, 끝 0.25 m 에서 속도 0 으로 놓음 (0x1C60F8, 0x1C6B1C)
		EnemyNav.Rope zip = new EnemyNav.Rope(new Vector3f(-300, 300, 60), new Vector3f(1300, 100, 60), EnemyNav.SUB_ZIPLINE);
		EnemyNav n = new EnemyNav(AiTemplate.CAP_ZIPLINE, SKEL, 1000);
		n.setRide(new AiData.Ride(17f, 20f, 10f, 1.5f));
		n.setWorld(ropeWorld(zip));
		Vector3f p = new Vector3f(-280, 300 - HAND - 4, 60);
		n.update(p, new Vector3f(), null, false, DT);
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertEquals(EnemyNav.ST_ROPE | EnemyNav.SUB_ZIPLINE, n.stateBits());
		assertEquals(0, n.combatMask(), "짚라인 분기 마스크 0");
		// 1 초 뒤 속도 160, 2 초 뒤 320 (상한)
		for (int i = 0; i < 60; i++) {
			n.update(p, new Vector3f(-1, 0, 0), null, false, DT); // 스틱은 무시 (반대 방향이어도)
		}
		assertEquals(160f, n.zipSpeed(), 3f);
		assertTrue(p.x > -280, "낮은 끝(+x) 쪽으로");
		int frames = 60;
		while (n.mode() == EnemyNav.Mode.ROPE && frames++ < 60 * 10) {
			n.update(p, new Vector3f(), null, false, DT);
			assertTrue(n.zipSpeed() <= 320f + 1e-3f);
		}
		assertEquals(EnemyNav.Mode.AIR, n.mode(), "끝에서 놓음");
		assertEquals(1300 - 4 * 1600 / zip.a().distance(zip.b()), p.x, 6f, "끝 0.25 m 앞");
		assertEquals(0f, n.speed(), 1e-3, "월드 속도 0");
	}

	@Test
	void ziplineIgnoresJumpWhenGoalSteeplyBelow() {
		EnemyNav.Rope zip = new EnemyNav.Rope(new Vector3f(-300, 300, 60), new Vector3f(1300, 100, 60), EnemyNav.SUB_ZIPLINE);
		EnemyNav n = new EnemyNav(AiTemplate.CAP_ZIPLINE, SKEL, 1000);
		n.setWorld(ropeWorld(zip));
		Vector3f p = new Vector3f(-280, 300 - HAND - 4, 60);
		n.update(p, new Vector3f(), null, false, DT);
		for (int i = 0; i < 30; i++) {
			n.update(p, new Vector3f(), null, false, DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		// 목표가 거의 바로 아래 (Δy < −16, 45° 보다 가파름) → AI 는 점프 버튼 (수평 줄과 같은 분기 0x1CFCE0) 을 누르지만
		// 줄 갱신 0x1C5488 이 짚라인 (0x40000) 이면 바로 돌아간다 (영웅과 같은 코드) → 계속 탄다 [확실]
		float x0 = p.x;
		for (int i = 0; i < 10; i++) {
			n.update(p, new Vector3f(), new Vector3f(p.x + 20, 0, 60), false, DT);
		}
		assertEquals(EnemyNav.Mode.ROPE, n.mode());
		assertTrue(p.x > x0, "낮은 끝으로 계속");
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

	// ------------------------------------------------------------------ 생물끼리 밀어내기 (0x1E2DD8 / 0x1E28B0)

	/** 테스트용 몸: EnemyNav 상태를 그대로 (해골 Sold_020 0.7 / 2.8 / 0.4). */
	static final class Mob implements BodyPush.Body {
		final EnemyNav nav;
		final Vector3f pos;
		boolean solid = true;

		Mob(int caps, Vector3f pos, java.util.List<BodyPush.Body> crowd) {
			this.nav = new EnemyNav(caps, SKEL, 1000);
			nav.setBody(new AiData.Body(0.7f, 2.8f, 0.4f), 1f);
			nav.setWorld(WORLD);
			nav.setCrowd(this, () -> crowd);
			this.pos = pos;
			crowd.add(this);
		}

		public Vector3f feet() {
			return pos;
		}

		public float bodyRadius() {
			return nav.bodyRadius();
		}

		public float bodyHeight() {
			return nav.bodyHeight();
		}

		public float bodyBase() {
			return nav.bodyBase();
		}

		public boolean airborne() {
			return nav.mode() == EnemyNav.Mode.AIR;
		}

		public boolean solid() {
			return solid;
		}

		void tick(Vector3f stick, Vector3f goal) {
			nav.update(pos, stick, goal, false, DT);
		}
	}

	private static float flat(Vector3f a, Vector3f b) {
		return (float) Math.hypot(a.x - b.x, a.z - b.z);
	}

	@Test
	void overlappingEnemiesPushThemselvesApartToSumOfRadii() {
		wallTop = 300f;
		java.util.List<BodyPush.Body> crowd = new java.util.ArrayList<>();
		Mob a = new Mob(0, new Vector3f(100, 0, 200), crowd), b = new Mob(0, new Vector3f(104, 0, 200), crowd);
		assertEquals(0.7f * 16, a.bodyRadius(), 1e-4, "반지름 = Sold +0x234 × 16");
		a.tick(new Vector3f(), null);
		// a 가 자기 갱신에서 깊이 전부 (22.4 − 4) 를 밀려남 (자기만 민다) → b 는 이미 떨어져 있어 그대로
		assertEquals(2 * 11.2f, flat(a.pos, b.pos), 1e-3);
		assertEquals(104f, b.pos.x, 1e-4);
		b.tick(new Vector3f(), null);
		assertEquals(104f, b.pos.x, 1e-4, "b 는 이미 겹치지 않음");
		assertTrue(a.pos.x < 100, "a 는 b 반대쪽(−x)으로");
		assertEquals(0f, a.pos.y, 1e-4, "같은 높이 → 수평으로만");
	}

	@Test
	void groundEnemyWalkingIntoAnotherIsBlocked() {
		java.util.List<BodyPush.Body> crowd = new java.util.ArrayList<>();
		Mob a = new Mob(0, new Vector3f(0, 0, 200), crowd), b = new Mob(0, new Vector3f(60, 0, 200), crowd);
		for (int i = 0; i < 120; i++) {
			a.tick(new Vector3f(1, 0, 0), null);
			b.tick(new Vector3f(), null);
			assertTrue(flat(a.pos, b.pos) >= 22.4f - 1e-3f, "프레임 " + i + " 거리 " + flat(a.pos, b.pos));
		}
		assertEquals(60f - 22.4f, a.pos.x, 0.5f, "b 앞에서 멈춤");
	}

	@Test
	void nonSolidOrAirborneOthersArePassedThrough() {
		java.util.List<BodyPush.Body> crowd = new java.util.ArrayList<>();
		Mob a = new Mob(0, new Vector3f(0, 0, 200), crowd), b = new Mob(0, new Vector3f(5, 0, 200), crowd);
		b.solid = false; // stateMask 1 (목 베기·잡기 처형·스폰 등)
		a.tick(new Vector3f(), null);
		assertEquals(0f, a.pos.x, 1e-4, "단단하지 않은 상대는 통과");
		b.solid = true;
		b.pos.y = 30; // 공중 상대 (낙하 중): 자신 지상이면 밀지 않음 (0x1E2B80)
		b.nav.setWorld(null);
		EnemyNav.Mode m = b.nav.mode();
		assertNotEquals(EnemyNav.Mode.GROUND, m);
		a.tick(new Vector3f(), null);
		assertEquals(0f, a.pos.x, 1e-4, "공중 상대는 지상 쪽을 밀지 않음");
	}

	@Test
	void airborneEnemyLandingOnAnotherIsPushedOff() {
		java.util.List<BodyPush.Body> crowd = new java.util.ArrayList<>();
		Mob a = new Mob(0, new Vector3f(2, 60, 200), crowd), b = new Mob(0, new Vector3f(0, 0, 200), crowd);
		b.tick(new Vector3f(), null);
		for (int i = 0; i < 240 && a.nav.mode() != EnemyNav.Mode.GROUND; i++) {
			a.tick(new Vector3f(), null);
		}
		assertEquals(EnemyNav.Mode.GROUND, a.nav.mode());
		assertTrue(flat(a.pos, b.pos) >= 22.4f - 1e-2f, "머리 위에 서지 않고 옆으로 밀려 착지 " + flat(a.pos, b.pos));
	}

	@Test
	void climbersOnSameWallPushApartInsideTheWallPlane() {
		java.util.List<BodyPush.Body> crowd = new java.util.ArrayList<>();
		Mob a = new Mob(AiTemplate.CAP_CLIMB, new Vector3f(0, 150, 6), crowd);
		Mob b = new Mob(AiTemplate.CAP_CLIMB, new Vector3f(3, 150, 6), crowd);
		b.tick(new Vector3f(), null);
		a.tick(new Vector3f(), null);
		assertEquals(EnemyNav.Mode.WALL, a.nav.mode());
		assertEquals(EnemyNav.Mode.WALL, b.nav.mode());
		for (int i = 0; i < 10; i++) {
			a.tick(new Vector3f(), null);
			b.tick(new Vector3f(), null);
		}
		assertTrue(a.pos.distance(b.pos) >= 22.4f - 1e-2f, "벽 위에서도 갈라짐 " + a.pos.distance(b.pos));
		assertEquals(EnemyNav.WALL_OFF, a.pos.z, 1e-3, "벽 평면 성분만 — 벽에서 떨어지지 않음");
		assertEquals(EnemyNav.WALL_OFF, b.pos.z, 1e-3);
		assertEquals(EnemyNav.Mode.WALL, a.nav.mode());
	}

	@Test
	void ropeRidersAreNotPushed() {
		// 줄 이동 0x1C5848 은 0x1E2DD8 을 부르지 않는다
		java.util.List<BodyPush.Body> crowd = new java.util.ArrayList<>();
		Mob a = new Mob(AiTemplate.CAP_ROPE, new Vector3f(0, 105, 60), crowd);
		Mob b = new Mob(AiTemplate.CAP_ROPE, new Vector3f(2, 105, 60), crowd);
		crowd.clear(); // 잡기 전 공중에서는 밀리므로 따로 잡게 한 뒤
		a.tick(new Vector3f(), null);
		b.tick(new Vector3f(), null);
		crowd.add(a);
		crowd.add(b);
		for (int i = 0; i < 30; i++) {
			a.tick(new Vector3f(), null);
			b.tick(new Vector3f(), null);
		}
		assertEquals(EnemyNav.Mode.ROPE, a.nav.mode());
		assertEquals(EnemyNav.Mode.ROPE, b.nav.mode());
		assertEquals(2f, flat(a.pos, b.pos), 1e-3);
	}

	@Test
	void heroBodyIsKratosSold000() {
		BodyPush.Body h = BodyPush.hero(new Vector3f(), () -> false, null);
		assertEquals(9.6f, h.bodyRadius(), 1e-4);
		assertEquals(35.2f, h.bodyHeight(), 1e-4);
		assertEquals(6.4f, h.bodyBase(), 1e-4);
		assertTrue(h.solid(), "기술 없음 → CombatFileSet 기본 0x1F (비트 2)");
		// 영웅(r 9.6) 이 해골(r 11.2) 과 겹치면 영웅이 밀려남
		java.util.List<BodyPush.Body> crowd = new java.util.ArrayList<>();
		Mob s = new Mob(0, new Vector3f(10, 0, 0), crowd);
		s.tick(new Vector3f(), null); // 착지 (공중 상대는 지상 영웅을 밀지 않음)
		Vector3f p = new Vector3f();
		assertTrue(BodyPush.push(h, p, crowd, null));
		assertEquals(20.8f, flat(p, s.pos), 1e-3);
	}
}
