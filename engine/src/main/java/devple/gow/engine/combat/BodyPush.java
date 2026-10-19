package devple.gow.engine.combat;

import devple.gow.tools.combat.CombatFile;
import org.joml.Vector3f;

import java.util.function.BooleanSupplier;

/**
 * 생물끼리 밀어내기 — 원작 공용 내비게이션의 이동 단계 0x1EA200 / 0x1EA5F0 가 벽 충돌(0x1E2100) <b>앞에</b> 부르는 0x1E2DD8 과
 * 그 질의 콜백 0x1E28B0 (32 문서 §생물끼리 밀어내기) [확실: 흐름·수식].
 * <ul>
 *   <li>몸 = 수직 캡슐. 상자 owner+0x120 = (지름, 키, 지름) · +0x130 = (0, 바닥, 0) · +0x140 = 키 − 바닥 (0x1E6D58: Sold +0x234 반폭 ×2,
 *       +0x238 키, +0x23C 바닥, × 16 × 개체 스케일 +0x168).</li>
 *   <li>자기 쪽 기준점 b = 발 − 바닥 (0x1E2E44), 선분 [b + R, b + (키 − 바닥) − R]. 상대 쪽 선분 [발 + r, 발 + 키 − r] (0x1E2A8C–0x1E2AEC).
 *       두 선분 최근접 거리 (0x12F7F8) &lt; R + r 이면 겹침, 깊이 = R + r − 거리, 방향 n = 자기 최근접점 − 상대 최근접점.</li>
 *   <li>질의 필터 = 생물 범주 1|2 (0x1E2E68, 영웅·적 모두), 자신과 붙잡은 대상(+0x188/+0x18C) 제외. 상대의 <b>현재 기술 +0x12 비트 2</b>
 *       (CombatFile Move.stateMask — 기술 없으면 CombatFileSet #9C811767 +0x38, 데이터 전부 0x1F) 가 있어야 단단함 (0x1E2968–0x1E29AC).
 *       stateMask 1 (비트 2 없음) = 목 베기 사망·잡기 처형(mai*)·벽 잡기·스폰·하피 swipe 등 → 통과.</li>
 *   <li>자신이 공중 (+0x174 &amp; 0x4C) 이면 n × 깊이 전부. 지상이면 공중인 상대는 무시, 그 밖은 n × 깊이 (자유),
 *       벽 오르기 (0x20) 는 n 을 몸 위·오른쪽 축에 투영 (벽 평면 안에서만), 턱 매달림 (0x10) 은 오른쪽 축만, 사다리 (0x400000) 는 상대보다 아래일 때 위 축만.</li>
 *   <li><b>자기만 민다</b> (상대는 움직이지 않음) — 상대도 자기 갱신에서 같은 계산을 하므로 순서대로 갈라진다. 결과는 누적 (콜백마다 갱신된 기준점).
 *       owner+0x2D8 비트 31 이면 건너뜀 (0x1EA670), 줄 (0x400) 이동 0x1C5848 은 부르지 않음.</li>
 * </ul>
 */
public final class BodyPush {

	private BodyPush() {
	}

	/** 밀어내기에 참여하는 생물 몸 (월드 단위). */
	public interface Body {
		/** 발 위치. */
		Vector3f feet();

		/** 캡슐 반지름 = Sold +0x234 × 16 × 스케일. */
		float bodyRadius();

		/** 키 = Sold +0x238 × 16 × 스케일. */
		float bodyHeight();

		/** 바닥 높이 = Sold +0x23C × 16 × 스케일. */
		float bodyBase();

		/** 공중 (+0x174 &amp; 0x4C: 점프·낙하·날기). */
		boolean airborne();

		/** 단단함 = 현재 기술 stateMask 비트 2 (없으면 CombatFileSet 기본값). false 면 다른 생물이 이 몸을 통과한다. */
		boolean solid();
	}

	/** 기술 stateMask 비트 2 (0x1E29A8) — 기술이 없으면 CombatFileSet 기본 0x1F (데이터 전부) → 단단함. */
	public static boolean solid(Combatant c) {
		if (c == null || !c.active()) {
			return true;
		}
		CombatFile.Move m = c.move();
		return m == null || (m.stateMask() & 2) != 0;
	}

	/** 영웅 몸 (크라토스 Sold_000, 스케일 1). */
	public static Body hero(Vector3f feet, BooleanSupplier airborne, Combatant combat) {
		AiData.Body b = AiData.Body.HERO;
		return new Body() {
			public Vector3f feet() {
				return feet;
			}

			public float bodyRadius() {
				return b.half() * 16f;
			}

			public float bodyHeight() {
				return b.height() * 16f;
			}

			public float bodyBase() {
				return b.base() * 16f;
			}

			public boolean airborne() {
				return airborne.getAsBoolean();
			}

			public boolean solid() {
				return BodyPush.solid(combat);
			}
		};
	}

	/**
	 * self 를 others 밖으로 민다 (0x1E2DD8). feet 는 이번 틱 이동 후 발 위치 (갱신됨).
	 *
	 * @param wallNormal 벽 오르기 중이면 벽 법선 (밀기를 벽 평면 = 위·오른쪽 축에 투영), 아니면 null
	 * @return 밀렸는지 (원작 반환 0x400)
	 */
	public static boolean push(Body self, Vector3f feet, Iterable<? extends Body> others, Vector3f wallNormal) {
		if (others == null) {
			return false;
		}
		float r0 = self.bodyRadius();
		float base = self.bodyBase();
		float span = self.bodyHeight() - base; // +0x140
		boolean selfAir = self.airborne();
		boolean pushed = false;
		for (Body o : others) {
			if (o == null || o == self || !o.solid()) {
				continue;
			}
			// 자기 선분 (0x1E2A50–0x1E2AC0): 기준점 b = 발 − 바닥
			float by = feet.y - base;
			float a0 = by + r0, a1 = Math.max(a0, by + span - r0);
			float r1 = o.bodyRadius();
			Vector3f q = o.feet();
			float b0 = q.y + r1, b1 = Math.max(b0, q.y + o.bodyHeight() - r1);
			// 두 수직 선분의 최근접점 (0x12F7F8)
			float pa, pb;
			if (a1 < b0) {
				pa = a1;
				pb = b0;
			} else if (b1 < a0) {
				pa = a0;
				pb = b1;
			} else {
				pa = pb = 0;
			}
			float dx = feet.x - q.x, dy = pa - pb, dz = feet.z - q.z;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			float sum = r0 + r1;
			if (dist >= sum) {
				continue;
			}
			if (!selfAir && o.airborne()) {
				continue; // 0x1E2B80: 자신 지상 + 상대 공중 → 밀지 않음
			}
			Vector3f n;
			if (dist < 1e-4f) {
				n = new Vector3f(1, 0, 0); // 같은 점: 원작은 0 벡터 정규화 (정의 안 됨) [근사: +x]
			} else {
				n = new Vector3f(dx, dy, dz).div(dist);
			}
			float depth = sum - dist;
			Vector3f d;
			if (!selfAir && wallNormal != null) {
				// 0x1E2CA8: 벽 오르기 — 몸 위축(+0x60)·오른쪽축(+0x50) 성분만
				Vector3f up = new Vector3f(0, 1, 0);
				Vector3f right = new Vector3f(wallNormal).negate().cross(up);
				if (right.lengthSquared() < 1e-6f) {
					continue;
				}
				right.normalize();
				d = new Vector3f(up).mul(n.dot(up) * depth).fma(n.dot(right) * depth, right);
			} else {
				d = n.mul(depth);
			}
			feet.add(d);
			pushed = true;
		}
		return pushed;
	}
}
