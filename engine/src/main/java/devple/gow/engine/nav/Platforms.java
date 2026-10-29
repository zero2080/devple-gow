package devple.gow.engine.nav;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.SurfaceAttr;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 움직이는 발판·엘리베이터: Animator 엔티티가 애니를 트는 레벨 오브젝트의 CDV(동적 충돌, BallHull 0) hull 윗면이 바닥이 된다.
 * hull 은 자기 관절(BallHull off[6]) 로컬 좌표이고, 관절 월드 = 오브젝트 애니 포즈 × 인스턴스 행렬 (38 문서 §5).
 * 영웅이 그 위에 서 있으면 지지 관절의 이번 틱 이동만큼 같이 옮긴다 (carry).
 * 바닥 = 수직 선분이 hull 로 들어가는 평면의 재질 (평면별 off[9] → 재질 엔트리 = 시트 표면 레코드와 같은 배치, +0x18 bitfield0) 이
 * 영웅 질의 제외 마스크 {@link #HERO_EXCLUDE} 에 걸리지 않는 것 [확실: 0x1E3298 / 0x1E2100 → 0x139218 의 (재질 bitfield0 &amp; 0x3309F4) 거름].
 * 이전의 이름 규칙 (재질 이름에 ground/floor) 은 폐기 — PAND04AA 엘리베이터 바닥 {@code EntitiesPand04AA_SheetM} 은 Ground 인데 빠졌다 (43 §13.10).
 */
public final class Platforms {

	/** 지지 바닥: 높이, 오브젝트, hull 관절, 윗면 표면 속성, 윗면 재질 번호 (CDV 재질 엔트리, 없으면 −1). */
	public record Support(float y, Body body, int joint, long attrs, int material) {
		/**
		 * 이 윗면 재질의 접촉 피해 (영웅 "Damage" / 적 "AIDamage") 와 SheetFX. 원작 바닥 질의 (0x1E3298 · 0x1E36B0 · 0x1E2F20 · 0x1E1F28) 는 정적 시트와
		 * GO 충돌 인스턴스 (CDV) 를 같이 보고, 맞은 면의 재질을 접촉 0x1E1DF0 에 넘겨 Damage 를 대기 피해로 건다 (43 §46.1-1) [확실] — 밟고 선 CDV 윗면도 피해를 준다
		 * (PAND04C 가시 함정 gospikes 1024: 30 초 뒤 솟는 가시가 영웅을 들어 올리며 죽인다, 영상 5화 1708, 43 §53).
		 */
		public Touch touch(boolean ai) {
			BallHull h = body.hull;
			int[] dmg = ai ? h.materialAiDamage : h.materialDamage;
			if (material < 0 || material >= dmg.length || dmg[material] <= 0) {
				return Touch.NONE;
			}
			return new Touch(dmg[material], material < h.materialFx.length ? h.materialFx[material] : -1);
		}
	}

	/** 오브젝트 하나 (CDV hull + 애니). */
	public static final class Body {
		public final String owner;
		final Skeleton skeleton;
		final float[] inst;
		final AnimationSet anims;
		final BallHull hull;
		/** hull 별 평면별 영웅 질의에 걸리는지 (재질이 제외 마스크에 걸리지 않음). */
		final boolean[][] heroPlane;
		/** hull 별 평면별 표면 속성 마스크. */
		final long[][] planeAttrs;
		Animation anim;
		float time;
		boolean playing;
		/** 재생 속도 (음수 = 역재생), 반복 (0x2), 왕복 (0x4) — Animator 슬롯 1·2 (원작 0x1AB620). */
		float rate = 1;
		boolean loop, pingPong;
		float[][] joints;
		float[][] prev;
		/**
		 * GO 부모 링크 ({@code GoInstance.parentLink}, 0x58): 부모 몸·관절과 그 관절의 처음 월드 역행렬. 부모 관절이 움직인 만큼
		 * (follow = 처음⁻¹ · 지금) 이 몸의 관절 월드에 곱한다 — 정박한 배 goshipgroup 이 goshiptrans "Anim" 관절을 따라 부두로 (ATHN02A).
		 */
		Body parent;
		int parentJoint;
		float[] parentRestInv;
		/** 부모 관절 이동 (null = 처음 자리 그대로), 애니 없는 몸의 처음 관절 월드. */
		float[] follow;
		float[][] baseJoints;
		/** 이번 틱 부모를 따라 움직였는가. */
		boolean followMoved;
		/** 스크립트 Animator·R1 오브젝트 (false = 정지 CDV 오브젝트: 도착 자세 맞추기 대상 아님). */
		public boolean scripted = true;
		/** 충돌 켜짐 — 스크립트 Vis Attr11_0 (1 켬 / 2 끔: "collisionOFF", "DoorCollOff") [추정: 슬롯 이름은 미확정, 데이터 용례로]. */
		public boolean solid = true;
		/** TAndF 물리 들어 올림 높이 (처음 자리 위로, {@link Platforms#lift}). */
		float lift;
		/**
		 * 밀린·실려 간 상자의 처음 자리에서의 변환 (행벡터 4×4, 관절 월드 = 처음 · place) — 영웅이 민 이동 ({@link Platforms#shift}, 원작 0x211A10 이
		 * GO 위치를 옮김) 과 발판 관절에 실린 yaw 회전·이동 ({@link Platforms#ride}, 원작 0x1E6248).
		 */
		float[] place = Mat4.identity();
		/**
		 * SCR_GhostTrail (0x246918 — 생성할 때의 자세로 꼬리 관절을 정함, 0x20DB38) — 그릴 관절은 {@link #drawJoints}. null = 없음.
		 * 원작 갱신은 GO +0x4 bit 0 일 때만 건너뛰고 모델 숨김 (Vis 슬롯 0 = 부품 0xF/0x1C) 과는 무관하다 [확실: 0x246918 · 0x1ABAA8].
		 */
		public devple.gow.engine.fx.GhostTrail ghost;

		/** 그릴 관절 월드: 꼬리 길 ({@link #ghost}) 을 이번 틱 갱신해 꼬리 관절을 길 위로 (없으면 {@link #joints()} 그대로). */
		public float[][] drawJoints() {
			return ghost == null ? joints : ghost.apply(joints);
		}

		/** 인스턴스 월드 행렬 (행벡터, {@code GoInstance.matrix()}) — Animator 이펙트 기준 (GameScene.startAnimatorEffects). */
		public float[] instanceMatrix() {
			return inst;
		}

		Body(String owner, Skeleton sk, float[] inst, AnimationSet anims, BallHull hull) {
			this.owner = owner;
			this.skeleton = sk;
			this.inst = inst;
			this.anims = anims;
			this.hull = hull;
			int hn = hull == null ? 0 : hull.hulls.size();
			heroPlane = new boolean[hn][];
			planeAttrs = new long[hn][];
			for (int h = 0; h < hn; h++) {
				int np = hull.hulls.get(h).planes().size();
				heroPlane[h] = new boolean[np];
				planeAttrs[h] = new long[np];
				for (int k = 0; k < np; k++) {
					planeAttrs[h][k] = hull.planeAttrs(h, k);
					heroPlane[h][k] = heroSolid(planeAttrs[h][k]);
				}
			}
			open = new boolean[hn];
			for (int h = 0; h < hn; h++) {
				open[h] = openBelow(hull.hulls.get(h).planes());
			}
			joints = LevelLayout.jointWorld(sk, inst);
			prev = joints;
		}

		/**
		 * hull h 가 아래로 열려 있다 (평면 4 개 이상인데 아래를 보는 평면이 없음) — 발판 · 압력판 CDV (PAND04G CDV_PressPlate1–3, PAND01A · PAND03A
		 * CDV_PressPlate, ATHN03A CDV_MinoPressurePlate: 윗면 + 비스듬한 옆면 32 개뿐, PAND04C 우리 크랭크 hull 10). 반공간 교집합이 아래로 끝없는 원뿔이다.
		 */
		final boolean[] open;

		static boolean openBelow(List<float[]> planes) {
			if (planes.size() < 4) {
				return false;
			}
			for (float[] q : planes) {
				if (q[1] < -1e-3f) {
					return false;
				}
			}
			return true;
		}

		/**
		 * 원작 충돌 질의 0x139218 은 hull 평면을 보기 전에 선분이 인스턴스 경계구 (+0x20: 중심 · 반지름) 와 만나는지 판별식으로 거른다
		 * (0x139258 – 0x139318, 만나지 않으면 0) [확실: 코드]. 엔진 hull 질의 (바닥 {@link #top} · 몸 밀어내기 · 선분) 는 경계구를 보지 않아, 아래로 열린
		 * hull 은 몸에서 멀리 떨어진 아래쪽 점도 "안" 이었다 — PAND04G 톱날 구덩이 (영상 5 2996–2998: 톱날에 맞아 떨어져 죽음) 로 떨어진 영웅이
		 * 630 유닛 떨어진 시작 압력판 gopressplate1 의 원뿔 옆면 (y 2147, 판 2719) 에 서서 KillMe 바닥 (1976, Death) 에 닿지 않았다.
		 * 경계구 = BallHull 0x1C (x, y, z, r) 를 hull 관절 로컬로 [근사: 원작 +0x20 을 만드는 곳은 못 찾음 — 압력판은 경계구 중심 = hull 중심 (0, 2.5, 0),
		 * r 20.4], 거르기는 열린 hull 에만 [근사: 닫힌 hull 은 경계구 밖 점이 hull 안일 수 없다].
		 *
		 * @param margin 질의 반지름 (몸 구) — 로컬 단위
		 */
		boolean outsideBound(int h, float lx, float ly, float lz, float margin) {
			if (!open[h]) {
				return false;
			}
			float[] s = hull.sphere;
			float dx = lx - s[0], dy = ly - s[1], dz = lz - s[2], r = s[3] + margin + 1f;
			return dx * dx + dy * dy + dz * dz > r * r;
		}

		void pose() {
			prev = joints;
			if (anim == null) {
				return;
			}
			float[][] lw = anim.sample(skeleton, time, false).world(skeleton);
			float[] m = follow != null ? Mat4.mul(inst, follow) : inst;
			float[][] w = new float[lw.length][];
			for (int i = 0; i < lw.length; i++) {
				w[i] = Mat4.mul(lw[i], m);
			}
			joints = w;
		}

		/** 부모 링크가 있고 부모 관절이 처음 자리에서 움직였는가 (렌더·Marker·볼륨도 이 몸의 자세를 따라야 함). */
		public boolean follows() {
			return follow != null;
		}

		/** 부모 관절 이동 행렬 (처음 관절 월드⁻¹ · 지금), 없으면 null. */
		public float[] follow() {
			return follow;
		}

		/** 이번 틱 부모를 따라 움직였는가. */
		public boolean followMoved() {
			return followMoved;
		}

		/** 부모 관절 이동을 다시 구해 관절 월드에 반영 (부모가 이번 틱 자세를 정한 뒤). */
		void updateFollow() {
			followMoved = false;
			if (parent == null) {
				return;
			}
			float[] pj = parent.joints[Math.min(parentJoint, parent.joints.length - 1)];
			float[] f = Mat4.mul(parentRestInv, pj);
			if (follow == null && nearIdentity(f) || follow != null && java.util.Arrays.equals(follow, f)) {
				return;
			}
			follow = nearIdentity(f) ? null : f;
			float[][] old = joints;
			if (anim != null) {
				pose();
			} else {
				float[][] w = new float[baseJoints.length][];
				for (int i = 0; i < w.length; i++) {
					w[i] = follow != null ? Mat4.mul(baseJoints[i], follow) : baseJoints[i];
				}
				joints = w;
			}
			prev = old;
			followMoved = true;
		}

		/** 들어 올림 → 관절 월드 (이동 행렬을 follow 로 — 렌더·충돌·Marker 가 그대로 따른다, 부모 링크 없는 몸만). */
		void applyLift() {
			// 0 으로 내려와도 단위 이동을 남긴다 — 렌더 자세 (LevelScene.poseInstance) 는 덮어쓸 때까지 남으므로 follows() 로 다시 준다
			// (1015 리셋·놓음으로 떨어진 게이트가 들린 자세로 그려지던 것, 43 문서 §19)
			float[] f = place.clone();
			f[13] += lift; // 행벡터: 이동 = 12..14
			follow = f;
			float[][] old = joints;
			if (anim != null) {
				pose();
			} else {
				float[][] w = new float[baseJoints.length][];
				for (int i = 0; i < w.length; i++) {
					w[i] = follow != null ? Mat4.mul(baseJoints[i], follow) : baseJoints[i];
				}
				joints = w;
			}
			prev = old;
			followMoved = true;
		}

		static boolean nearIdentity(float[] m) {
			for (int i = 0; i < 16; i++) {
				if (Math.abs(m[i] - (i % 5 == 0 ? 1 : 0)) > 1e-4f) {
					return false;
				}
			}
			return true;
		}

		public boolean playing() {
			return playing;
		}

		/** 한 번 재생 중 (반복·왕복이 아님) — 끝나기를 기다릴 수 있는 애니. */
		public boolean playingOnce() {
			return playing && !loop && !pingPong;
		}

		public float time() {
			return time;
		}

		public Animation anim() {
			return anim;
		}

		/** 지금 GO 월드 행렬 (행벡터) = 인스턴스 · 밀린·실려 간 이동 ({@link Platforms#shift} · {@link Platforms#ride}) — Creator 밀기 상자 렌더 기준. */
		public float[] goMatrix() {
			return follow != null ? Mat4.mul(inst, follow) : inst;
		}

		/** 현재 관절 월드 행렬 (Marker 위치 갱신용). */
		public float[][] joints() {
			return joints;
		}

		public Skeleton skeleton() {
			return skeleton;
		}

		/**
		 * 현재 관절 숨김 = act 의 u8 가시성 트랙 (41 §1.3) 과 스크립트 JointVisibility ({@link #setJointVisible}) — 둘 다 없으면 null.
		 * 원작 렌더 행렬 0x137290 은 부모 비트가 없거나 표 값이 0 인 관절을 건너뛰고 비트를 세우지 않아 자손까지 그리지 않는다 [확실] → 꺼진 관절의 자손도 숨김.
		 */
		public boolean[] hidden() {
			boolean[] h = anim == null ? null : anim.hiddenJoints(skeleton, time);
			if (jointOff == null) {
				return h;
			}
			boolean[] out = h != null ? h.clone() : new boolean[skeleton.size()];
			for (int j = 0; j < out.length && j < skeleton.size(); j++) {
				for (int k = j, guard = 0; k >= 0 && guard < 64; k = skeleton.joints.get(k).parent(), guard++) {
					if (k < jointOff.length && jointOff[k]) {
						out[j] = true;
						break;
					}
				}
			}
			return out;
		}

		/**
		 * 스크립트 Vis JointVisibility (슬롯 17) 로 꺼진 관절 (자기 값만, 자손은 {@link #hidden} 이 잇는다). 원작 0x1ABAA8 끝: 값이 0 이 아니면
		 * GO 모델 인스턴스 (+0x104) 의 관절 표 (+0x54, 0x1364F0 이 모두 1 로 만듦) 의 [Vis 엔티티 +0x40 = 붙은 관절] 에 1 → 1, 2 → 0, 3 → 뒤집기를 쓰고
		 * 관절 행렬 캐시 시각 (+0x40) 을 지운다 [확실]. 데이터: PAND00C Ring1Crank-RingRot-crankPlatform01-CrankOff/On (goring1crank 관절 2 — 크랭크 받침·기둥),
		 * EyeDoor-LeftAnim-lever01 / RightAnim-lever02 Off/On (goeyedoor 관절 2 · 8 — 석상 받침 톱니 손잡이), 나머지 (말하는 머리 · ATHN05B 문 빛 ·
		 * PAND01BB 반짝이) 는 관절 0 이고 Visible 과 짝 (43 §36).
		 */
		boolean[] jointOff;

		/** @param value 1 보임, 2 숨김, 3 뒤집기 (0 = 그대로) */
		public void setJointVisible(int joint, int value) {
			if (value == 0 || joint < 0 || joint >= skeleton.size()) {
				return;
			}
			if (jointOff == null) {
				jointOff = new boolean[skeleton.size()];
			}
			jointOff[joint] = value == 3 ? !jointOff[joint] : value == 2;
		}

		/**
		 * 스크립트 JointVisibility 를 받은 적이 있는가 — 애니가 돈 적 없어도 자세 그리기 (관절별) 로 그려야 한다. 다시 다 켜져도 참 (장면의 마지막
		 * 자세 · 숨김이 남지 않도록 계속 넘긴다).
		 */
		public boolean jointsHidden() {
			return jointOff != null;
		}

		/**
		 * hull h 가 영웅 몸을 막는지 — 평면 하나라도 {@link #WALL_EXCLUDE} 에 안 걸리면. 몸 질의 0x1E2100 의 고정 마스크는 0x10042 (바닥 0x1E3298 의
		 * 0x1010042 와 달리 GeneralGuide 없음) 이고 호출 마스크 (0x1EA200 0x8020000 / 0x28020000, 0x1EA5F0 0x20000 | 0x8000000 | 0x20000000) 에도 없다
		 * → GeneralGuide 면 (ATHN01B goplayerblock "guideMat" 등) 은 몸을 막는다 [확실] (38 §18.4).
		 */
		boolean wallHull(int h) {
			return wallHull(h, WALL_EXCLUDE);
		}

		/** hull h 가 몸 제외 마스크 exclude ({@code NavWorld.bodyExclude}) 아래에서 몸을 막는지. */
		boolean wallHull(int h, long exclude) {
			for (long a : planeAttrs[h]) {
				if ((a & exclude) == 0) {
					return true;
				}
			}
			return false;
		}

		/**
		 * (x, z) 수직선이 hull 들과 만나는 구간의 윗면 중 y+step 이하·y-drop 이상에서 가장 높은 것. 없으면 NaN. hull 관절은 out[0],
		 * 윗면 평면의 표면 속성은 attrsOut[0]. 윗면 평면 (아래로 내리는 선분이 처음 맞는 면) 의 재질이 영웅 질의 제외면 그 hull 은 바닥이 아니다.
		 */
		float top(float x, float y, float z, float drop, int[] jointOut) {
			return top(x, y, z, drop, jointOut, null);
		}

		float top(float x, float y, float z, float drop, int[] jointOut, long[] attrsOut) {
			return top(x, y, z, drop, jointOut, attrsOut, null);
		}

		float top(float x, float y, float z, float drop, int[] jointOut, long[] attrsOut, int[] matOut) {
			float best = Float.NaN;
			if (!solid) {
				return best; // 충돌 끔 (Vis Attr11_0 = 2) — 충돌 세계에서 빠짐
			}
			for (int h = 0; h < heroPlane.length; h++) {
				BallHull.Hull hh = hull.hulls.get(h);
				if (hh.planes().size() < 4) {
					continue; // 평면 1 개 = 구 (ENV 전용 표기), 발판 아님
				}
				int j = Math.min(hull.hullJoint[h], joints.length - 1);
				float[] inv = Mat4.invertAffine(joints[j]);
				float[] p0 = new float[3];
				Mat4.transformPoint(inv, x, 0, z, p0);
				float[] p1 = new float[3];
				Mat4.transformPoint(inv, x, 1, z, p1);
				float dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
				float lo = -1e9f, hi = 1e9f;
				int topPlane = -1;
				List<float[]> planes = hh.planes();
				for (int k = 0; k < planes.size(); k++) {
					float[] pl = planes.get(k);
					float a = pl[0] * dx + pl[1] * dy + pl[2] * dz;
					float b = -(pl[0] * p0[0] + pl[1] * p0[1] + pl[2] * p0[2] + pl[3]);
					if (Math.abs(a) < 1e-9f) {
						if (b < 0) {
							lo = 1e9f;
						}
					} else if (a > 0) {
						if (b / a < hi) {
							hi = b / a;
							topPlane = k;
						}
					} else {
						lo = Math.max(lo, b / a);
					}
				}
				if (topPlane < 0 || !heroPlane[h][topPlane]
						|| hi > -1e8f && outsideBound(h, p0[0] + dx * hi, p0[1] + dy * hi, p0[2] + dz * hi, 0f)) {
					continue;
				}
				if (lo <= hi && hi <= y + 1e-3f && hi >= y - drop && (Float.isNaN(best) || hi > best)) {
					best = hi;
					jointOut[0] = j;
					if (attrsOut != null) {
						attrsOut[0] = planeAttrs[h][topPlane];
					}
					if (matOut != null) {
						int[] pm = hh.planeMaterials();
						matOut[0] = topPlane < pm.length ? pm[topPlane] : hh.nameIndex(); // contactDamage 와 같은 규칙
					}
				}
			}
			return best;
		}
	}

	private final List<Body> bodies = new ArrayList<>();

	/**
	 * 영웅 충돌 질의의 면 제외 마스크 [확실]: 바닥 0x1E3298 / 몸 0x1E2100 이 0x3309F4 = 호출 마스크 | 0x1010042 | (영웅 +0x258 &amp; 0x4300000) 로 둔다.
	 * 0x1010042 = Water·Death·AIBlock·GeneralGuide, 영웅 +0x258 = 0x500000 (Player 생성 0x1D63E0) → NoPlayerCollision,
	 * 바닥 호출 마스크 0x8020000 (0x1C58D8 등) = ClimbGuide·CombatGuide. 면 (시트 삼각형·CDV 평면) 재질 bitfield0 이 하나라도 걸리면 그 면은 건너뛴다.
	 */
	public static final long HERO_EXCLUDE = SurfaceAttr.WATER.bit() | SurfaceAttr.DEATH.bit() | SurfaceAttr.AI_BLOCK.bit()
			| SurfaceAttr.GENERAL_GUIDE.bit() | SurfaceAttr.NO_PLAYER_COLLISION.bit() | SurfaceAttr.CLIMB_GUIDE.bit() | SurfaceAttr.COMBAT_GUIDE.bit();

	/**
	 * 벽 (구 밀어내기) 기본 제외 마스크 = {@link #HERO_EXCLUDE} − GeneralGuide [확실: 0x1E2224 고정 0x10042] = 기술 없는 영웅
	 * ({@code NavWorld.HERO_BODY_EXCLUDE}). ClimbGuide 는 벽에 붙어 있을 때, CombatGuide 는 플레이어가 피격 반응 (+0x13 = 2) 이 아닌 전투 기술 중일 때
	 * 막는다 (0x1EA6A0 / 0x1EA6C0) — 상태별 마스크는 {@code NavWorld.bodyExclude} 로 {@link #collideSphere(Vector3f, float, Collection, long)} 에 넘긴다.
	 */
	public static final long WALL_EXCLUDE = HERO_EXCLUDE & ~SurfaceAttr.GENERAL_GUIDE.bit();

	/** 표면 속성의 면이 영웅 바닥 질의에 걸리는지 ({@link #HERO_EXCLUDE} 에 하나도 안 걸림). */
	public static boolean heroSolid(long attrs) {
		return (attrs & HERO_EXCLUDE) == 0;
	}

	public Platforms() {
	}

	/**
	 * 재질 애니 시계 (Animator 슬롯 0 "$이름", 원작 0x1AB0B8 의 '$' 갈래 [확실: 코드]): "MAT_" + 이름 (공백·탭 건너뜀, 모두 31 자) 의 리소스를
	 * 0x17A7B8 로 찾고, 대상 GO 모델의 재질 목록 (+0xD0) 에서 그 리소스의 인스턴스를 찾아 그 +0x44 애니 제어기에 관절 애니와 같은 모드 규칙
	 * (0x10 정지 · 0x11 이어 · 0x12 세움 · 0x1 역 · 0x8 시간 유지 · 0x4/0x2 재생 방식) 으로 act 0 을 튼다 (이름 해시 비교 없음).
	 * 재질 ANM act 플래그 0x41 = 생성 때부터 반복 재생, 0x42 = 스크립트가 틀 때까지 시간 0 (16 §3.1).
	 * 예 ATHN02D 미노타우로스 뒤 남쪽 문: doorFadeAnim "$M01DoorFade1" (가산 불꽃) · subShaderAnim1 "$doorSub" (감산 p28 0.5 × 색 2 = 검은 막)
	 * 이 6–7.5 초에 p28 → 0 — 예전에는 무시돼 문이 검은 채 남았다 (43 §14).
	 */
	public static final class MatClock {
		public final String owner;
		public final String material;
		final float duration;
		float time;
		float rate = 1;
		boolean playing;
		boolean loop;
		boolean pingPong;
		/** 스크립트가 한 번이라도 틀었는가 — 아니면 0x42 (스크립트용 한 번) 재질은 색 트랙을 걸지 않는다 ({@code MaterialFx#times}, 43 §33). */
		boolean started;

		MatClock(String owner, String material, float duration) {
			this.owner = owner;
			this.material = material;
			this.duration = duration;
		}

		public float time() {
			return time;
		}

		public boolean playing() {
			return playing;
		}
	}

	private final List<MatClock> matClocks = new ArrayList<>();
	/** 재질 애니를 찾을 WAD ({@link #of}) — 처음 "$" 를 만날 때 읽는다. */
	private devple.gow.tools.wad.WadFile matWad;
	private java.util.Map<String, devple.gow.tools.fx.MaterialAnim.Anim> matAnims;

	private devple.gow.tools.fx.MaterialAnim.Anim materialAnim(String mat) {
		if (matAnims == null) {
			matAnims = matWad != null ? devple.gow.tools.fx.MaterialAnim.load(matWad) : java.util.Map.of();
		}
		return matAnims.get(mat);
	}

	/** "$이름" → "MAT_이름" (원작 0x1AB1E4: 0x20 바이트 버퍼에 "MAT_" + 앞 공백·탭을 건너뛴 이름 strncpy 0x1C, [0x1F] = 0 → 모두 31 자까지). */
	public static String materialName(String anim) {
		String n = anim.substring(1).replaceFirst("^[ \\t]+", "");
		String m = "MAT_" + n;
		return m.length() > 0x1F ? m.substring(0, 0x1F) : m;
	}

	private MatClock matClock(String owner, String mat, boolean create) {
		for (MatClock c : matClocks) {
			if (c.owner.equalsIgnoreCase(owner) && c.material.equals(mat)) {
				return c;
			}
		}
		if (!create) {
			return null;
		}
		devple.gow.tools.fx.MaterialAnim.Anim a = materialAnim(mat);
		if (a == null) {
			return null; // 원작 "Can't find material '%s'" / "No animation on this object"
		}
		MatClock c = new MatClock(owner.toLowerCase(java.util.Locale.ROOT), mat, Math.max(a.duration(), 0));
		// 생성 때 상태: act 0x41 (반복) 은 바로 돌고, 0x42 (스크립트용 한 번) 는 시간 0 에 서 있다
		c.loop = a.loop();
		c.playing = a.loop();
		matClocks.add(c);
		return c;
	}

	/** 스크립트 재질 애니 대상 등록 (생성 상태로, 스크립트가 틀기 전부터 렌더가 이 시계를 쓴다 — 0x42 재질이 레벨 시간으로 저절로 돌지 않게). */
	public boolean registerMaterial(String owner, String anim) {
		return owner != null && anim != null && anim.startsWith("$") && matClock(owner, materialName(anim), true) != null;
	}

	/** 소유 GO (소문자) → 재질 이름 → 재질 애니 시간 (렌더). */
	public java.util.Map<String, java.util.Map<String, Float>> materialTimes() {
		if (matClocks.isEmpty()) {
			return java.util.Map.of();
		}
		java.util.Map<String, java.util.Map<String, Float>> out = new java.util.HashMap<>();
		for (MatClock c : matClocks) {
			// 아직 틀지 않은 0x42 재질 = NaN (색 트랙 없이 MAT 기본 색, 프레임·UV·불투명도는 시간 0 — MaterialFx.times)
			out.computeIfAbsent(c.owner, k -> new java.util.HashMap<>()).put(c.material, c.loop || c.started ? c.time : Float.NaN);
		}
		return out;
	}

	public List<MatClock> materialClocks() {
		return matClocks;
	}

	/** "$" 재질 애니 ({@link #play} 의 '$' 갈래, 모드 규칙은 관절 애니와 같다). */
	private boolean playMaterial(String owner, String anim, int mode, float speed, float start) {
		MatClock c = matClock(owner, materialName(anim), true);
		if (System.getProperty("gow.debug.mat") != null) {
			System.out.printf("재질 애니 %s %s 모드 0x%x → %s%n", owner, materialName(anim), mode, c == null ? "재질/애니 없음" : "길이 " + c.duration + " 반복 " + c.loop);
		}
		if (c == null) {
			return false;
		}
		c.started |= mode != 0x10;
		if (mode == 0x10 || mode == 0x11) {
			c.playing = mode == 0x11;
			return true;
		}
		if (mode == 0x12) {
			c.time = Math.max(0, Math.min(start, c.duration));
			c.rate = speed;
			c.loop = c.pingPong = false;
			c.playing = false;
			return true;
		}
		boolean keep = (mode & 0x8) != 0;
		c.rate = (mode & 0x1) != 0 ? -speed : speed;
		c.pingPong = (mode & 0x4) != 0;
		c.loop = !c.pingPong && (mode & 0x2) != 0;
		if (!keep) {
			c.time = (mode & 0x1) != 0 && start <= 0 ? c.duration : Math.min(start, c.duration);
		}
		c.playing = true;
		return true;
	}

	/**
	 * 레벨에서 발판 후보: CDV 볼륨이 있고 ANM 이 있으며 owners(스크립트 Animator 엔티티가 가리키는 GO) 에 든 오브젝트.
	 */
	public static Platforms of(LevelData lv, Collection<String> owners) {
		return of(lv, owners, java.util.Set.of());
	}

	/**
	 * posed = 충돌 볼륨이 없어도 자세만 갖는 오브젝트 (R1 레버·크랭크·문: 진행 시간으로 관절 자세 → Marker·렌더, {@link #poseAt}).
	 * 그 밖에 CDV 가 있는 배치 GO 도 모두 넣는다 ({@link #staticOwner}) — 원작은 GO 를 만들 때 CDV 리소스마다 BallHull 인스턴스를 만들어
	 * 충돌 세계 0x29C978 에 올린다 (리소스 vtable 0x2BF75C → 0x11D498 → 0x138E70 → 0x138FD0) [추정: 생성 경로]. 예 ATHN02A 정박한 배
	 * goshipgroup 갑판 = ATHN01F Warp 도착점 바로 아래 (43 §13.10).
	 */
	public static Platforms of(LevelData lv, Collection<String> owners, Collection<String> posed) {
		Platforms ps = new Platforms();
		ps.matWad = lv.wad;
		java.util.Map<String, Integer> ghosts = lv.wad != null ? devple.gow.engine.fx.GhostTrail.owners(lv.wad) : java.util.Map.of(); // SCR_GhostTrail → 머리 관절
		for (LevelLayout.Placement p : lv.layout.placements()) {
			String name = p.instance().name();
			boolean scripted = owners.contains(name) || posed.contains(name);
			if (!scripted && !staticOwner(lv, p) || p.object().skeleton() == null) {
				continue;
			}
			BallHull cdv = null;
			String anm = null;
			for (String r : p.object().refs()) {
				BallHull h = lv.hulls.get(r);
				if (h != null && h.kind == 0 && (!h.hulls.isEmpty() || breakableBalls(lv, name, h) || damageBalls(h))) {
					cdv = h;
				}
				if (r.startsWith("ANM_")) {
					anm = r;
				}
			}
			// 충돌 없는 Animator 대상 (ATHN01F 침대 미니게임 gobedshake: 선반 항아리 bedShake00–03/bedshakeWin/Fail/Reset) 도 자세만 갖는 몸으로 —
			// 원작 Animator 0x1AB0B8 은 대상 GO 의 애니 컴포넌트를 틀 뿐 충돌과 무관 [확실: 코드]. 예전에는 CDV 가 없으면 빠져 애니가 재생되지 않았다
			if (cdv == null && !posed.contains(name) && !(owners.contains(name) && anm != null)) {
				continue;
			}
			AnimationSet set = null;
			try {
				set = anm != null ? AnimationSet.load(lv.wad, anm) : null;
			} catch (RuntimeException ignored) {
				// 애니 없는 발판은 정지 상태로만
			}
			Body body = new Body(name, p.object().skeleton(), p.instance().matrix(), set, cdv);
			body.scripted = scripted;
			// 시작 자세 = 인스턴스 시작 act 를 시작 시간에 (원작 GO 리셋 0x1309C0, StartPose) — Marker·hull 도 렌더와 같은 자세
			devple.gow.tools.level.StartPose sp = devple.gow.tools.level.StartPose.of(p, set);
			if (sp.act() != null) {
				body.joints = sp.jointWorld();
				body.prev = body.joints;
				if (!scripted && lv.selfAnimates(p) && sp.act().looping() && sp.act().duration > 0 && sp.act().hasSkeletalData()) {
					// 정지 CDV 오브젝트의 반복 시작 act (렌더의 반복 재생 배치와 같은 규칙, LevelScene) — 충돌도 그 자세를 따른다 (HDES01C 죽음 굴림대 등).
					// 인스턴스 플래그 비트 0 이 꺼진 GO 는 돌지 않는다 (GO +0xF8, 0x130F48 — LevelData.selfAnimates)
					float d = sp.act().duration;
					body.anim = sp.act();
					body.time = ((sp.time() % d) + d) % d;
					body.loop = true;
					body.playing = true;
				}
			}
			body.baseJoints = body.joints;
			Integer ghostHead = ghosts.get(name.toLowerCase(java.util.Locale.ROOT));
			if (ghostHead != null) {
				body.ghost = devple.gow.engine.fx.GhostTrail.of(p.object().skeleton(), ghostHead, body.joints); // 생성 자세 (0x20D988 → 0x20DB38)
			}
			ps.bodies.add(body);
		}
		// GO 부모 링크 (0x58): 자식 몸이 부모 몸의 관절을 따른다 (둘 다 몸일 때)
		for (LevelLayout.Placement p : lv.layout.placements()) {
			int pid = p.instance().parentId();
			Body child = pid < 0 ? null : ps.body(p.instance().name());
			if (child == null) {
				continue;
			}
			for (LevelLayout.Placement q : lv.layout.placements()) {
				Body par = q.instance().id() == pid ? ps.body(q.instance().name()) : null;
				if (par != null && par != child) {
					child.parent = par;
					child.parentJoint = Math.max(0, p.instance().parentJoint());
					child.parentRestInv = Mat4.invertAffine(par.joints[Math.min(child.parentJoint, par.joints.length - 1)]);
					break;
				}
			}
		}
		return ps;
	}

	public List<Body> bodies() {
		return bodies;
	}

	/**
	 * 스크립트가 움직이지 않는 CDV 오브젝트 (정지 충돌): 평면 hull 이 있는 CDV 를 갖고, 다른 시스템이 맡는 비헤이비어 GO (상자·밀기·크랭크 등,
	 * {@code LevelData.behaviors}) 가 아닌 것. 깨지는 물건 (Breakable) 은 포함 — 원작은 GO 를 만들 때 CDV 마다 충돌 인스턴스를 올리고,
	 * Breakable 비헤이비어는 그것을 건드리지 않는다 → 부서지기 전에는 몸을 막는다 (ATHN01B 선창 침상 gobreakablebunk01–08·쓰러진 기둥 gofallenposts,
	 * 41 §2.1). 부서지면 {@link #setSolid} 로 끈다.
	 */
	static boolean staticOwner(LevelData lv, LevelLayout.Placement p) {
		String name = p.instance().name();
		for (var e : lv.behaviors.entrySet()) {
			if (e.getKey().equalsIgnoreCase(name) && !"Breakable".equals(e.getValue().type())) {
				return false;
			}
		}
		for (String r : p.object().refs()) {
			BallHull h = lv.hulls.get(r);
			if (h != null && h.kind == 0 && (breakableBalls(lv, name, h) || damageBalls(h))) {
				return true;
			}
			if (h != null && h.kind == 0) {
				for (BallHull.Hull hh : h.hulls) {
					if (hh.planes().size() >= 4) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 공 (ball) 만 있는 Breakable CDV — PAND02D/02DD 물속 돌진 문 godashdoorgrp* (공 1 개, 반지름 56, 재질 Ground · TakeDamage),
	 * ARENA06 항아리 (반지름 16). 원작 Breakable 접촉 0x1DE4D8 (충돌 접촉 0x203AF0 → 0x203CD4 가 부름) 은 공격이 없을 때 돌진 중인
	 * (+0x2D8 0x80000) 몸이 Sold+0x214 (18 m/s) 보다 빠르면 1000 피해를 준다 — 몸이 물체 충돌 인스턴스에 닿아야 오는 접촉이므로 공도 몸을 막는다
	 * [확실: 접촉 코드 · 공 질의 0x139C20 (인스턴스 +0x3C 공 수, +0x88 공 월드) / 근사: 엔진은 Breakable 의 공만 몸 구 밀어내기, 바닥 · 선분 질의 없음].
	 * 재질이 NoPlayerCollision 이면 막지 않는다 (몸 제외 마스크).
	 */
	static boolean breakableBalls(LevelData lv, String owner, BallHull h) {
		if (h.balls.isEmpty() || !h.hulls.isEmpty()) {
			return false;
		}
		for (var e : lv.behaviors.entrySet()) {
			if (e.getKey().equalsIgnoreCase(owner)) {
				return "Breakable".equals(e.getValue().type());
			}
		}
		return false;
	}

	/**
	 * 공 (ball) 만 있고 재질에 접촉 피해 ("Damage" / "AIDamage" &gt; 0) 가 있는 CDV — PAND03C 굴러오는 불덩이 goleftrock* · gomidrock* · gorightrock*
	 * (공 1 개, 관절 1, 반지름 43.2, Ground · Slide, 피해 20 / 적 1024). 원작은 모든 CDV 를 충돌 세계에 올리고 (0x138FD0) 몸 질의 0x1E2100 이
	 * 공에 닿으면 접촉 0x1E1DF0 이 그 재질의 피해를 건다 (43 §46) [확실: 접촉 · 피해 / 근사: 엔진은 피해 공만 몸 구 밀어내기 — 피해 없는 장식 공은 예전처럼 뺌].
	 */
	static boolean damageBalls(BallHull h) {
		if (h.balls.isEmpty() || !h.hulls.isEmpty()) {
			return false;
		}
		for (int i = 0; i < h.materialDamage.length; i++) {
			if (h.materialDamage[i] > 0 || h.materialAiDamage[i] > 0) {
				return true;
			}
		}
		return false;
	}

	/** 접촉 판정 여유 (밀어내기가 몸 구를 면에서 정확히 반지름 거리에 두므로 그 자리도 "닿음"). */
	static final float CONTACT_SKIN = 0.5f;

	/**
	 * 몸 구 (c, r) 가 닿은 CDV 재질의 접촉 피해 중 최댓값 — 원작 0x1E1DF0: 몸·바닥 질의 (0x1E3298 바닥 · 0x1E36B0 · 0x1E2F20 · 0x1E1F28) 가 맞은
	 * 면의 재질 레코드 (+0x2C) 에서 플레이어 (+0x174 비트 31) 면 "Damage", 아니면 "AIDamage" 를 읽어 0 이 아니면 생물 vtable +0xA8 (대기 피해 +0x280 ·
	 * 접촉점 +0x270 · SheetFX +0x284) 에 둔다 [확실]. 공 = 공 중심 거리 &lt; 공 반지름 + r, hull = 모든 평면 부호 거리 &lt; r 이면 가장 얕은 (거리가 큰) 평면의
	 * 재질 [근사: 원작 질의는 면별 — 볼록 hull 의 맞은 면 = 가장 얕은 면]. 충돌 꺼진 몸, skip, 재질 &amp; exclude ≠ 0 인 면은 빼고, 몸 밀어내기와 같은 몸 목록.
	 *
	 * @param ai true = 적 ("AIDamage")
	 * @return 가장 큰 피해와 그 재질 SheetFX (없으면 {@link Touch#NONE})
	 */
	public Touch contactDamage(Vector3f c, float r, boolean ai, Collection<String> skip, long exclude) {
		int best = 0, fx = -1;
		float[] w = new float[3];
		for (Body b : bodies) {
			if (!b.solid || b.hull == null || skip.contains(b.owner)) {
				continue;
			}
			BallHull h = b.hull;
			int[] dmg = ai ? h.materialAiDamage : h.materialDamage;
			if (!anyPositive(dmg)) {
				continue;
			}
			if (h.hulls.isEmpty()) {
				for (int i = 0; i < h.balls.size(); i++) {
					int mat = h.ballMaterial[i];
					if (mat < 0 || mat >= dmg.length || dmg[mat] <= best || (h.materialAttrs[mat] & exclude) != 0) {
						continue;
					}
					float[] ball = h.balls.get(i);
					float[] m = b.joints[Math.min(Math.max(h.ballJoint[i], 0), b.joints.length - 1)];
					Mat4.transformPoint(m, ball[0], ball[1], ball[2], w);
					float scale = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
					if (c.distance(w[0], w[1], w[2]) < ball[3] * scale + r + CONTACT_SKIN) {
						best = dmg[mat];
						fx = h.materialFx[mat];
					}
				}
				continue;
			}
			for (int hi = 0; hi < h.hulls.size(); hi++) {
				BallHull.Hull hh = h.hulls.get(hi);
				if (hh.planes().size() < 4) {
					continue;
				}
				float[] m = b.joints[Math.min(h.hullJoint[hi], b.joints.length - 1)];
				float[] lp = new float[3];
				Mat4.transformPoint(Mat4.invertAffine(m), c.x, c.y, c.z, lp);
				float scale = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
				float lr = (r + CONTACT_SKIN) / Math.max(1e-3f, scale);
				float sdMax = -Float.MAX_VALUE;
				int face = -1;
				for (int k = 0; k < hh.planes().size(); k++) {
					float[] pl = hh.planes().get(k);
					float len = (float) Math.sqrt(pl[0] * pl[0] + pl[1] * pl[1] + pl[2] * pl[2]);
					if (len < 1e-6f) {
						continue;
					}
					float sd = (pl[0] * lp[0] + pl[1] * lp[1] + pl[2] * lp[2] + pl[3]) / len;
					if (sd > sdMax) {
						sdMax = sd;
						face = k;
					}
				}
				if (face < 0 || sdMax >= lr) {
					continue;
				}
				int[] pm = hh.planeMaterials();
				int mat = face < pm.length ? pm[face] : hh.nameIndex();
				if (mat >= 0 && mat < dmg.length && dmg[mat] > best && (h.materialAttrs[mat] & exclude) == 0) {
					best = dmg[mat];
					fx = h.materialFx[mat];
				}
			}
		}
		return best > 0 ? new Touch(best, fx) : Touch.NONE;
	}

	/** 접촉 피해 (재질 "Damage"/"AIDamage") 와 그 재질의 SheetFX (MFX 템플릿, 피격 분기 +0x1A 키). */
	public record Touch(int damage, int sheetFx) {
		public static final Touch NONE = new Touch(0, -1);

		/** 피해가 큰 쪽. */
		public static Touch max(Touch a, Touch b) {
			return b.damage > a.damage ? b : a;
		}
	}

	private static boolean anyPositive(int[] a) {
		for (int v : a) {
			if (v > 0) {
				return true;
			}
		}
		return false;
	}

	/** 오브젝트 애니 상태 (이음 넘기기: 떠나온 레벨은 원작에서 다른 슬롯에 그대로 남는다, 43 §13.8). */
	public record State(String owner, String anim, float time, float rate, boolean playing, boolean loop, boolean pingPong, boolean solid, float lift) {
	}

	/** 애니가 한 번이라도 걸렸거나 충돌이 꺼진 (스크립트 Vis Attr11_0 "DoorCDOff" 등) 오브젝트의 상태. */
	public List<State> states() {
		List<State> out = new ArrayList<>();
		for (Body b : bodies) {
			if (b.anim != null || !b.solid || b.lift != 0) {
				out.add(new State(b.owner, b.anim != null ? b.anim.name : null, b.time, b.rate, b.playing, b.loop, b.pingPong, b.solid, b.lift));
			}
		}
		for (MatClock c : matClocks) { // 재질 애니 = anim "$MAT_…"
			out.add(new State(c.owner, "$" + c.material, c.time, c.rate, c.playing, c.loop, c.pingPong, true, 0));
		}
		return out;
	}

	/** {@link #states()} 를 되살린다 (같은 레벨의 새 Platforms). */
	public void restore(List<State> states) {
		for (State s : states) {
			if (s.anim() != null && s.anim().startsWith("$")) {
				MatClock c = matClock(s.owner(), s.anim().substring(1), true);
				if (c != null) {
					c.time = s.time();
					c.rate = s.rate();
					c.playing = s.playing();
					c.loop = s.loop();
					c.pingPong = s.pingPong();
					c.started = s.playing() || s.time() > 0; // 튼 적 있는 재질 (기억에 없는 값 — 돌았거나 시간이 지났으면)
				}
				continue;
			}
			Body b = body(s.owner());
			if (b == null) {
				continue;
			}
			b.solid = s.solid();
			if (s.lift() != 0 && b.parent == null) {
				b.lift = s.lift(); // 들어 올린 TAndF 문 (열린 창살) 그대로
				b.applyLift();
			}
			if (b.anims == null || s.anim() == null) {
				continue;
			}
			Animation a = b.anims.find(s.anim());
			if (a == null) {
				continue;
			}
			b.anim = a;
			b.time = s.time();
			b.rate = s.rate();
			b.playing = s.playing();
			b.loop = s.loop();
			b.pingPong = s.pingPong();
			b.pose();
		}
	}

	public Body body(String owner) {
		for (Body b : bodies) {
			if (b.owner.equalsIgnoreCase(owner)) {
				return b;
			}
		}
		return null;
	}

	/** 몸 owner 의 ANM 에 그 이름의 act 가 있는가 (대소문자 무시). */
	public boolean hasAct(String owner, String act) {
		Body b = body(owner);
		return b != null && b.anims != null && act != null && b.anims.find(act) != null;
	}

	/**
	 * TAndF 들어 올리는 문의 물리 높이 (원작 0x214058: 잡고 있는 동안 문 Y = 영웅 붙잡기 관절 (영웅 클래스 +0xEA) 높이, 0x214180: 끝 (+0x1E4 = 2)
	 * 이면 중력 /GlobGame/Gravity 50 × 16 으로 처음 Y (+0x1E8) + 템플릿 +0x11C (#8743c048) × 16 까지 올라가 멈추고, 놓으면 같은 중력으로 처음 Y 까지
	 * 내려온다) [확실: 코드]. 문 ANM 에 템플릿 act (liftdoor2/liftdoor3) 가 없는 문 (ATHN04A LiftDoor13 "LiftDoor13" 길이 0 하나) 은 이것으로만 움직인다.
	 *
	 * @return 높이가 바뀌었으면 true
	 */
	public boolean lift(String owner, float dy) {
		Body b = body(owner);
		if (b == null || b.parent != null || Math.abs(b.lift - dy) < 1e-3f) {
			return false;
		}
		b.lift = dy;
		b.applyLift();
		return true;
	}

	/**
	 * 영웅이 민 상자 (PushPull Type 0, 원작 0x211A10 이 GO 위치를 옮김) 를 (dx, dy, dz) 만큼 — 렌더 자세·CDV 충돌·Marker·자식 GO (부모 링크) 가 같이 간다.
	 * ATHN05B 발리스타 BallistaPlatfor 의 크랭크 gobiggun1crank · 팔 gobiggun1anim 은 부모 링크 (0x58) 로 붙어 있다 [확실: 데이터]. 예전 엔진은 상자
	 * 위치 (Interactables.Item.pos) 와 Marker 만 옮겨 상자가 제자리에 그려졌다 (43 문서 §22).
	 *
	 * @return 몸이 있으면 true
	 */
	public boolean shift(String owner, float dx, float dy, float dz) {
		Body b = body(owner);
		if (b == null || b.parent != null || dx == 0 && dy == 0 && dz == 0) {
			return false;
		}
		b.place[12] += dx;
		b.place[13] += dy;
		b.place[14] += dz;
		b.applyLift();
		return true;
	}

	/**
	 * 발판 관절에 실린 몸 (밀기 상자) 을 그 관절의 이번 틱 움직임 d (행벡터, 월드 → 월드) 만큼 — 렌더·CDV·자식 GO 가 같이 간다 ({@link #ride(Support, Vector3f)}
	 * 이 만든 yaw 만 남긴 변환). 자식 몸 (부모 링크) 은 같은 틱에 다시 따라가게 한다.
	 *
	 * @return 몸이 있으면 true
	 */
	public boolean ride(String owner, float[] d) {
		Body b = body(owner);
		if (b == null || b.parent != null) {
			return false;
		}
		b.place = Mat4.mul(b.place, d);
		b.applyLift();
		for (Body c : bodies) {
			if (c.parent == b) {
				c.updateFollow(); // 크랭크·팔 (gobiggun1crank / gobiggun1anim) 이 같은 틱에 따라옴
			}
		}
		return true;
	}

	/**
	 * 받침 s 의 관절이 이번 틱 움직였으면 그 위에 실린 점 pivot 을 옮기는 변환 (행벡터) — 원작 0x1E6248 [확실: 코드]: 지면 GO (+0x24C) 관절 (+0x250) 의
	 * 지금 행렬 × 지난 행렬⁻¹ (+0x1C0, 0x185048) 에서 위 = (0, 1, 0) 로 다시 직교화해 yaw 만 남기고 (0x1E6424 – 0x1E64A4), 몸의 회전 행 (+0x50…) 과
	 * 위치를 그 변환으로 돌리고 옮긴다. 상자 갱신 0x212398 (Type 0/7/8, 0x21252C) 과 영웅 0x1EBD78 이 매 틱 부른다. 여기서는 받침 관절의 전체
	 * 변환으로 pivot 의 새 자리를 구하고 회전은 yaw 만 쓴다.
	 *
	 * @return 변환 (움직이지 않았으면 null)
	 */
	public static float[] ride(Support s, Vector3f pivot) {
		if (s == null || s.body.prev == s.body.joints || s.joint >= s.body.prev.length) {
			return null;
		}
		float[] d = Mat4.mul(Mat4.invertAffine(s.body.prev[s.joint]), s.body.joints[s.joint]);
		if (Body.nearIdentity(d)) {
			return null;
		}
		float a = d[8], c = d[10], n = (float) Math.sqrt(a * a + c * c);
		if (n < 1e-6f) {
			return null;
		}
		a /= n;
		c /= n;
		float[] r = Mat4.identity(); // 행 0 = 위 × 행 2, 행 1 = (0, 1, 0), 행 2 = (a, 0, c)
		r[0] = c;
		r[2] = -a;
		r[8] = a;
		r[10] = c;
		float[] to = new float[3];
		Mat4.transformPoint(d, pivot.x, pivot.y, pivot.z, to);
		float[] rot = new float[3];
		Mat4.transformPoint(r, pivot.x, pivot.y, pivot.z, rot);
		r[12] = to[0] - rot[0];
		r[13] = to[1] - rot[1];
		r[14] = to[2] - rot[2];
		return r;
	}

	/** 몸 owner 의 이름이 joint 인 관절의 지금 월드 행렬 (행벡터, 대소문자 무시). 없으면 null. */
	public float[] jointWorld(String owner, String joint) {
		Body b = body(owner);
		if (b == null || b.skeleton == null || joint == null) {
			return null;
		}
		for (int j = 0; j < b.skeleton.joints.size() && j < b.joints.length; j++) {
			if (b.skeleton.joints.get(j).name().equalsIgnoreCase(joint)) {
				return b.joints[j];
			}
		}
		return null;
	}

	/** owner 와 그 부모 링크 조상, 그리고 그 조상에 매달린 다른 몸 (형제) — 쏜 GO 자기 CDV 를 판정에서 뺄 때 (발리스타 ← 회전판). */
	public java.util.Set<String> family(String owner) {
		java.util.Set<String> out = new java.util.HashSet<>();
		Body b = body(owner);
		if (b == null) {
			out.add(owner);
			return out;
		}
		java.util.Set<Body> up = new java.util.HashSet<>();
		for (Body x = b; x != null && up.add(x); x = x.parent) {
			out.add(x.owner);
		}
		for (Body x : bodies) {
			if (x.parent != null && up.contains(x.parent)) {
				out.add(x.owner);
			}
		}
		return out;
	}

	/**
	 * Animator 엔티티 (SCR AnimName): "!n" = 오브젝트 세트의 n 번째 act [추정: ATHN05D 엘리베이터 "!0" = 유일한 act], 그 밖에는 act 이름.
	 *
	 * @return 재생을 시작했으면 true
	 */
	public boolean play(String owner, String animName) {
		return play(owner, animName, 0, 1, 0);
	}

	/**
	 * Animator 효과 (원작 0x1AB0B8) [확실: 코드]: 슬롯 0 이름 ("!n" = n 번째 act, "$…" = 재질 애니 → {@link MatClock}), 슬롯 1 모드 비트,
	 * 슬롯 2 속도, 슬롯 3 시작 시간.
	 * <ul>
	 *   <li>0x10 = 일시 정지, 0x11 = 이어 재생 (그 밖의 설정 없음)</li>
	 *   <li>0x1 = 역재생 (속도 부호 반전, 시작 시간 ≤ 0 이면 애니 끝에서), 0x8 = 시간 유지 (지금 자리에서 이어서)</li>
	 *   <li>재생 방식 0x100A60: 0x4 → 4, 0x2 → 1, 그 밖 → 2 [추정: 4 왕복, 1 반복, 2 한 번]</li>
	 * </ul>
	 */
	public boolean play(String owner, String animName, int mode, float speed, float start) {
		if (animName != null && animName.startsWith("$")) {
			return owner != null && playMaterial(owner, animName, mode, speed, start); // 재질 애니 ({@link MatClock})
		}
		Body b = body(owner);
		if (b == null || b.anims == null) {
			return false;
		}
		if (mode == 0x10 || mode == 0x11) {
			if (b.anim == null) {
				return false;
			}
			b.playing = mode == 0x11;
			return true;
		}
		Animation a = null;
		List<Animation> all = b.anims.all();
		if (animName != null && animName.startsWith("!")) {
			try {
				int i = Integer.parseInt(animName.substring(1));
				a = i >= 0 && i < all.size() ? all.get(i) : null;
			} catch (NumberFormatException ignored) {
				// 이름으로
			}
		}
		if (a == null && animName != null) {
			a = b.anims.find(animName);
		}
		if (a == null && !all.isEmpty()) {
			a = all.getFirst();
		}
		if (a == null) {
			return false;
		}
		if (mode == 0x12) {
			// 0x12 = 그 act 를 시작 시간 (슬롯 3) 에 세우고 속도 0 (0x1AB5E8 → 0x1AB394: 0x100578(시간) + 애니 +0x3C = 0) [확실: 디스어셈블].
			// ATHN02C LazySueGroup-AniPause: 회전판을 3 초 뒤 다음 멈춤 자리 (0 / 3 / 6 초) 에 세운다. 예전엔 0x2 (반복) 로 읽어 계속 돌았다
			b.anim = a;
			b.time = Math.max(0, Math.min(start, a.duration));
			b.rate = speed;
			b.loop = b.pingPong = false;
			b.playing = false;
			b.pose();
			return true;
		}
		boolean keep = (mode & 0x8) != 0 && b.anim == a;
		b.anim = a;
		b.rate = (mode & 0x1) != 0 ? -speed : speed;
		b.pingPong = (mode & 0x4) != 0;
		b.loop = !b.pingPong && (mode & 0x2) != 0;
		if (!keep) {
			b.time = (mode & 0x1) != 0 && start <= 0 ? a.duration : Math.min(start, a.duration);
		}
		b.playing = true;
		return true;
	}

	/**
	 * 진행 시간으로 자세 (R1 오브젝트: 원작 오브젝트 애니 시간 = 과제 진행 시간, 0x215928 / 0x2118E8). act = null → 가장 긴 act.
	 *
	 * @return 자세가 바뀌었으면 true
	 */
	public boolean poseAt(String owner, String act, float t) {
		Body b = body(owner);
		if (b == null || b.anims == null) {
			return false;
		}
		if (b.anim == null || act != null && !b.anim.name.equalsIgnoreCase(act)) {
			Animation a = act != null ? b.anims.find(act) : null;
			if (a == null) {
				for (Animation x : b.anims.all()) {
					if (a == null || x.duration > a.duration) {
						a = x;
					}
				}
			}
			if (a == null) {
				return false;
			}
			b.anim = a;
			b.time = -1;
		}
		if (Math.abs(b.time - t) < 1e-5f) {
			return false;
		}
		b.time = t;
		b.playing = false;
		b.pose();
		return true;
	}

	public void update(float dt) {
		for (Body b : bodies) {
			if (b.playing && b.anim != null) {
				float d = b.anim.duration;
				b.time += dt * b.rate;
				if (b.time > d || b.time < 0) {
					if (b.loop && d > 0) {
						b.time = ((b.time % d) + d) % d;
					} else if (b.pingPong && d > 0) {
						b.time = b.time > d ? 2 * d - b.time : -b.time;
						b.rate = -b.rate;
					} else {
						b.time = Math.max(0, Math.min(d, b.time));
						b.playing = false;
					}
				}
				b.pose();
			} else {
				b.prev = b.joints;
			}
		}
		for (Body b : bodies) {
			b.updateFollow(); // 부모가 이번 틱 자세를 정한 뒤 (목록 순서 — 부모가 먼저 오는 한 겹 링크만 같은 틱에 맞음)
		}
		for (MatClock c : matClocks) {
			if (!c.playing) {
				continue;
			}
			float d = c.duration;
			c.time += dt * c.rate;
			if (c.time > d || c.time < 0) {
				if (c.loop && d > 0) {
					c.time = ((c.time % d) + d) % d;
				} else if (c.pingPong && d > 0) {
					c.time = c.time > d ? 2 * d - c.time : -c.time;
					c.rate = -c.rate;
				} else {
					c.time = Math.max(0, Math.min(d, c.time));
					c.playing = false;
				}
			}
		}
	}

	/**
	 * 구 밀어내기 (CDV 볼록 hull = 문·창살·회전방 벽): hull 관절 로컬로 옮겨 각 평면 n·x + d 의 부호 거리 중 최댓값이 반지름보다 작으면
	 * 그 평면 법선 방향으로 밀어낸다 (가장 얕게 파고든 면) [근사: 모서리·꼭짓점 영역은 면으로 처리]. skip = 붙잡는 오브젝트 (레버·크랭크·상자).
	 *
	 * @return 밀렸으면 true
	 */
	public boolean collideSphere(org.joml.Vector3f c, float r, Collection<String> skip) {
		return collideSphere(c, r, skip, WALL_EXCLUDE);
	}

	/** {@link #collideSphere(Vector3f, float, Collection)} 를 몸 제외 마스크 exclude 로 ({@code NavWorld.bodyExclude}, 38 §18.6). */
	public boolean collideSphere(org.joml.Vector3f c, float r, Collection<String> skip, long exclude) {
		boolean moved = false;
		for (int iter = 0; iter < 3; iter++) {
			boolean any = false;
			for (Body b : bodies) {
				if (b.solid && b.hull != null && !skip.contains(b.owner)) {
					any |= pushOut(b, c, r, exclude);
					if (!b.hull.balls.isEmpty() && b.hull.hulls.isEmpty()) {
						any |= pushOutBalls(b, c, r, exclude);
					}
				}
			}
			moved |= any;
			if (!any) {
				break;
			}
		}
		return moved;
	}

	/** 공 CDV ({@link #breakableBalls}): 공 중심 = 공 관절 월드 × 로컬 중심, 반지름 × 관절 스케일 — 겹치면 중심에서 바깥으로 민다. */
	private static boolean pushOutBalls(Body b, org.joml.Vector3f c, float r, long exclude) {
		boolean moved = false;
		float[] w = new float[3];
		for (int i = 0; i < b.hull.balls.size(); i++) {
			int mat = b.hull.ballMaterial[i];
			if (mat >= 0 && mat < b.hull.materialAttrs.length && (b.hull.materialAttrs[mat] & exclude) != 0) {
				continue;
			}
			float[] ball = b.hull.balls.get(i);
			float[] m = b.joints[Math.min(Math.max(b.hull.ballJoint[i], 0), b.joints.length - 1)];
			Mat4.transformPoint(m, ball[0], ball[1], ball[2], w);
			float scale = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
			float br = ball[3] * scale + r;
			float dx = c.x - w[0], dy = c.y - w[1], dz = c.z - w[2];
			float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d >= br - 1e-3f) {
				continue;
			}
			if (d < 1e-4f) {
				dx = 0;
				dy = 1;
				dz = 0;
				d = 1;
			}
			float k = br / d;
			c.set(w[0] + dx * k, w[1] + dy * k, w[2] + dz * k);
			moved = true;
		}
		return moved;
	}

	private static boolean pushOut(Body b, org.joml.Vector3f c, float r, long exclude) {
		boolean moved = false;
		for (int h = 0; h < b.hull.hulls.size(); h++) {
			BallHull.Hull hh = b.hull.hulls.get(h);
			if (hh.planes().size() < 4 || !b.wallHull(h, exclude)) {
				continue;
			}
			float[] m = b.joints[Math.min(b.hull.hullJoint[h], b.joints.length - 1)];
			float[] inv = Mat4.invertAffine(m);
			float[] lp = new float[3];
			Mat4.transformPoint(inv, c.x, c.y, c.z, lp);
			float scale = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
			float lr = r / Math.max(1e-3f, scale);
			if (b.outsideBound(h, lp[0], lp[1], lp[2], lr)) {
				continue; // 경계구 밖 (0x139218, 열린 hull)
			}
			float best = -Float.MAX_VALUE;
			float[] bn = null;
			for (float[] pl : hh.planes()) {
				float len = (float) Math.sqrt(pl[0] * pl[0] + pl[1] * pl[1] + pl[2] * pl[2]);
				if (len < 1e-6f) {
					continue;
				}
				float sd = (pl[0] * lp[0] + pl[1] * lp[1] + pl[2] * lp[2] + pl[3]) / len;
				if (sd > best) {
					best = sd;
					bn = new float[]{pl[0] / len, pl[1] / len, pl[2] / len};
				}
			}
			if (bn != null && best < lr - 1e-3f) {
				float push = lr - best;
				float[] w = new float[3];
				Mat4.transformPoint(m, lp[0] + bn[0] * push, lp[1] + bn[1] * push, lp[2] + bn[2] * push, w);
				c.set(w[0], w[1], w[2]);
				moved = true;
			}
		}
		return moved;
	}

	/**
	 * 선분 from → to 가 처음 들어가는 CDV hull 면 (볼록 hull 평면 클리핑, hull 관절 로컬). 들어가는 평면의 재질 표면 속성 &amp; exclude ≠ 0 이면 그 hull 은 지나간다
	 * (면 거름 0x139218 [근사: 원작은 평면마다 거른 뒤 다음 면을 본다 — 볼록 hull 이라 같은 hull 의 뒤쪽 면은 들어가는 면이 아님]). 시작점이 hull 안이면 맞지 않음.
	 * 충돌 꺼진 (Vis Attr11_0 = 2) 오브젝트는 빠진다.
	 *
	 * @return 가장 가까운 접촉 (없으면 null)
	 */
	public NavWorld.Contact segment(Vector3f from, Vector3f to, long exclude) {
		NavWorld.Contact best = null;
		float[] a = new float[3], b = new float[3];
		for (Body bd : bodies) {
			if (!bd.solid || bd.hull == null) {
				continue;
			}
			for (int h = 0; h < bd.hull.hulls.size(); h++) {
				BallHull.Hull hh = bd.hull.hulls.get(h);
				List<float[]> planes = hh.planes();
				if (planes.size() < 4) {
					continue;
				}
				float[] m = bd.joints[Math.min(bd.hull.hullJoint[h], bd.joints.length - 1)];
				float[] inv = Mat4.invertAffine(m);
				Mat4.transformPoint(inv, from.x, from.y, from.z, a);
				Mat4.transformPoint(inv, to.x, to.y, to.z, b);
				float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
				float t0 = -Float.MAX_VALUE, t1 = 1f;
				int enter = -1;
				boolean miss = false;
				for (int k = 0; k < planes.size() && !miss; k++) {
					float[] pl = planes.get(k);
					float sa = pl[0] * a[0] + pl[1] * a[1] + pl[2] * a[2] + pl[3];
					float sd = pl[0] * dx + pl[1] * dy + pl[2] * dz;
					if (Math.abs(sd) < 1e-9f) {
						miss = sa > 0;
					} else if (sd < 0) {
						float t = -sa / sd;
						if (t > t0) {
							t0 = t;
							enter = k;
						}
					} else {
						t1 = Math.min(t1, -sa / sd);
					}
				}
				if (miss || enter < 0 || t0 < 0 || t0 > t1 || (bd.planeAttrs[h][enter] & exclude) != 0
						|| bd.outsideBound(h, a[0] + dx * t0, a[1] + dy * t0, a[2] + dz * t0, 0f)
						|| best != null && t0 >= best.t()) {
					continue;
				}
				float[] pl = planes.get(enter);
				float[] n = new float[3];
				Mat4.transformDir(m, pl[0], pl[1], pl[2], n);
				Vector3f nw = new Vector3f(n[0], n[1], n[2]);
				if (nw.lengthSquared() > 1e-12f) {
					nw.normalize();
				}
				int[] pm = hh.planeMaterials();
				int mat = enter < pm.length ? pm[enter] : -1;
				int fx = mat >= 0 && mat < bd.hull.materialFx.length ? bd.hull.materialFx[mat] : 0;
				best = new NavWorld.Contact(t0, new Vector3f(from).lerp(to, t0), nw, fx, bd.owner, bd.planeAttrs[h][enter]);
			}
		}
		return best;
	}

	/**
	 * 레벨 배치가 아닌 GO 의 충돌 (Creator 가 만든 R_PERMK 상자 gochest, 38 §21 · Creator 밀기 상자 DEST02 PushPullBlock, 43 §32): 인스턴스 행렬 inst 에
	 * CDV hull. 같은 이름이 있으면 바꾼다. 밀기 상자는 {@link #shift} · {@link #ride} 로 옮겨진다 (바인드 관절 = 처음 관절 월드).
	 */
	public Body add(String owner, Skeleton sk, float[] inst, BallHull cdv) {
		bodies.removeIf(b -> b.owner.equalsIgnoreCase(owner));
		Body b = new Body(owner, sk, inst, null, cdv);
		b.scripted = false;
		b.baseJoints = b.joints;
		bodies.add(b);
		return b;
	}

	/** 소유 GO 의 충돌 켜고 끄기 (스크립트 Vis Attr11_0, 부서진 Breakable). */
	public void setSolid(String owner, boolean on) {
		Body b = body(owner);
		if (b != null) {
			b.solid = on;
		}
	}

	/** 가장 높은 발판 윗면 (없으면 null). */
	public Support floor(float x, float y, float z, float drop) {
		return floor(x, y, z, drop, java.util.Set.of());
	}

	/** 가장 높은 발판 윗면 — skip 몸 (밀기 상자 자신과 그 자식) 은 빼고 (없으면 null). */
	public Support floor(float x, float y, float z, float drop, Collection<String> skip) {
		Support best = null;
		int[] j = new int[1];
		long[] at = new long[1];
		int[] mt = new int[1];
		for (Body b : bodies) {
			if (!skip.isEmpty() && skip.contains(b.owner)) {
				continue;
			}
			mt[0] = -1;
			float t = b.top(x, y, z, drop, j, at, mt);
			if (!Float.isNaN(t) && (best == null || t > best.y)) {
				best = new Support(t, b, j[0], at[0], mt[0]);
			}
		}
		return best;
	}

	/** 지지 관절의 이번 틱 이동을 점에 적용 (발판에 실려 감). */
	public static void carry(Vector3f p, Support s) {
		if (s == null || s.body.prev == s.body.joints) {
			return;
		}
		float[] inv = Mat4.invertAffine(s.body.prev[s.joint]);
		float[] l = new float[3];
		Mat4.transformPoint(inv, p.x, p.y, p.z, l);
		float[] w = new float[3];
		Mat4.transformPoint(s.body.joints[s.joint], l[0], l[1], l[2], w);
		p.set(w[0], w[1], w[2]);
	}

	/**
	 * 도착점 아래 정적 바닥이 없을 때: 발판 애니 중 윗면이 점 바로 아래(±tol)에 오는 시각으로 자세를 맞춘다 [근사].
	 * 원작은 Warp 직전 레벨에서 엘리베이터가 그 위치에 있다 (ATHN05D WarpFrom4A: 엘리베이터 바닥 677 = 애니 끝).
	 *
	 * @return 맞춘 발판 (없으면 null)
	 */
	public Body settleUnder(Vector3f p, float tol) {
		int[] j = new int[1];
		// 생성 스크립트가 이미 재생 중인 발판 (PAND05B 시작 엘리베이터: 생성 센서 → Animator "!0", t=0 윗면 281 = Start 282) 은 그 자세가
		// 도착점을 받치면 그대로 둔다 — 시간을 맞춰 멈추면 원작 하강 연출이 사라진다 (43 §13.8)
		for (Body b : bodies) {
			if (b.playing && b.anim != null && !Float.isNaN(b.top(p.x, p.y + tol, p.z, 2 * tol, j))) {
				return b;
			}
		}
		for (Body b : bodies) {
			if (!b.scripted && !Float.isNaN(b.top(p.x, p.y + tol, p.z, 2 * tol, j))) {
				return b; // 정지 CDV 오브젝트 바닥 (ATHN02A 정박한 배 갑판)
			}
		}
		for (Body b : bodies) {
			if (b.anims == null || b.playing || !b.scripted) {
				continue;
			}
			Animation a0 = b.anim; // 맞지 않으면 원래 상태로 (이음으로 넘겨받은 열린 문 등, 43 §13.8)
			float t0 = b.time;
			boolean p0 = b.playing;
			float[][] j0 = b.joints;
			for (Animation a : b.anims.all()) {
				for (float t = a.duration; t >= 0; t -= Math.max(a.duration / 60f, 1 / 30f)) {
					b.anim = a;
					b.time = t;
					b.pose();
					b.prev = b.joints;
					float top = b.top(p.x, p.y + tol, p.z, 2 * tol, j);
					if (!Float.isNaN(top)) {
						b.playing = false;
						return b;
					}
				}
			}
			b.anim = a0;
			b.time = t0;
			b.playing = p0;
			b.joints = a0 == null ? LevelLayout.jointWorld(b.skeleton, b.inst) : j0;
			b.prev = b.joints;
		}
		return null;
	}
}
