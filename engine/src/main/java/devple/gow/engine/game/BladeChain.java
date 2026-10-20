package devple.gow.engine.game;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import devple.gow.tools.level.SurfaceAttr;

import java.util.ArrayList;
import java.util.List;

/**
 * 혼돈의 블레이드 사슬 (45 문서 §3). 원작 블레이드 객체 (Blades_Init 0x1F9C38, 갱신 0x1FB460 ← 0x1FDB18) 의 GL 없는 부분:
 * <ul>
 *   <li>블레이드 행렬 = 상태 (+0x92: 0 등 / 1 손 / 2 손 밖) 가 고른 관절 행렬을 향해 회전 slerp · 위치 lerp (비율 +0x98:
 *       등·손 1.0, 손 밖 {@code Blade Damping} 0.4 / 프레임). 애니의 무기 로케이터 (lWeapOH) 를 그대로 쓰지 않고 따라간다.</li>
 *   <li>사슬 = 손목 lChain 에서 블레이드 쪽 lChainW 까지 3차 베지어 (제어점 = 양 끝 + 각 끝 Z 축 × 0.33·현 길이).
 *       물리는 없다.</li>
 *   <li>사슬 고리 띠 (0x1FC870): 마디 수 = 길이 근사 / {@code Segment Length}, t = 1 → 0 표본마다 시선에 수직인 띠.</li>
 *   <li>궤적 (0x1FC070): 베지어 t = 0.2…1 의 굽은 부분을 부채꼴로 채우고 칼끝 (블레이드 −Z × Weapon Length) 까지 잇는다.</li>
 * </ul>
 * 좌표는 엔진 월드 (원작 단위 = 1/16 m). 원작은 정점을 ×16 고정소수 (vftoi0) 로 보내지만 값은 같다.
 */
public final class BladeChain {

	/** 관절 월드 조회 (영웅 월드 × 관절). */
	@FunctionalInterface
	public interface Joints {
		boolean world(String joint, Matrix4f out);
	}

	public static final int BACK = 0, IN_HAND = 1, OUT_HAND = 2;
	/** 상태 전환 거리 (0x40B33333 = 5.6 단위): lWeapOH 가 lWeapIH / 등 관절에 이만큼 가까우면 손 / 등. */
	public static final float SWITCH_DIST = 5.6f;
	/** 제어점 길이 = 현 × 0.33 (0x3EA8F5C3). */
	public static final float CTRL = 0.33f;
	/** 궤적 부채꼴의 베지어 표본 t (0x1FC070 의 번스타인 계수 상수). */
	public static final float[] FAN_T = {0.2f, 0.325f, 0.45f, 0.575f, 0.7f, 0.775f, 0.85f, 0.925f, 1f};
	/** 사슬 띠 최대 표본 (원작 정점 버퍼 0x88 = 띠 2 개 × 68 정점, 표본 하나 = 정점 2) [근사: 띠 이음 중복 무시]. */
	public static final int MAX_SAMPLES = 68;
	/**
	 * 블레이드마다 관절 이름 (튜닝 /TweakTemplates/Attachment/000 의 +0xA8 lChain, +0xD8 lChainW, +0x48 lWeapIH, +0x78 lWeapOH,
	 * +0x108 leftBladeBack — 0x201170 이 Blades_Init 의 a3/t0/t1/t2/t3 로 넘김, 오른쪽은 +0x18 씩 뒤).
	 */
	public static final String[][] JOINTS = {
			{"lChain", "lChainW", "lWeapIH", "lWeapOH", "leftBladeBack"},
			{"rChain", "rChainW", "rWeapIH", "rWeapOH", "rightBladeBack"}};

	/** 골반 관절 (Blades_Init +0x8E): 벽·적 질의의 높이. */
	public static final String PELVIS = "Pelvis";
	/** 블레이드 점 = 블레이드 위치 + (0 − Z) × 0.5 × 16 (0x1FB044–0x1FB06C) — 블레이드 라이트와 같은 점. */
	public static final float BLADE_POINT_BACK = 8f;
	/** 적 스윕 표본 간격 (0x40CCCCCD = 6.4 단위, 0x1FB230). */
	public static final float SWEEP_STEP = 6.4f;
	/**
	 * 벽 질의 면 제외 마스크 (0x3309F4 = 0x0B130000, 0x1FB2F0) [확실]: AIBlock · ClimbGuide · NoPlayerCollision · GeneralGuide · NoDiving · CombatGuide.
	 * 영웅 이동 질의와 달리 Water · Death 는 막는다.
	 */
	public static final long WALL_EXCLUDE = SurfaceAttr.fromRecord(0x0B130000, 0, 0);

	/**
	 * 벽 질의 종류 마스크 (0x3309F0) [확실: 0x1FB2D0 / 0x1FB3B4]. 충돌 세계 0x29C978 의 엔트리 범주 = 소유 GO +0xF4 (0 이면 0x80000000, 0x11D608)
	 * 와 AND 해 0 이 아닌 엔트리만 본다 (0x175648). 첫 질의 0xFF9CE00F = 범주 비트 0–3 (영웅 1 · 적 1|2 …) · 13–15 · 18–20 · 23–31 (0x80000000 = 범주 없는 GO 와
	 * 정적 시트), 빠지는 것 = 비트 4–12 · 16·17 · 21·22. 둘째 질의 0x80000000 = 범주 없는 GO·정적 시트만.
	 */
	public static final int TYPES_WALL = 0xFF9CE00F, TYPES_WORLD = 0x80000000;
	/** 엔트리 범주: 소유 GO +0xF4 = 0 (문·발판 같은 일반 CDV GO, 정적 시트) → 0x80000000 [확실: 0x11D608 / 정적 시트는 추정], 적 = 1 또는 2 (AI 초기화 0x1D0890). */
	public static final int CAT_WORLD = 0x80000000, CAT_CREATURE = 0x3;

	/**
	 * 벽 질의 결과 (접촉 기록 0x2C8F10: +0x00 점, +0x10 면 법선, +0x24 개체, +0x2C 재질 엔트리 → SheetFX).
	 *
	 * @param bit31 맞은 개체가 없거나 (정적 시트) 개체 +0xF4 bit 31 (0x1FB344 – 0x1FB350) — 이때만 첫 질의 점으로 당긴다
	 */
	public record WallHit(Vector3f point, Vector3f normal, int sheetFx, boolean bit31) {
	}

	/** 블레이드 벽 질의 (0x1FAFC8 → 선분 질의 0x11E190, 가장 가까운 충돌 = 모드 2). */
	@FunctionalInterface
	public interface Walls {
		/**
		 * from → to 의 가장 가까운 충돌 (면 표면 속성 &amp; exclude ≠ 0 인 면은 건너뜀, 엔트리 범주 &amp; types = 0 인 것도 건너뜀).
		 *
		 * @return 없으면 null
		 */
		WallHit cast(Vector3f from, Vector3f to, long exclude, int types);
	}

	/** /Player/ 튜닝 (Player_RegisterTweaks 0x1D5B8C…: 0x29F294…0x29F2B4). */
	public float damping = 0.4f, scaleBack = 1, scaleIn = 1, scaleOut = 1;
	/** 미터 (× 16 = 단위). */
	public float segmentLength = 0.25f, linkDiameter = 0.13f, glowDiameter = 0.18f;

	public final Side[] sides = {new Side(0), new Side(1)};

	public static BladeChain fromTweaks(Tweaks tw) {
		BladeChain c = new BladeChain();
		if (tw != null) {
			String n = "/Player/";
			c.damping = tw.f(n, "Blade Damping", c.damping);
			c.scaleBack = tw.f(n, "Blade Scale On Back", c.scaleBack);
			c.scaleIn = tw.f(n, "Blade Scale In Hand", c.scaleIn);
			c.scaleOut = tw.f(n, "Blade Scale Out Hand", c.scaleOut);
			c.segmentLength = tw.f(n, "Segment Length", c.segmentLength);
			c.linkDiameter = tw.f(n, "Link Diameter", c.linkDiameter);
			c.glowDiameter = tw.f(n, "Glow Diameter", c.glowDiameter);
		}
		return c;
	}

	/** 블레이드 하나 (원작 0xF0 바이트 객체). */
	public final class Side {
		public final int index;
		/** +0x92 상태. */
		public int mode = BACK;
		/** 블레이드 행렬 (배율 없음, 원작 +0x00..+0x30). */
		public final Matrix4f blade = new Matrix4f();
		/** +0x9C 현재 배율, +0xA0 목표 배율. */
		public float scale = 1, scaleTarget = 1;
		/** +0xA8 bit 0: 사슬을 그림 (손·손 밖). */
		public boolean chainVisible;
		/** 베지어 제어점 P0 (손목) … P3 (블레이드 쪽 사슬 끝). */
		public final Vector3f[] ctrl = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
		/** P3 에서 사슬이 나가는 방향 (lChainW Z, 정규화). */
		public final Vector3f endDir = new Vector3f();
		/** 블레이드 Z 축 (정규화): 칼끝 = P3 − Z · Weapon Length. */
		public final Vector3f bladeZ = new Vector3f(0, 0, 1);
		boolean init;
		/** 이번 틱 첫 벽 질의가 맞음 → 그 점에 불꽃 0x202248 (맞은 것이 적 몸이어도). */
		public boolean wallHit;
		/** 이번 틱 블레이드를 당김 (첫 질의가 bit 31 이거나, 아니면 둘째 질의 0x80000000 이 맞음). */
		public boolean pulled;
		/** 첫 벽 질의 충돌점·면 법선·면 SheetFX (wallHit 일 때) — 불꽃 위치. */
		public final Vector3f wallPoint = new Vector3f(), wallNormal = new Vector3f();
		public int wallSheetFx;
		/**
		 * 블레이드 라이트 위치 = 당기기 전 블레이드 점 B, 방향 = O − B (0x1FB10C – 0x1FB13C → 0x151928 / 0x151978) [확실].
		 * 블레이드 GO 가 보일 때 상태와 관계없이 (손 밖 검사 0x1FB148 앞) 매 틱. lightValid = 이번 틱 정함.
		 */
		public final Vector3f lightPoint = new Vector3f(), lightDir = new Vector3f();
		public boolean lightValid;
		/**
		 * +0xA4 피 묻은 블레이드 시간 (초): Blades_Init 0x1FA5B0 이 100, 0x1FB508 이 매 프레임 += 1/60, 사슬 적 스윕이 무엇이든 맞으면
		 * (콜백 0x1FAF40 → 0x337C18 = 1) 0 (0x1FB2C8) [확실].
		 */
		public float bloodTime = 100f;
		/** 블레이드 쪽 +0x50: 당긴 양 (충돌점 − 블레이드 점), 매 틱 0 으로 시작. */
		public final Vector3f pull = new Vector3f();
		/** 적 스윕 (0x1FB1B4 – 0x1FB2C0): 시작점 = (영웅 x, 골반 y, 영웅 z), 끝점들 = 이전 → 지금 블레이드 점 사이 (t = 1 부터). 손 밖이 아니면 비어 있음. */
		public final Vector3f sweepFrom = new Vector3f();
		public final List<Vector3f> sweepTo = new ArrayList<>();
		private final Quaternionf rot = new Quaternionf();
		private final Vector3f pos = new Vector3f();

		Side(int index) {
			this.index = index;
		}

		/** GO 행렬 = 블레이드 행렬 × 배율 (+0x9C). */
		public Matrix4f world(Matrix4f out) {
			return out.set(blade).scale(scale);
		}

		public boolean valid() {
			return init;
		}

		/** 사슬 적 스윕이 맞음 (0x1FB2C0: 0x337C18 이면 +0xA4 = 0). */
		public void bleed() {
			bloodTime = 0;
		}

		/**
		 * 블레이드 GO 부착물 종류 1 blooddrip / 2 bloodspray 의 생성률 배율 (0x1FB508 – 0x1FB574 → 0x201FB0: 부착 파티클 컴포넌트 +0xB4) [확실]:
		 * blooddrip = clamp(4 − t, 0, 1) (3 초까지 1, 4 초에 0), bloodspray = clamp(2 · (1 − t / 0.1), 0, 1) (0.05 초까지 1, 0.1 초에 0).
		 * 그 밖의 종류는 1.
		 */
		public float blood(int kind) {
			return bloodRate(kind, bloodTime);
		}
	}

	public static final int ATTACH_BLOODDRIP = 1, ATTACH_BLOODSPRAY = 2;

	/** {@link Side#blood} 의 식 (t = +0xA4). */
	public static float bloodRate(int kind, float t) {
		float v = switch (kind) {
			case ATTACH_BLOODDRIP -> 4f - t;
			case ATTACH_BLOODSPRAY -> 2f * (1f - t / 0.1f);
			default -> 1f;
		};
		return Math.max(0f, Math.min(1f, v));
	}

	public void reset() {
		for (Side s : sides) {
			s.init = false;
		}
	}

	/**
	 * 한 틱 (0x1FB460, 원작은 프레임마다 1회 = 1/60 초).
	 *
	 * @param hasMove  전투 기술 진행 중 (combatant +0x4C)
	 * @param keepMode 현재 기술 flags (+0x0E) &amp; 0x80: 상태를 바꾸지 않음
	 */
	public void update(Joints j, boolean hasMove, boolean keepMode, float dt) {
		update(j, hasMove, keepMode, dt, null, null);
	}

	/**
	 * 한 틱 + 블레이드 벽 충돌 (0x1FAFC8).
	 *
	 * @param root  영웅 GO 위치 (0x243378 → +0x30), null 이면 벽·적 질의 없음
	 * @param walls 레벨 충돌 질의, null 이면 벽 질의 없음
	 */
	public void update(Joints j, boolean hasMove, boolean keepMode, float dt, Vector3f root, Walls walls) {
		Matrix4f pelvis = new Matrix4f();
		boolean havePelvis = root != null && j.world(PELVIS, pelvis);
		Matrix4f chain = new Matrix4f(), chainW = new Matrix4f(), ih = new Matrix4f(), oh = new Matrix4f(), back = new Matrix4f();
		for (Side s : sides) {
			s.bloodTime += dt; // 0x1FB508: +0xA4 += 1/60 (프레임마다, 적 스윕보다 먼저)
			String[] n = JOINTS[s.index];
			if (!j.world(n[0], chain) || !j.world(n[1], chainW) || !j.world(n[2], ih) || !j.world(n[3], oh) || !j.world(n[4], back)) {
				continue;
			}
			if (!s.init) {
				// 0x1FA5B0: 행렬 = 등 관절, +0x94..+0xA0 = 1, 상태 0
				s.init = true;
				s.mode = BACK;
				back.getNormalizedRotation(s.rot);
				back.getTranslation(s.pos);
				s.scale = s.scaleTarget = 1;
			}
			if (!keepMode) {
				s.mode = nextMode(s.mode, hasMove, dist(oh, ih), dist(oh, back));
			}
			Matrix4f target = switch (s.mode) {
				case IN_HAND -> ih;
				case OUT_HAND -> oh;
				default -> back;
			};
			float rate = s.mode == OUT_HAND ? damping : 1f;
			s.scaleTarget = s.mode == IN_HAND ? scaleIn : s.mode == OUT_HAND ? scaleOut : scaleBack;
			s.chainVisible = s.mode != BACK; // 0x1FD9F8 / 0x1FDAE8
			float frames = dt * 60f;
			float t = step(rate, frames);
			Vector3f pos0 = new Vector3f(s.pos); // 이전 블레이드 GO 위치 (0x1FB200: GO +0x50, 이번 틱 GO 행렬은 0x1FAFC8 뒤에 쓴다)
			// 0x184388 slerp (이전 → 목표, t), 위치 선형 (+0x30 += (목표 − 이전) · t)
			Quaternionf tr = target.getNormalizedRotation(new Quaternionf());
			s.rot.slerp(tr, t).normalize();
			s.pos.lerp(target.getTranslation(new Vector3f()), t);
			Vector3f prevPos = new Vector3f(pos0);
			s.wallHit = false;
			s.pulled = false;
			s.lightValid = false;
			s.pull.zero();
			s.sweepTo.clear();
			if (havePelvis) {
				Vector3f z = new Vector3f(0, 0, 1).rotate(s.rot);
				Vector3f from = new Vector3f(root.x, pelvis.m31(), root.z);
				// 0x1FB0F0 – 0x1FB13C: 블레이드 라이트 = 당기기 전 블레이드 점 B, 방향 O − B (상태 검사 0x1FB148 앞)
				s.lightPoint.set(z).mul(-BLADE_POINT_BACK).add(s.pos);
				s.lightDir.set(from).sub(s.lightPoint);
				s.lightValid = true;
				if (s.mode == OUT_HAND) {
					collide(s, from, prevPos, z, walls);
				}
			}
			// +0x9C += (+0xA0 − +0x9C) · 0.1 (프레임마다)
			s.scale += (s.scaleTarget - s.scale) * step(0.1f, frames);
			s.blade.translationRotate(s.pos.x, s.pos.y, s.pos.z, s.rot);
			// 사슬 끝: lChainW 를 lWeapOH 기준으로 옮겨 따라가는 블레이드 행렬에 붙인다 (0x185490 역행렬 → 곱 2번)
			Matrix4f rel = new Matrix4f(oh).invert().mul(chainW);
			Matrix4f end = new Matrix4f(s.blade).mul(rel);
			s.blade.getColumn(2, s.bladeZ).normalize();
			end.getColumn(2, s.endDir).normalize();
			Vector3f d0 = chain.getColumn(2, new Vector3f()).normalize().negate();
			controlPoints(chain.getTranslation(new Vector3f()), d0, end.getTranslation(new Vector3f()), s.endDir, s.ctrl);
		}
	}

	/**
	 * 0x1FAFC8 (상태 2 = 손 밖일 때만, 0x1FB148) [확실: 코드].
	 * <ol>
	 *   <li>블레이드 점 B = 블레이드 위치 − Z × 8, 시작점 O = (영웅 x, 골반 y, 영웅 z).</li>
	 *   <li>적 스윕 끝점: 이전 블레이드 점 (이전 GO 위치 − 지금 Z × 8) → B 를 간격 s = min(1, 6.4 / |B − 이전|) 로,
	 *       t = 1, 1 − s, … (다음 t − s ≥ 0 인 동안) — 원작 루프 그대로 (t = 0 쪽 마지막 구간은 빠질 수 있다).</li>
	 *   <li>벽 (0x1FB2CC): O → B 선분 질의 (면 제외 {@link #WALL_EXCLUDE}, 종류 {@link #TYPES_WALL}, 가장 가까운 것). 맞으면 그 점에 불꽃
	 *       ({@link Side#wallHit}, 0x202248 — 적 몸이어도). 맞은 개체가 없거나 +0xF4 bit 31 이면 블레이드 위치 = 충돌점 + Z × 8
	 *       (= 블레이드 점을 충돌점에 놓음), +0x50 = 충돌점 − B. 아니면 종류 {@link #TYPES_WORLD} 로 다시 질의해 맞으면 같은 식으로 당긴다 (불꽃 없음).
	 *       블레이드 상태 행렬 (+0x30) 에 쓰므로 다음 틱 slerp 의 시작도 당긴 위치.</li>
	 * </ol>
	 */
	void collide(Side s, Vector3f from, Vector3f prevPos, Vector3f z, Walls walls) {
		Vector3f back = new Vector3f(z).mul(-BLADE_POINT_BACK);
		Vector3f b = new Vector3f(s.pos).add(back);
		Vector3f prev = new Vector3f(prevPos).add(back);
		s.sweepFrom.set(from);
		sweepPoints(prev, b, s.sweepTo);
		if (walls == null) {
			return;
		}
		WallHit h = walls.cast(from, b, WALL_EXCLUDE, TYPES_WALL);
		if (h == null) {
			return;
		}
		s.wallHit = true;
		s.wallPoint.set(h.point());
		s.wallNormal.set(h.normal());
		s.wallSheetFx = h.sheetFx();
		WallHit p = h.bit31() ? h : walls.cast(from, b, WALL_EXCLUDE, TYPES_WORLD); // 0x1FB3A8 – 0x1FB420
		if (p == null) {
			return;
		}
		s.pulled = true;
		s.pull.set(p.point()).sub(b);
		s.pos.add(s.pull);
	}

	/** 적 스윕 끝점 (0x1FB200 – 0x1FB2B8): prev → cur 사이, t = 1 부터 s = min(1, 6.4 / 거리) 씩, 다음 t − s ≥ 0 인 동안. */
	public static void sweepPoints(Vector3f prev, Vector3f cur, List<Vector3f> out) {
		float d = prev.distance(cur);
		float s = d > SWEEP_STEP ? SWEEP_STEP / d : 1f;
		float t = 1f;
		do {
			out.add(new Vector3f(prev).lerp(cur, t));
			t -= s;
		} while (t - s >= 0f);
	}

	/** 0x1FB73C 상태 선택. dIH = |lWeapOH − lWeapIH|, dBack = |lWeapOH − 등 관절|. */
	public static int nextMode(int mode, boolean hasMove, float dIH, float dBack) {
		if (hasMove) {
			return dIH < SWITCH_DIST ? IN_HAND : dBack < SWITCH_DIST ? BACK : OUT_HAND;
		}
		// 기술이 없으면: 손 → 등 가까우면 등, 손 밖 → 손이나 등에 가까우면 손, 등은 그대로
		if (mode == IN_HAND && dBack < SWITCH_DIST) {
			return BACK;
		}
		if (mode == OUT_HAND && (dIH < SWITCH_DIST || dBack < SWITCH_DIST)) {
			return IN_HAND;
		}
		return mode;
	}

	/** 프레임당 비율 r 을 프레임 수만큼 (1/60 초면 r 그대로). */
	static float step(float r, float frames) {
		if (r >= 1f) {
			return 1f;
		}
		if (r <= 0f) {
			return 0f;
		}
		return 1f - (float) Math.pow(1f - r, Math.max(0f, frames));
	}

	private static float dist(Matrix4f a, Matrix4f b) {
		return a.getTranslation(new Vector3f()).distance(b.getTranslation(new Vector3f()));
	}

	/** P1 = P0 + d0·L, P2 = P3 + d3·L, L = 0.33·|P3 − P0| (0x1FBD04…0x1FBDE8). */
	public static void controlPoints(Vector3f p0, Vector3f d0, Vector3f p3, Vector3f d3, Vector3f[] out) {
		float l = p0.distance(p3) * CTRL;
		out[0].set(p0);
		out[1].set(d0).mul(l).add(p0);
		out[2].set(d3).mul(l).add(p3);
		out[3].set(p3);
	}

	public static Vector3f bezier(Vector3f[] p, float t, Vector3f out) {
		float u = 1 - t;
		float b0 = u * u * u, b1 = 3 * u * u * t, b2 = 3 * u * t * t, b3 = t * t * t;
		return out.set(p[0].x * b0 + p[1].x * b1 + p[2].x * b2 + p[3].x * b3,
				p[0].y * b0 + p[1].y * b1 + p[2].y * b2 + p[3].y * b3,
				p[0].z * b0 + p[1].z * b1 + p[2].z * b2 + p[3].z * b3);
	}

	/** 길이 근사 = (현 + 제어 다각형) / 2 (0x1FC9EC). */
	public static float arcLength(Vector3f[] p) {
		return 0.5f * (p[0].distance(p[3]) + p[0].distance(p[1]) + p[1].distance(p[2]) + p[2].distance(p[3]));
	}

	/** 마디 수 (실수) = 길이 / (Segment Length × 16). ≤ 1 이면 사슬을 숨긴다 (0x1FDA08). */
	public float segments(Vector3f[] p) {
		return arcLength(p) / (segmentLength * 16f);
	}

	/** 사슬 빛 밝기 (+0xC8 색 RGB) = clamp(마디 / 16, 0, 1). */
	public static float glowLevel(float segments) {
		return Math.max(0f, Math.min(1f, segments / 16f));
	}

	/**
	 * 사슬 띠 표본 (0x1FCE70 루프): t = 1 (블레이드) 부터 1/마디씩 줄여 0 (손목) 까지. 옆 벡터 = 정규화(cross(P − 눈, 접선)),
	 * 첫 표본 접선 = d3, 다음부터 P − 이전 P.
	 *
	 * @return 표본마다 {x, y, z, 옆 x, 옆 y, 옆 z, t}
	 */
	public static List<float[]> samples(Vector3f[] p, Vector3f d3, Vector3f eye, float segments) {
		List<float[]> out = new ArrayList<>();
		if (segments <= 1f) {
			return out;
		}
		float dt = 1f / segments;
		float t = 1f;
		Vector3f prev = new Vector3f(p[3]);
		Vector3f cur = new Vector3f(p[3]);
		Vector3f tan = new Vector3f(d3);
		while (true) {
			Vector3f side = new Vector3f(cur).sub(eye).cross(tan);
			if (side.lengthSquared() > 1e-12f) {
				side.normalize();
			}
			out.add(new float[]{cur.x, cur.y, cur.z, side.x, side.y, side.z, t});
			if (t <= 0f || out.size() >= MAX_SAMPLES) {
				break;
			}
			t = Math.max(0f, t - dt);
			prev.set(cur);
			bezier(p, t, cur);
			tan.set(cur).sub(prev);
		}
		return out;
	}

	/**
	 * 궤적 정점 (0x1FC070): GS 삼각형 띠 2 개 (각 첫 두 정점 ADC). 띠 1 = [A, B, C, A, D, A, E] (u = 0, .125, .25, 0, .375, 0, .5),
	 * 띠 2 = [E, F, G, E, H, E, I, E, T] (u = .5 + f·{0, .125, .25, 0, .375, 0, .5, 0}, T = 1), v 는 모두 1.
	 * A…I = 베지어 {@link #FAN_T}, T = 칼끝, f = |I − E| / (|I − E| + 블레이드 길이).
	 *
	 * @param bladeLen Weapon Length × 16 (단위)
	 * @return 띠 2 개, 정점마다 {x, y, z, u}
	 */
	public static float[][][] trail(Vector3f[] p, Vector3f bladeZ, float bladeLen) {
		Vector3f[] s = new Vector3f[FAN_T.length];
		for (int i = 0; i < s.length; i++) {
			s[i] = bezier(p, FAN_T[i], new Vector3f());
		}
		Vector3f tip = new Vector3f(bladeZ).mul(-bladeLen).add(p[3]);
		float ie = s[8].distance(s[4]);
		float f = ie / (ie + bladeLen);
		int[] i1 = {0, 1, 2, 0, 3, 0, 4};
		float[] u1 = {0, .125f, .25f, 0, .375f, 0, .5f};
		float[][] a = new float[7][];
		for (int i = 0; i < 7; i++) {
			Vector3f v = s[i1[i]];
			a[i] = new float[]{v.x, v.y, v.z, u1[i]};
		}
		int[] i2 = {4, 5, 6, 4, 7, 4, 8, 4};
		float[] k2 = {0, .125f, .25f, 0, .375f, 0, .5f, 0};
		float[][] b = new float[9][];
		for (int i = 0; i < 8; i++) {
			Vector3f v = s[i2[i]];
			b[i] = new float[]{v.x, v.y, v.z, .5f + f * k2[i]};
		}
		b[8] = new float[]{tip.x, tip.y, tip.z, 1f};
		return new float[][][]{a, b};
	}

	/**
	 * GS 삼각형 띠 → 삼각형 목록 (정점 인덱스 3 개씩). 원작 그대로 (A,B,C)·(B,C,A) 같은 겹친 삼각형도 두 번 그린다 (가산이라 두 배 밝음),
	 * 같은 정점이 두 번 든 퇴화 삼각형은 면적 0.
	 */
	public static List<int[]> stripTriangles(int n) {
		List<int[]> l = new ArrayList<>();
		for (int i = 2; i < n; i++) {
			l.add(new int[]{i - 2, i - 1, i});
		}
		return l;
	}
}
