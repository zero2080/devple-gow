package devple.gow.engine.game;

import devple.gow.engine.fx.EffectSystem;
import devple.gow.engine.fx.ParticleDef;
import devple.gow.engine.fx.ParticleSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;

/**
 * 영웅 물속 거품 (43 §50.1-3). 원작 영웅 부품 (vtable 0x2BADE0, 생성 0x1FE270 ← 영웅 생성 0x1D62B0) 의 갱신 0x1FE518 [확실: 디스어셈블]:
 * <ul>
 *   <li>처음 갱신 0x1FE3A0 이 거품 객체 (0x1FF0A8) 를 만든다 — R_PERMK <b>gobubpart</b> (모양 PTC_Bubpart · MAT_bubble2TX, 수명 1 초, 이미터 0 개)
 *       파티클 서버에 콜백 0x1FF3E0 을 걸고, 영웅 관절 여섯 (표 0x29F2C0: lMetatarsal, rMetatarsal, pelvis, lwrist, rwrist, neck) 의 번호를 잡는다.</li>
 *   <li>영웅이 물 상태 (+0x174 &amp; 0x380 — 떠 있기 · 가라앉기 · 잠수) 면 매 프레임 누적 (+0x20) += 0.5 + |속도 +0xE0| × 0.5 × 1/16 (0x1FE938 – 0x1FE99C:
 *       60 × (…) × 1/60), 잠수 돌진 (+0x2D8 0x80000) 이 이 프레임 켜졌으면 +100 (0x1FE9B4). 물 밖이면 콜백만 남은 거품을 돌린다.</li>
 *   <li>콜백 0x1FF3E0 (매 프레임, 칸마다): 살아 있는 거품은 위치 += 속도 (프레임 단위), 나이 + 0.01 ≥ 1 이거나 위치 y &gt; 수면 (+0x230) 이면 지운다.
 *       빈 칸은 누적 ≥ 1 인 동안 새 거품 — k = 난수 [0, 4), 끝점 E = (lMetatarsal, rMetatarsal, lwrist, rwrist)[k], 뿌리 R = (pelvis, neck)[k/2],
 *       위치 = E + (R − E) · r² (VSUB · VMULx · VADD, 0x1FF73C – 0x1FF758 — 손발 쪽에 몰림), 속도 = (0, (0.5 + 0.5 r') · 16 / 60, 0) (VADDx y 만,
 *       0x1FF76C – 0x1FF7A0 — 위로 초당 8 – 16), 누적 −= 1.</li>
 * </ul>
 * 엔진 [근사]: 이미터 여럿을 틱마다 새 거품 수만큼 각자의 난수 점에 두고 한 개씩 낸다 (파티클 시스템이 위치 · 수명 · VU 식을 맡음). 수면 위 점은 내지 않고
 * (원작은 만든 뒤 콜백이 지움), 살아 있는 거품의 수면 지우기는 없다 (수명 1 초 × 16 = 수면 위 최대 16).
 */
public final class HeroBubbles {

	/** 콜백 스택 배치 (sp+0x00 … 0x50): 끝점 넷 → 뿌리 둘. */
	static final String[] ENDS = {"lMetatarsal", "rMetatarsal", "lwrist", "rwrist"};
	static final String[] ROOTS = {"pelvis", "neck"};
	/** 0x1FE96C: 누적 += (0.5 + |v|·0.5·0.0625) (프레임마다). */
	static final float BASE = 0.5f, PER_SPEED = 0.5f * 0.0625f;
	/** 잠수 돌진 시작 프레임 (0x1FE9B4: 100.0). */
	static final float DASH_BURST = 100f;
	/** 거품 상승 속도 (0.5 + 0.5 r) · 16 /초 → 가운데 12 ± 4. */
	static final float RISE = 12f, RISE_RND = 4f;
	/** 한 틱에 새로 낼 최대 거품 (이미터 수). 돌진 +100 은 여러 틱에 나뉜다 (원작도 빈 칸 수 · PTC 최대 1000 까지). */
	static final int SLOTS = 8;

	private final ParticleSystem.Emitter[] ems;
	private final Random rnd = new Random(0x1FF3E0);
	private float acc;
	private boolean wasDashing;

	private HeroBubbles(ParticleSystem.Emitter[] ems) {
		this.ems = ems;
	}

	/** gobubpart 모양이 없으면 (R_PERMK 없음) null. */
	public static HeroBubbles of(EffectSystem fx) {
		ParticleDef def = fx.shapeDef("BubpartShape", new float[]{0, RISE, 0}, new float[]{0, RISE_RND, 0}, 0.5f);
		if (def == null) {
			return null;
		}
		ParticleSystem.Emitter[] ems = new ParticleSystem.Emitter[SLOTS];
		for (int i = 0; i < SLOTS; i++) {
			ems[i] = fx.particles().add(def, 0, 0, 0);
			ems[i].rate = 0;
		}
		return new HeroBubbles(ems);
	}

	/** 누적 증가량 (프레임 = 1/60 초). */
	static float perFrame(float speed) {
		return BASE + speed * PER_SPEED;
	}

	/** 시험용 (이미터 없음). */
	HeroBubbles() {
		this(new ParticleSystem.Emitter[0]);
	}

	/** 누적 (+0x20) 을 이번 틱만큼 올리고 새로 낼 거품 수 (최대 {@link #SLOTS}) 를 뺀다. 물 밖이면 0 으로. */
	int take(boolean inWater, boolean dashing, float speed, float dt) {
		if (!inWater || dt <= 0) {
			acc = 0;
			wasDashing = false;
			return 0;
		}
		acc += perFrame(speed) * dt * 60f;
		if (dashing && !wasDashing) {
			acc += DASH_BURST;
		}
		wasDashing = dashing;
		int n = Math.min((int) acc, SLOTS);
		acc -= Math.max(n, 0);
		return n;
	}

	/** 끝점 E 에서 뿌리 R 쪽으로 r² (손발 쪽에 몰린다). */
	static Vector3f spawnPoint(Vector3f end, Vector3f root, float r) {
		return new Vector3f(end).lerp(root, r * r);
	}

	/**
	 * @param inWater 영웅이 물 상태 (떠 있기 · 가라앉기 · 잠수)
	 * @param dashing 잠수 돌진 중
	 * @param speed   영웅 속도 크기 (월드/초)
	 * @param surface 수면 높이
	 * @param joints  관절 이름 → 월드 행렬 (없으면 false)
	 * @return 이번 틱 새 거품 수
	 */
	public int update(boolean inWater, boolean dashing, float speed, float surface, float dt, JointLookup joints) {
		for (ParticleSystem.Emitter e : ems) {
			e.rate = 0;
		}
		int n = take(inWater, dashing, speed, dt);
		if (n <= 0) {
			return 0;
		}
		Matrix4f m = new Matrix4f();
		Vector3f[] end = new Vector3f[4], root = new Vector3f[2];
		for (int i = 0; i < 4; i++) {
			end[i] = joints.world(ENDS[i], m) ? m.getTranslation(new Vector3f()) : null;
		}
		for (int i = 0; i < 2; i++) {
			root[i] = joints.world(ROOTS[i], m) ? m.getTranslation(new Vector3f()) : null;
		}
		int made = 0;
		for (int i = 0; i < n; i++) {
			int k = rnd.nextInt(4);
			if (end[k] == null || root[k >> 1] == null) {
				continue;
			}
			Vector3f p = spawnPoint(end[k], root[k >> 1], rnd.nextFloat());
			if (p.y > surface) {
				continue; // 수면 위 거품은 원작 콜백이 다음 프레임에 지운다
			}
			ems[made].pos.set(p);
			ems[made].rate = 1f / dt;
			made++;
		}
		return made;
	}

	/** 관절 월드 행렬 조회. */
	public interface JointLookup {
		boolean world(String joint, Matrix4f out);
	}
}
