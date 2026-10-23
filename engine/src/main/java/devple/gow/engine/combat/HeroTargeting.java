package devple.gow.engine.combat;

import org.joml.Vector3f;

import java.util.List;
import java.util.function.Function;

/**
 * 영웅 대상 고르기 0x1D9CB0 (플레이어 갱신 0x1D8470 → 매 프레임, 잡기 플래그 0x8 이 아닐 때 → combatant +0x80) [확실]:
 * <ol>
 *   <li>후보 = Targeting Range × 16 구 안의 생물 (구 질의 0x11E8D0, 콜백 0x1D9B88: 죽음·표적 불가 0x1E1A50 제외), 위치 = 중심 (vslot 0xE8)</li>
 *   <li><b>유지</b>: 스틱 크기 ≤ 0.8 이고 이전 대상이 후보에 남아 있으면 그대로 (0x1D9E04: +0xF0.w &gt; 0.8 0x3F4CCCCD, 또는 이전 대상 없음일 때만 다시)</li>
 *   <li>다시 고름: 스틱 벡터 s (카메라 기준 월드, 길이 = 스틱 크기 — 입력 갱신 0x1DD5F0 이 +0xF0 에), d = 후보 − 영웅, d' = d 의 수평 성분
 *       (위 축 +0xA0 성분 제거), c = s·d' / |d| 가 0.5 (0x3F000000) 보다 큰 후보 중 |d| / c² 최소 — 스틱 방향 ±60° 안에서 가깝고 스틱 쪽에 가까운 적.
 *       스틱이 중립이면 c = 0 → 대상 없음 (이전 대상이 사라진 뒤 스틱을 놓으면 대상도 없다)</li>
 * </ol>
 * 자동 조준 0x2077A0 이 기술 중 이 대상 쪽으로 돈다 ({@link EnemyFighter#autoAim}, {@code HeroController.updateCombat}).
 */
public final class HeroTargeting {

	/** /Player/ "Targeting Range" 10 (R_PERMK, 등록 0x1D5810 → 0x30408C) × 16. */
	public static final float RANGE = 10f * 16f;
	/** 다시 고르는 스틱 크기 (0x1D9E08). */
	public static final float RESELECT_STICK = 0.8f;
	/** 후보 콘 c 하한 (0x1D9EC0). */
	public static final float MIN_COS = 0.5f;

	private HeroTargeting() {
	}

	/**
	 * @param prev       이전 대상 (없으면 null)
	 * @param candidates 후보 (이미 범위·생사 거른 것)
	 * @param center     후보 → 중심 위치
	 * @param hero       영웅 위치 (루트)
	 * @param stickX     스틱 월드 벡터 x (길이 = 스틱 크기 ≤ 1)
	 * @param stickZ     스틱 월드 벡터 z
	 */
	public static <T> T select(T prev, List<T> candidates, Function<T, Vector3f> center, Vector3f hero, float stickX, float stickZ) {
		float mag = (float) Math.hypot(stickX, stickZ);
		if (prev != null && mag <= RESELECT_STICK && candidates.contains(prev)) {
			return prev;
		}
		T best = null;
		float bestScore = Float.MAX_VALUE;
		for (T t : candidates) {
			Vector3f d = new Vector3f(center.apply(t)).sub(hero);
			float dist = d.length();
			if (dist < 1e-3f) {
				continue;
			}
			float c = (stickX * d.x + stickZ * d.z) / dist;
			if (c <= MIN_COS) {
				continue;
			}
			float score = dist / (c * c);
			if (score < bestScore) {
				bestScore = score;
				best = t;
			}
		}
		return best;
	}

	/** 스틱 (x, y) + 카메라 yaw → 월드 스틱 벡터 (x, z), 길이 = min(1, 크기). 영웅 이동과 같은 규약 (yaw = camYaw + atan2(−x, y), 정면 −Z). */
	public static float[] stickWorld(float sx, float sy, float camYaw) {
		float mag = Math.min(1f, (float) Math.hypot(sx, sy));
		float yaw = camYaw + (float) Math.atan2(-sx, sy);
		return new float[]{-(float) Math.sin(yaw) * mag, -(float) Math.cos(yaw) * mag};
	}
}
